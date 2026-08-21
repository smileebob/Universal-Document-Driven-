package com.example.common.infrastructure.tablestore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QuerySpec 结构化检索验收测试（InMemoryTablestore 近似检索）：
 * term/terms/wildcard/prefix/range/exists/nestedTerm/nestedWildcard + 排序 + limit/next_token + totalCount。
 */
class InMemorySearchTest {

    private static final String DOC_DATA = "doc_data";
    private static final String DOC_DATA_INDEX = "doc_data_index";

    private InMemoryTablestore template;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        seed("sales_order", 10L, "alice", 1000L, "{\"customer_name\":\"华为技术\"}");
        seed("sales_order", 20L, "bob", 2000L, "{\"customer_name\":\"阿里软件\"}");
        seed("sales_order", 30L, "alice", 3000L, "{\"customer_name\":\"腾讯云\"}");
        seed("purchase_order", 10L, "carol", 4000L, null);
        seed("sales_order", 99L, "dave", 5000L, "{\"customer_name\":\"华为云计算\"}");
    }

    private void seed(String docCode, long status, String creator, long createTime, String attributes) {
        String uuid = UUID.randomUUID().toString();
        TsPrimaryKey.Builder pkBuilder = TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(docCode, uuid))
                .add("uuid", uuid);
        Map<String, TsColumnValue> columns = new java.util.LinkedHashMap<>(Map.of(
                "doc_code", TsColumnValue.of(docCode),
                "current_status", TsColumnValue.of(status),
                "creator", TsColumnValue.of(creator),
                "create_time", TsColumnValue.of(createTime)));
        if (attributes != null) {
            columns.put("attributes", TsColumnValue.of(attributes));
        }
        template.putRow(DOC_DATA, new TsRow(pkBuilder.build(), columns));
    }

    @Test
    @DisplayName("term 等值 + AND 组合")
    void term_and_multipleConditions() {
        SearchResult result = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .term("doc_code", TsColumnValue.of("sales_order"))
                .term("creator", TsColumnValue.of("alice"))
                .build());
        assertThat(result.getRows()).hasSize(2);
    }

    @Test
    @DisplayName("terms 多值属于")
    void terms_in() {
        SearchResult result = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .terms("current_status", List.of(TsColumnValue.of(10L), TsColumnValue.of(99L)))
                .build());
        assertThat(result.getRows()).hasSize(3);
    }

    @Test
    @DisplayName("wildcard 通配（*华为*）与 prefix 前缀")
    void wildcard_and_prefix() {
        SearchResult wildcard = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .wildcard("attributes", "*华为*")
                .build());
        assertThat(wildcard.getRows()).hasSize(2);

        SearchResult prefix = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .prefix("creator", "al")
                .build());
        assertThat(prefix.getRows()).hasSize(2);
    }

    @Test
    @DisplayName("range 范围（含开闭区间）与 exists 列存在")
    void range_and_exists() {
        SearchResult closed = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .range("create_time", TsColumnValue.of(2000L), true, TsColumnValue.of(4000L), true)
                .build());
        assertThat(closed.getRows()).hasSize(3);

        SearchResult open = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .range("create_time", TsColumnValue.of(2000L), false, TsColumnValue.of(4000L), false)
                .build());
        assertThat(open.getRows()).hasSize(1);

        SearchResult exists = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .exists("attributes")
                .build());
        assertThat(exists.getRows()).hasSize(4);
    }

    @Test
    @DisplayName("nestedTerm：attributes JSON 子字段等值（对象与缺失容器）")
    void nestedTerm() {
        SearchResult hit = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .nestedTerm("attributes", "customer_name", TsColumnValue.of("腾讯云"))
                .build());
        assertThat(hit.getRows()).hasSize(1);

        SearchResult miss = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .nestedTerm("attributes", "customer_name", TsColumnValue.of("不存在公司"))
                .build());
        assertThat(miss.getRows()).isEmpty();
    }

    @Test
    @DisplayName("nestedWildcard：attributes JSON 子字段通配（*华为*，contains 降级路径）")
    void nestedWildcard() {
        SearchResult hit = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .nestedWildcard("attributes", "customer_name", "*华为*")
                .build());
        assertThat(hit.getRows()).hasSize(2);

        SearchResult miss = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .nestedWildcard("attributes", "customer_name", "*不存在*")
                .build());
        assertThat(miss.getRows()).isEmpty();

        // 缺失 attributes 容器的行不得命中
        SearchResult noContainer = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .nestedWildcard("attributes", "customer_name", "*")
                .build());
        assertThat(noContainer.getRows()).hasSize(4);
    }

    @Test
    @DisplayName("排序 + limit + next_token 分页续翻 + getTotalCount")
    void sort_limit_nextToken_totalCount() {
        QuerySpec page1Spec = QuerySpec.builder()
                .term("doc_code", TsColumnValue.of("sales_order"))
                .sortAsc("create_time")
                .limit(2)
                .getTotalCount(true)
                .build();
        SearchResult page1 = template.search(DOC_DATA, DOC_DATA_INDEX, page1Spec);
        assertThat(page1.getRows()).hasSize(2);
        assertThat(page1.getTotalCount()).isEqualTo(4L);
        assertThat(page1.isHasNext()).isTrue();
        assertThat(page1.getRows().get(0).getLong("create_time")).isEqualTo(1000L);

        SearchResult page2 = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .term("doc_code", TsColumnValue.of("sales_order"))
                .sortAsc("create_time")
                .limit(2)
                .nextToken(page1.getNextToken())
                .build());
        assertThat(page2.getRows()).hasSize(2);
        assertThat(page2.getRows().get(0).getLong("create_time")).isEqualTo(3000L);
        assertThat(page2.isHasNext()).isFalse();

        // 倒序
        SearchResult desc = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .term("doc_code", TsColumnValue.of("sales_order"))
                .sortDesc("create_time")
                .limit(1)
                .build());
        assertThat(desc.getRows().get(0).getLong("create_time")).isEqualTo(5000L);
    }

    @Test
    @DisplayName("未请求 totalCount 时返回 -1；空条件 MatchAll 语义")
    void totalCount_notRequested_and_matchAll() {
        SearchResult all = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder().build());
        assertThat(all.getRows()).hasSize(5);
        assertThat(all.getTotalCount()).isEqualTo(SearchResult.TOTAL_COUNT_NOT_REQUESTED);
    }

    @Test
    @DisplayName("查询约束：limit 越界 / wildcard 超 32 字符 / terms 超 1024 词 构造即拒")
    void query_constraints() {
        assertThatThrownBy(() -> QuerySpec.builder().limit(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuerySpec.builder().limit(101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuerySpec.builder().wildcard("f", "x".repeat(33)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
        assertThatThrownBy(() -> QuerySpec.builder()
                .nestedWildcard("attributes", "f", "*" + "x".repeat(33) + "*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
        List<TsColumnValue> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 1025; i++) {
            tooMany.add(TsColumnValue.of((long) i));
        }
        assertThatThrownBy(() -> QuerySpec.builder().terms("f", tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1024");
    }
}
