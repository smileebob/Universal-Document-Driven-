package com.example.common.infrastructure.tablestore;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.example.common.infrastructure.tablestore.model.BatchWriteResult;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowChange;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;

/**
 * TablestoreTemplate 统一门面（防腐层唯一入口，F-06）。
 *
 * <p>业务层（controller/service/domain）<b>禁止直接 import SDK</b>
 * （{@code com.alicloud.openservices.tablestore.*}），全部 Tablestore 访问经本门面收口，
 * ArchUnit 规则强制守护（design/08 §2.7）。实现有两个：</p>
 * <ul>
 *   <li>{@code OtsTablestoreTemplate}：真实 SyncClient 适配（生产/dev/test 云实例）；</li>
 *   <li>{@code InMemoryTablestore}：内存仿真层（doc-common test-jar，唯一存储测试手段）。</li>
 * </ul>
 */
public interface TablestoreTemplate {

    // ==================== 点查 ====================

    /**
     * 主键点查（GetRow，写后读强一致路径，TS-03）。
     *
     * @param table 表名
     * @param pk    完整主键
     * @return 命中行（未命中返回 empty）
     */
    Optional<TsRow> getRow(String table, TsPrimaryKey pk);

    /**
     * 批量点查（BatchGetRow，单批 ≤ 100 自动分段，TS-03）。
     *
     * @param table        表名
     * @param primaryKeys  主键集合
     * @return 命中行列表（不含未命中，顺序不保证）
     */
    List<TsRow> batchGet(String table, List<TsPrimaryKey> primaryKeys);

    // ==================== 写入 ====================

    /**
     * 整行写入（PutRow，IGNORE 条件）。
     *
     * @param table 表名
     * @param row   数据行
     */
    void putRow(String table, TsRow row);

    /**
     * 条件整行写入（PutRow + Condition，唯一行 CAS / 幂等建行的基础，TS-01）。
     *
     * @param table     表名
     * @param row       数据行
     * @param condition 行条件
     */
    void putRow(String table, TsRow row, RowCondition condition);

    /**
     * 幂等建行快捷方法（EXPECT_NOT_EXIST）。
     *
     * @param table 表名
     * @param row   数据行
     */
    default void putRowIfAbsent(String table, TsRow row) {
        putRow(table, row, RowCondition.expectNotExist());
    }

