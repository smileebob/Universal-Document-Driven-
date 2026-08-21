package com.example.common.infrastructure.tablestore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 评审修复 Major-4：offset 分页双实现对齐测试。
 *
 * <p>口径：offset 模式返回行必须与「nextToken 游标逐页累积后跳过 offset 取 limit」
 * 的语义完全一致，且 offset 模式不返回 nextToken。本测试在仿真层
 * （InMemoryTablestore）直接验证对齐性；OtsTablestoreTemplate 以 token 循环翻页
 * （searchWithOffset）实现同口径，两实现共享同一 QuerySpec 契约。</p>
 */
class OffsetAlignmentTest {

    private static final String DOC_DATA = "doc_data";
    private static final String DOC_DATA_INDEX = "doc_data_index";

    private InMemoryTablestore template;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        // 7 行确定性数据（create_time 升序作为稳定排序键）
        for (int i = 1; i <= 7; i++) {
            seed(100L * i);
        }
    }

    private void seed(long createTime) {
        String uuid = UUID.randomUUID().toString();
        TsPrimaryKey pk = TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey("sales_order", uuid))
                .add("uuid", uuid)
                .build();
        Map<String, TsColumnValue> columns = new LinkedHashMap<>(Map.of(
                "doc_code", TsColumnValue.of("sales_order"),
                "current_status", TsColumnValue.of(100L),
                "create_time", TsColumnValue.of(createTime)));
        template.putRow(DOC_DATA, new TsRow(pk, columns));
    }

    private static List<String> uuids(List<TsRow> rows) {
        List<String> result = new ArrayList<>(rows.size());
        for (TsRow row : rows) {
            result.add(String.valueOf(row.getPrimaryKey().get("uuid")));
        }
        return result;
    }

    /** nextToken 游标逐页累积全量行（limit=3，共 7 行 → 3 页） */
    private List<TsRow> collectAllViaToken() {
        List<TsRow> all = new ArrayList<>();
        String token = null;
        do {
            SearchResult page = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                    .term("doc_code", TsColumnValue.of("sales_order"))
                    .sortAsc("create_time")
                    .limit(3)
                    .nextToken(token)
                    .build());
            all.addAll(page.getRows());
            token = page.getNextToken();
        } while (token != null && !token.isEmpty());
        return all;
    }

    @Test
    @DisplayName("offset=2/limit=3 与 token 迭代跳 2 取 3 完全一致，且不返回 nextToken")
    void offset_alignsWithTokenIteration() {
        List<TsRow> expected = collectAllViaToken();
        assertThat(expected).hasSize(7);

        SearchResult actual = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .term("doc_code", TsColumnValue.of("sales_order"))
                .sortAsc("create_time")
                .limit(3)
                .offset(2)
                .build());

        assertThat(uuids(actual.getRows()))
                .isEqualTo(uuids(expected.subList(2, 5)));
        // offset 模式不暴露续翻游标（防非法 token 回流 SDK）
        assertThat(actual.isHasNext()).isFalse();
    }

    @Test
    @DisplayName("offset 边界：offset=0 等价首页；offset 超尾返回空；offset+limit 跨页正确")
    void offset_boundaries() {
        List<TsRow> all = collectAllViaToken();

        SearchResult zero = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .sortAsc("create_time").limit(3).offset(0).build());
        assertThat(uuids(zero.getRows())).isEqualTo(uuids(all.subList(0, 3)));

        SearchResult beyond = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .sortAsc("create_time").limit(3).offset(7).build());
        assertThat(beyond.getRows()).isEmpty();

        SearchResult crossPage = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .sortAsc("create_time").limit(4).offset(5).build());
        assertThat(uuids(crossPage.getRows())).isEqualTo(uuids(all.subList(5, 7)));
    }

    @Test
    @DisplayName("offset 模式仍正确返回 totalCount")
    void offset_totalCount() {
        SearchResult result = template.search(DOC_DATA, DOC_DATA_INDEX, QuerySpec.builder()
                .sortAsc("create_time").limit(3).offset(2).getTotalCount(true).build());
        assertThat(result.getTotalCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("QuerySpec 契约：offset 与 nextToken 互斥；offset 不可为负")
    void querySpec_validation() {
        assertThatThrownBy(() -> QuerySpec.builder().offset(5).nextToken("opaque").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextToken");
        assertThatThrownBy(() -> QuerySpec.builder().offset(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }
}