    /**
     * 局部列更新（UpdateRow，IGNORE 条件）。
     *
     * @param table   表名
     * @param pk      主键
     * @param updates 列变更
     */
    void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates);

    /**
     * 条件局部列更新（UpdateRow + Condition，乐观锁与状态 CAS 统一入口，TS-01/TS-04）。
     *
     * @param table     表名
     * @param pk        主键
     * @param updates   列变更
     * @param condition 行条件
     */
    void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates, RowCondition condition);

    /**
     * version 乐观锁条件更新（TS-04 替代 @Version）：
     * 条件 {@code EXPECT_EXIST && version == expectVersion}，成功后 {@code version + 1}。
     *
     * @param table         表名
     * @param pk            主键
     * @param expectVersion 读取时的 version 值
     * @param updates       其余列变更（version 列自动追加）
     */
    default void updateWithVersion(String table, TsPrimaryKey pk, long expectVersion,
                                   List<ColumnUpdate> updates) {
        List<ColumnUpdate> all = new java.util.ArrayList<>(updates);
        all.add(ColumnUpdate.put("version", TsColumnValue.of(expectVersion + 1)));
        updateRow(table, pk, all,
                RowCondition.expectExistAndColumnEq("version", TsColumnValue.of(expectVersion)));
    }

    /**
     * 删除行（DeleteRow，IGNORE 条件）。
     *
     * @param table 表名
     * @param pk    主键
     */
    default void deleteRow(String table, TsPrimaryKey pk) {
        deleteRow(table, pk, RowCondition.ignore());
    }

    /**
     * 条件删除行（DeleteRow + RowCondition，评审修复 Major-6：TOCTOU 防御）。
     *
     * <p>条件校验失败（行不存在 / 列条件不满足）抛
     * {@code TsConditionCheckException}，由上层映射业务码（如 40901）。</p>
     *
     * @param table     表名
     * @param pk        主键
     * @param condition 行条件（EXPECT_EXIST / 列等值）
     */
    void deleteRow(String table, TsPrimaryKey pk, RowCondition condition);

    /**
     * 批量写（BatchWriteRow，单批 ≤ 200 自动分段、按表分组、逐行检查行级失败，TS-02）。
     *
     * @param changes 行变更列表
     * @return 逐行结果（非原子操作，失败行需调用方幂等重试）
     */
    BatchWriteResult batchWrite(List<RowChange> changes);

    // ==================== 局部事务 ====================

    /**
     * 局部事务（StartLocalTransaction / Commit / Abort，TS-04）。
     *
     * <p><b>仅收口同分区键的最终写入</b>：事务内操作对象的主键分区键值必须与
     * {@code partitionKey} 一致，跨分区操作抛
     * {@code TsCrossPartitionException}。内部禁远程调用（H-02 变通）；
     * {@code OTSRowOperationConflict} 由实现按 50/150/450ms 退避重试 3 次，
     * 仍失败抛 {@code TsOperationConflictException}（design/03 §6.3）。</p>
     *
     * @param anchorTable 锚定表（SDK 开启事务的表名，须与事务内写表同实例；
     *                    同分区设计下可取主行表如 doc_data）
     * @param partitionKey 事务分区键（所有操作必须落该分区）
     * @param action       事务体（抛出异常即回滚）
     * @param <T>          返回类型
     * @return 事务体返回值
     */
    <T> T executeInLocalTransaction(String anchorTable, String partitionKey,
                                    Function<LocalTransactionOps, T> action);

    // ==================== 范围扫描 ====================

    /**
     * 全表范围扫描（GetRange，INF_MIN → INF_MAX 自动续翻）。
     *
     * <p>仅限<b>配置表单键模式</b>场景（entity_schema / menu_config 等，行数百级，
     * design/03 §3.1）：启动预热、菜单全量聚合、定义导出。数据表（百万级以上）
     * 禁止全表扫描，一律走多元索引 {@link #search}（TS-05）。</p>
     *
     * @param table 表名
     * @return 全表行（按主键字典序；空表返回空列表）
     */
    List<TsRow> getRange(String table);

    /**
     * 分区级范围扫描（GetRange：第一主键列固定分区键值，其余列 INF_MIN → INF_MAX，自动续翻）。
     *
     * <p>适用于同分区伴生表的小行数量分区读取场景（如 doc_data_history 状态历史，
     * RD-08 时间线）；大分区数据禁止使用本方法，一律走多元索引 {@link #search}（TS-05）。</p>
     *
     * @param table        表名（须注册元数据，用于构造主键边界）
     * @param partitionKey 分区键值（第一主键列取值）
     * @return 分区内行（主键字典序升序；空分区返回空列表）
     */
    List<TsRow> getPartitionRange(String table, String partitionKey);

    // ==================== 检索 ====================

    /**
     * 多元索引检索（SearchQuery；QuerySpec 结构化翻译，TS-01/TS-05）。
     *
     * <p>写后读详情走主键 {@link #getRow}，禁止依赖多元索引做强一致判断（TS-03）。</p>
     *
     * @param table     表名
     * @param indexName 多元索引名
     * @param spec      结构化查询规格
     * @return 检索结果（行 + next_token + totalCount）
     */
    SearchResult search(String table, String indexName, QuerySpec spec);

    // ==================== 表管理（TS-06） ====================

    /**
     * 建表（存在即跳过，幂等）。供 TablestoreBootstrap / 流水线初始化使用。
     *
     * @param definition 表定义
     */
    void ensureTable(TableDefinition definition);

    /**
     * 建多元索引（存在即跳过，幂等）。
     *
     * @param tableName    表名
     * @param indexDefinition 索引定义
     */
    void ensureSearchIndex(String tableName, SearchIndexDef indexDefinition);

    /**
     * 多元索引字段对账注册（幂等，design/03 §4.3 动态子字段落地）。
     *
     * <p>语义：索引不存在 → 直接创建（含全部子字段）；索引存在且字段定义已覆盖
     * 期望定义 → 跳过；索引存在但缺失字段（典型：Nested 容器子字段未注册）→
     * 删除后按期望定义重建。OTS UpdateSearchIndex 不支持增量追加字段，
     * 重建为唯一落地路径（权衡与生产切换建议见实现类注释）。</p>
     *
     * @param tableName       表名
     * @param indexDefinition 期望的完整索引定义（基线列 + Nested 子字段）
     */
    void reconcileSearchIndex(String tableName, SearchIndexDef indexDefinition);

    /** 查询多元索引状态（不存在返回 exists=false）。 */
    com.example.common.infrastructure.tablestore.metadata.SearchIndexState describeSearchIndex(
            String tableName, String indexName);

    /** 既有索引字段是否覆盖期望定义（不存在返回 false）。 */
    boolean isSearchIndexCovered(String tableName, SearchIndexDef indexDefinition);

    /** 创建异名候选索引（存在即幂等跳过）。 */
    void createSearchIndexIfAbsent(String tableName, SearchIndexDef indexDefinition);

    /**
     * 创建逻辑索引的“兄弟索引”（OTS CreateSearchIndex.sourceIndexName）。
     * 只有兄弟索引才可被 UpdateSearchIndex.switchIndexName 切流。
     */
    void createBrotherSearchIndex(String tableName, String sourceIndex,
                                  SearchIndexDef candidateDefinition);

    /**
     * 在线切流：把 logicalIndex 的查询路由切换到 candidateIndex
     * （OTS UpdateSearchIndex.switchIndexName）。旧物理索引作为兄弟索引保留。
     */
    void switchSearchIndex(String tableName, String logicalIndex, String candidateIndex);

    /** 删除多元索引（清理回滚窗口后的旧索引；不存在即幂等）。 */
    void deleteSearchIndexIfExists(String tableName, String indexName);
}
