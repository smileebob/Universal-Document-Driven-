package com.example.common.infrastructure.tablestore.inmemory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.example.common.infrastructure.tablestore.AbstractTablestoreTemplate;
import com.example.common.infrastructure.tablestore.LocalTransactionOps;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.exception.TsCrossPartitionException;
import com.example.common.infrastructure.tablestore.exception.TsCrossTableException;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.example.common.infrastructure.tablestore.metadata.TableMetadataRegistry;
import com.example.common.infrastructure.tablestore.model.BatchWriteResult;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowChange;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QueryCondition;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.common.infrastructure.tablestore.query.SortField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * InMemoryTablestore 仿真层（design/08 §七：无云实例时的唯一存储测试/联调手段）。
 *
 * <p>实现与 {@code OtsTablestoreTemplate} 完全相同的门面语义：</p>
 * <ul>
 *   <li>主键 CRUD、EXPECT_NOT_EXIST / EXPECT_EXIST / 列等值条件 CAS、version 条件更新
 *       （失败抛与真实 SDK 同语义的 {@link TsConditionCheckException}）；</li>
 *   <li>局部事务同分区原子语义：分区锁 + overlay 缓冲，提交原子写回、异常丢弃，
 *       跨分区操作抛 {@link TsCrossPartitionException}；</li>
 *   <li><b>表级作用域约束（与真实 OTS 对齐）</b>：事务 ID 仅可用于 anchorTable
 *       自身读写，事务内访问其它表抛 {@link TsCrossTableException}（真实 OTS
 *       返回 OTSSessionNotExist，仿真提前拦截防止掩盖跨表事务缺陷）；</li>
 *   <li>近似检索：QuerySpec 全算子内存过滤（term/terms/wildcard/prefix/range/exists/nestedTerm/nestedWildcard）
 *       + 排序 + limit/next_token 游标语义；</li>
 *   <li>全表范围扫描（GetRange 仿真：按主键编码字典序返回全表行）；</li>
 *   <li>批量分段与大行拦截复用 {@link AbstractTablestoreTemplate} 公共骨架（与生产实现同源）。</li>
 * </ul>
 *
 * <p><b>装配定位（阶段一出口修复）</b>：本类位于 doc-common 主类路径，是
 * {@code doc.tablestore.mode=simulation} 显式开关下的 TablestoreTemplate 装配实现，
 * 支撑 dev/test/sim 环境在无云实例时端到端启动（启动自动按 V*.json 建表建索引）。
 * 安全防护：simulation 模式仅在 spring profile 含 dev/test/sim 时允许装配，
 * 生产 profile 下显式拒绝并启动失败（见 TablestoreAutoConfiguration）。
 * 纯单元测试亦可直接实例化使用（线程安全）。</p>
 */
public class InMemoryTablestore extends AbstractTablestoreTemplate {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 表存储：表名 →（主键编码 → 行） */
    private final ConcurrentMap<String, ConcurrentMap<String, TsRow>> tables = new ConcurrentHashMap<>();

    /** 已建多元索引：表名 → 索引名集合 */
    private final ConcurrentMap<String, List<String>> searchIndexes = new ConcurrentHashMap<>();

    /** 分区锁：{表名}#{分区键} → 锁（局部事务独占） */
    private final ConcurrentMap<String, ReentrantLock> partitionLocks = new ConcurrentHashMap<>();

    public InMemoryTablestore() {
        this(new TableMetadataRegistry());
    }

    public InMemoryTablestore(TableMetadataRegistry metadataRegistry) {
        super(metadataRegistry);
    }

    // ==================== 点查 ====================

    @Override
    public Optional<TsRow> getRow(String table, TsPrimaryKey pk) {
        ConcurrentMap<String, TsRow> rows = tables.get(table);
        if (rows == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rows.get(pk.encode()));
    }

    @Override
    protected List<TsRow> doBatchGetChunk(String table, List<TsPrimaryKey> chunk) {
        List<TsRow> result = new ArrayList<>(chunk.size());
        for (TsPrimaryKey pk : chunk) {
            getRow(table, pk).ifPresent(result::add);
        }
        return result;
    }

    // ==================== 写入 ====================

    @Override
    protected void doPutRow(String table, TsRow row, RowCondition condition) {
        ConcurrentMap<String, TsRow> rows = tableRows(table);
        String key = row.getPrimaryKey().encode();
        synchronized (rows) {
            TsRow existing = rows.get(key);
            checkCondition(table, existing, condition);
            rows.put(key, row);
        }
    }

    @Override
    protected void doUpdateRow(String table, TsPrimaryKey pk,
                               List<ColumnUpdate> updates, RowCondition condition) {
        ConcurrentMap<String, TsRow> rows = tableRows(table);
        String key = pk.encode();
        synchronized (rows) {
            TsRow existing = rows.get(key);
            checkCondition(table, existing, condition);
            Map<String, TsColumnValue> columns = existing == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(existing.getColumns());
            for (ColumnUpdate update : updates) {
                if (update.isDelete()) {
                    columns.remove(update.getName());
                } else {
                    columns.put(update.getName(), update.getValue());
                }
            }
            rows.put(key, new TsRow(pk, columns));
        }
    }

    @Override
    public void deleteRow(String table, TsPrimaryKey pk, RowCondition condition) {
        writeGuard().validatePartitionKey(table, pk);
        // 条件删除（评审修复 Major-6）：校验与移除同锁原子，与局部事务提交同口径
        synchronized (this) {
            TsRow existing = getRow(table, pk).orElse(null);
            checkCondition(table, existing, condition);
            ConcurrentMap<String, TsRow> rows = tables.get(table);
            if (rows != null) {
                rows.remove(pk.encode());
            }
        }
    }

    @Override
    protected List<BatchWriteResult.FailedRow> doBatchWriteChunk(List<RowChange> chunk) {
        for (RowChange change : chunk) {
            switch (change.getOp()) {
                case PUT:
                    doPutRow(change.getTable(),
                            new TsRow(change.getPrimaryKey(), change.getPutColumns()),
                            change.getCondition());
                    break;
                case UPDATE:
                    doUpdateRow(change.getTable(), change.getPrimaryKey(),
                            change.getUpdates(), change.getCondition());
                    break;
                case DELETE:
                    deleteRow(change.getTable(), change.getPrimaryKey());
                    break;
                default:
                    throw new IllegalArgumentException("不支持的批量写操作: " + change.getOp());
            }
        }
        // 内存实现无行级失败（条件冲突以异常终止整批，与真实 SDK 语义差异见类注释）
        return List.of();
    }

    // ==================== 局部事务（分区锁 + overlay 原子提交） ====================

    @Override
    public <T> T executeInLocalTransaction(String anchorTable, String partitionKey,
                                           Function<LocalTransactionOps, T> action) {
        writeGuard().validatePartitionValue(anchorTable, partitionKey);
        ReentrantLock lock = partitionLocks.computeIfAbsent(
                anchorTable + "#" + partitionKey, key -> new ReentrantLock());
        lock.lock();
        try {
            InMemoryTxOps ops = new InMemoryTxOps(anchorTable, partitionKey);
            T result = action.apply(ops);
            // 提交：先校验全部条件，再原子写回（模拟单分区原子提交）
            ops.commit();
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** 事务内缓冲的单条操作 */
    private enum TxOpKind { PUT, UPDATE, DELETE }

    private static final class TxOp {
        private final TxOpKind kind;
        private final String table;
        private final TsPrimaryKey pk;
        private final Map<String, TsColumnValue> putColumns;
        private final List<ColumnUpdate> updates;
        private final RowCondition condition;

        private TxOp(TxOpKind kind, String table, TsPrimaryKey pk,
                     Map<String, TsColumnValue> putColumns, List<ColumnUpdate> updates,
                     RowCondition condition) {
            this.kind = kind;
            this.table = table;
            this.pk = pk;
            this.putColumns = putColumns;
            this.updates = updates;
            this.condition = condition;
        }
    }

    /** 事务内操作对象：overlay 可见未提交写，跨分区/跨表拒绝（表级作用域与真实 OTS 对齐） */
    private final class InMemoryTxOps implements LocalTransactionOps {

        private final String anchorTable;
        private final String partitionKey;
        private final List<TxOp> buffer = new ArrayList<>();

        private InMemoryTxOps(String anchorTable, String partitionKey) {
            this.anchorTable = anchorTable;
            this.partitionKey = partitionKey;
        }

        @Override
        public Optional<TsRow> getRow(String table, TsPrimaryKey pk) {
            requireAnchorTable(table);
            requireSamePartition(table, pk);
            // 倒序找事务内最新缓冲（模拟读自身未提交写）
            for (int i = buffer.size() - 1; i >= 0; i--) {
                TxOp op = buffer.get(i);
                if (op.table.equals(table) && op.pk.equals(pk)) {
                    if (op.kind == TxOpKind.DELETE) {
                        return Optional.empty();
                    }
                    if (op.kind == TxOpKind.PUT) {
                        return Optional.of(new TsRow(pk, op.putColumns));
                    }
                    // UPDATE：基线行 + 缓冲列合并
                    Map<String, TsColumnValue> columns = InMemoryTablestore.this
                            .getRow(table, pk)
                            .map(row -> new LinkedHashMap<>(row.getColumns()))
                            .orElseGet(LinkedHashMap::new);
                    for (ColumnUpdate update : op.updates) {
                        if (update.isDelete()) {
                            columns.remove(update.getName());
                        } else {
                            columns.put(update.getName(), update.getValue());
                        }
                    }
                    return Optional.of(new TsRow(pk, columns));
                }
            }
            return InMemoryTablestore.this.getRow(table, pk);
        }

        @Override
        public void putRow(String table, TsRow row) {
            putRow(table, row, RowCondition.ignore());
        }

        @Override
        public void putRow(String table, TsRow row, RowCondition condition) {
            requireAnchorTable(table);
            requireSamePartition(table, row.getPrimaryKey());
            writeGuard().validateWrite(table, row.getPrimaryKey(), row.getColumns());
            buffer.add(new TxOp(TxOpKind.PUT, table, row.getPrimaryKey(),
                    row.getColumns(), null, condition));
        }

        @Override
        public void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates,
                              RowCondition condition) {
            requireAnchorTable(table);
            requireSamePartition(table, pk);
            writeGuard().validatePartitionKey(table, pk);
            buffer.add(new TxOp(TxOpKind.UPDATE, table, pk, null, updates, condition));
        }

        @Override
        public void deleteRow(String table, TsPrimaryKey pk) {
            requireAnchorTable(table);
            requireSamePartition(table, pk);
            writeGuard().validatePartitionKey(table, pk);
            buffer.add(new TxOp(TxOpKind.DELETE, table, pk, null, null, RowCondition.ignore()));
        }

        /** 原子提交：条件校验 + 写回；任一条件失败全部不生效（事务原子性） */
        void commit() {
            synchronized (InMemoryTablestore.this) {
                for (TxOp op : buffer) {
                    TsRow existing = InMemoryTablestore.this.getRow(op.table, op.pk).orElse(null);
                    checkCondition(op.table, existing, op.condition);
                }
                for (TxOp op : buffer) {
                    ConcurrentMap<String, TsRow> rows = tableRows(op.table);
                    switch (op.kind) {
                        case PUT:
                            rows.put(op.pk.encode(), new TsRow(op.pk, op.putColumns));
                            break;
                        case UPDATE: {
                            Map<String, TsColumnValue> columns = Optional.ofNullable(rows.get(op.pk.encode()))
                                    .map(TsRow::getColumns)
                                    .map(LinkedHashMap::new)
                                    .orElseGet(LinkedHashMap::new);
                            for (ColumnUpdate update : op.updates) {
                                if (update.isDelete()) {
                                    columns.remove(update.getName());
                                } else {
                                    columns.put(update.getName(), update.getValue());
                                }
                            }
                            rows.put(op.pk.encode(), new TsRow(op.pk, columns));
                            break;
                        }
                        case DELETE:
                            rows.remove(op.pk.encode());
                            break;
                        default:
                            throw new IllegalStateException("未知事务操作类型: " + op.kind);
                    }
                }
            }
        }

        @Override
        public List<TsRow> getPartitionRows(String table) {
            requireAnchorTable(table);
            // 基础数据：按分区键过滤（仿真环境小数据量，全表扫描可接受）
            Map<String, TsRow> merged = new LinkedHashMap<>();
            for (TsRow row : tableRows(table).values()) {
                if (partitionKey.equals(row.getPrimaryKey().partitionValueAsString())) {
                    merged.put(row.getPrimaryKey().encode(), row);
                }
            }
            // overlay：事务内未提交写可见（与 getRow 语义一致）
            for (TxOp op : buffer) {
                if (!op.table.equals(table)) {
                    continue;
                }
                String key = op.pk.encode();
                switch (op.kind) {
                    case PUT:
                        merged.put(key, new TsRow(op.pk, op.putColumns));
                        break;
                    case DELETE:
                        merged.remove(key);
                        break;
                    case UPDATE: {
                        Map<String, TsColumnValue> columns = merged.containsKey(key)
                                ? new LinkedHashMap<>(merged.get(key).getColumns())
                                : new LinkedHashMap<>();
                        for (ColumnUpdate update : op.updates) {
                            if (update.isDelete()) {
                                columns.remove(update.getName());
                            } else {
                                columns.put(update.getName(), update.getValue());
                            }
                        }
                        merged.put(key, new TsRow(op.pk, columns));
                        break;
                    }
                    default:
                        throw new IllegalStateException("未知事务操作类型: " + op.kind);
                }
            }
            return new ArrayList<>(merged.values());
        }

        /**
         * 表级作用域校验（OTS 硬约束对齐）：局部事务仅允许操作 anchorTable，
         * 跨表读写在真实 OTS 上被拒（OTSSessionNotExist），仿真提前拦截。
         */
        private void requireAnchorTable(String table) {
            if (!anchorTable.equals(table)) {
                throw new TsCrossTableException(
                        "局部事务跨表操作被拒绝（OTS 表级作用域硬约束）: anchorTable="
                                + anchorTable + ", 实际=" + table
                                + "；跨表写请移至事务提交后幂等补写");
            }
        }

        private void requireSamePartition(String table, TsPrimaryKey pk) {
            if (!partitionKey.equals(pk.partitionValueAsString())) {
                throw new TsCrossPartitionException(
                        "局部事务跨分区操作被拒绝: table=" + table + ", 期望分区键=" + partitionKey
                                + ", 实际=" + pk.partitionValueAsString());
            }
        }
    }

    // ==================== 范围扫描（GetRange 仿真） ====================

    @Override
    public List<TsRow> getRange(String table) {
        ConcurrentMap<String, TsRow> rows = tables.get(table);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        // 按主键编码字典序返回（近似 Tablestore GetRange 主键序语义）
        List<TsRow> result = new ArrayList<>(rows.values());
        result.sort(Comparator.comparing(row -> row.getPrimaryKey().encode()));
        return result;
    }

    @Override
    public List<TsRow> getPartitionRange(String table, String partitionKey) {
        ConcurrentMap<String, TsRow> rows = tables.get(table);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        // 分区键过滤 + 主键编码字典序（近似 Tablestore 分区 GetRange 语义）
        List<TsRow> result = new ArrayList<>();
        for (TsRow row : rows.values()) {
            if (partitionKey.equals(row.getPrimaryKey().partitionValueAsString())) {
                result.add(row);
            }
        }
        result.sort(Comparator.comparing(row -> row.getPrimaryKey().encode()));
        return result;
    }

    // ==================== 检索（内存近似） ====================

    @Override
    public SearchResult search(String table, String indexName, QuerySpec spec) {
        ConcurrentMap<String, TsRow> rows = tables.get(table);
        List<TsRow> matched = new ArrayList<>();
        if (rows != null) {
            for (TsRow row : rows.values()) {
                if (matchesAll(row, spec.getMust())) {
                    matched.add(row);
                }
            }
        }
        sortRows(matched, spec.getSorts());

        // 分页语义：结构化 offset（评审修复 Major-4）叠加 Base64("offset={N}") 游标续翻；
        // offset 模式不返回 nextToken（与 OTS 实现 searchWithOffset 口径对齐）
        boolean offsetMode = spec.getOffset() > 0;
        int offset = (int) spec.getOffset() + decodeOffset(spec.getNextToken());
        int limit = spec.getLimit();
        List<TsRow> page = matched.stream().skip(offset).limit(limit).toList();
        String nextToken = !offsetMode && offset + page.size() < matched.size()
                ? Base64.getEncoder().encodeToString(
                        ("offset=" + (offset + page.size())).getBytes(StandardCharsets.UTF_8))
                : null;
        long totalCount = spec.isGetTotalCount() ? matched.size() : SearchResult.TOTAL_COUNT_NOT_REQUESTED;
        return new SearchResult(page, nextToken, totalCount);
    }

    private static int decodeOffset(String nextToken) {
        if (nextToken == null || nextToken.isEmpty()) {
            return 0;
        }
        String decoded = new String(Base64.getDecoder().decode(nextToken), StandardCharsets.UTF_8);
        if (!decoded.startsWith("offset=")) {
            throw new IllegalArgumentException("非法 nextToken: " + nextToken);
        }
        return Integer.parseInt(decoded.substring("offset=".length()));
    }

    private boolean matchesAll(TsRow row, List<QueryCondition> conditions) {
        for (QueryCondition condition : conditions) {
            if (!matches(row, condition)) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(TsRow row, QueryCondition condition) {
        switch (condition.getKind()) {
            case TERM: {
                QueryCondition.Term term = (QueryCondition.Term) condition;
                return valueEquals(resolveField(row, term.getField()), term.getValue());
            }
            case TERMS: {
                QueryCondition.Terms terms = (QueryCondition.Terms) condition;
                TsColumnValue actual = resolveField(row, terms.getField());
                return terms.getValues().stream().anyMatch(value -> valueEquals(actual, value));
            }
            case WILDCARD: {
                QueryCondition.Wildcard wildcard = (QueryCondition.Wildcard) condition;
                TsColumnValue actual = resolveField(row, wildcard.getField());
                if (actual == null || actual.getType() != TsColumnValue.Type.STRING) {
                    return false;
                }
                return wildcardToRegex(wildcard.getPattern()).matcher(actual.asString()).matches();
            }
            case PREFIX: {
                QueryCondition.Prefix prefix = (QueryCondition.Prefix) condition;
                TsColumnValue actual = resolveField(row, prefix.getField());
                return actual != null && actual.getType() == TsColumnValue.Type.STRING
                        && actual.asString().startsWith(prefix.getPrefix());
            }
            case RANGE: {
                QueryCondition.Range range = (QueryCondition.Range) condition;
                return matchesRange(resolveField(row, range.getField()), range);
            }
            case EXISTS:
                return resolveField(row, condition.getField()) != null;
            case NESTED_TERM: {
                QueryCondition.NestedTerm nested = (QueryCondition.NestedTerm) condition;
                return matchesNestedTerm(row.getColumn(nested.getPath()), nested);
            }
            case NESTED_TERMS: {
                QueryCondition.NestedTerms nested = (QueryCondition.NestedTerms) condition;
                return matchesNestedTerms(row.getColumn(nested.getPath()), nested);
            }
            case NESTED_WILDCARD: {
                QueryCondition.NestedWildcard nested = (QueryCondition.NestedWildcard) condition;
                return matchesNestedWildcard(row.getColumn(nested.getPath()), nested);
            }
            default:
                throw new IllegalArgumentException("不支持的查询条件: " + condition.getKind());
        }
    }

    /**
     * 字段取值：属性列优先，未命中回退主键列。
     *
     * <p>真实 Tablestore 多元索引自动包含主键列（TermQuery 可直接查询），
     * 仿真层需与生产行为对齐（design/03 §4.1 索引字段表不含 uuid，
     * uuid/partition_key 均为主键列，OtsQueryTranslator 透传字段名即可命中）。</p>
     *
     * @param row   行
     * @param field 字段名
     * @return 列值；属性列与主键列均不存在时返回 null
     */
    private static TsColumnValue resolveField(TsRow row, String field) {
        TsColumnValue column = row.getColumn(field);
        if (column != null) {
            return column;
        }
        Object pkValue = row.getPrimaryKey().get(field);
        if (pkValue instanceof String str) {
            return TsColumnValue.of(str);
        }
        if (pkValue instanceof Long lng) {
            return TsColumnValue.of(lng);
        }
        return null;
    }

    private static boolean valueEquals(TsColumnValue actual, TsColumnValue expected) {
        return actual != null && actual.equals(expected);
    }

    private static Pattern wildcardToRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append('.');
                    break;
                default:
                    sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }

    private static boolean matchesRange(TsColumnValue actual, QueryCondition.Range range) {
        if (actual == null) {
            return false;
        }
        Double actualNumber = actual.asNumberOrNull();
        if (actualNumber != null) {
            if (range.getFrom() != null) {
                double from = Objects.requireNonNull(range.getFrom().asNumberOrNull(),
                        "range 数值列须与数值界比较");
                if (range.isFromInclusive() ? actualNumber < from : actualNumber <= from) {
                    return false;
                }
            }
            if (range.getTo() != null) {
                double to = Objects.requireNonNull(range.getTo().asNumberOrNull(),
                        "range 数值列须与数值界比较");
                if (range.isToInclusive() ? actualNumber > to : actualNumber >= to) {
                    return false;
                }
            }
            return true;
        }
        // STRING：字典序（ISO 日期字符串字典序即时间序，design/03 §4.3）
        if (actual.getType() != TsColumnValue.Type.STRING) {
            return false;
        }
        String value = actual.asString();
        if (range.getFrom() != null) {
            int cmp = value.compareTo(range.getFrom().asString());
            if (range.isFromInclusive() ? cmp < 0 : cmp <= 0) {
                return false;
            }
        }
        if (range.getTo() != null) {
            int cmp = value.compareTo(range.getTo().asString());
            if (range.isToInclusive() ? cmp > 0 : cmp >= 0) {
                return false;
            }
        }
        return true;
    }

    /** Nested 子字段等值：JSON 列为对象或对象数组（attributes/relations），任一子对象命中即真 */
    private static boolean matchesNestedTerm(TsColumnValue container, QueryCondition.NestedTerm nested) {
        if (container == null || container.getType() != TsColumnValue.Type.STRING) {
            return false;
        }
        JsonNode root;
        try {
            root = JSON.readTree(container.asString());
        } catch (Exception e) {
            return false;
        }
        List<JsonNode> elements = root.isArray()
                ? rootElements(root) : List.of(root);
        for (JsonNode element : elements) {
            JsonNode leaf = element.get(nested.getSubField());
            if (leaf != null && jsonEquals(leaf, nested.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Nested 子字段多值属于：任一子对象的子字段值命中值列表即真（RD-09） */
    private static boolean matchesNestedTerms(TsColumnValue container, QueryCondition.NestedTerms nested) {
        if (container == null || container.getType() != TsColumnValue.Type.STRING) {
            return false;
        }
        JsonNode root;
        try {
            root = JSON.readTree(container.asString());
        } catch (Exception e) {
            return false;
        }
        List<JsonNode> elements = root.isArray() ? rootElements(root) : List.of(root);
        for (JsonNode element : elements) {
            JsonNode leaf = element.get(nested.getSubField());
            if (leaf == null) {
                continue;
            }
            for (TsColumnValue expected : nested.getValues()) {
                if (jsonEquals(leaf, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<JsonNode> rootElements(JsonNode array) {
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return elements;
    }

    /** Nested 子字段通配符：JSON 列对象/对象数组任一子对象字符串值命中即真（contains 语义） */
    private static boolean matchesNestedWildcard(TsColumnValue container,
                                                 QueryCondition.NestedWildcard nested) {
        if (container == null || container.getType() != TsColumnValue.Type.STRING) {
            return false;
        }
        JsonNode root;
        try {
            root = JSON.readTree(container.asString());
        } catch (Exception e) {
            return false;
        }
        Pattern regex = wildcardToRegex(nested.getPattern());
        List<JsonNode> elements = root.isArray() ? rootElements(root) : List.of(root);
        for (JsonNode element : elements) {
            JsonNode leaf = element.get(nested.getSubField());
            if (leaf != null && leaf.isTextual() && regex.matcher(leaf.asText()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static boolean jsonEquals(JsonNode node, TsColumnValue expected) {
        switch (expected.getType()) {
            case STRING:
                return node.isTextual() && node.asText().equals(expected.asString());
            case LONG:
                return node.isIntegralNumber() && node.asLong() == expected.asLong();
            case BOOLEAN:
                return node.isBoolean() && node.asBoolean() == expected.asBoolean();
            case DOUBLE:
                return node.isNumber() && Double.compare(node.asDouble(), expected.asDouble()) == 0;
            default:
                return false;
        }
    }

    private static void sortRows(List<TsRow> rows, List<SortField> sorts) {
        if (sorts.isEmpty()) {
            // 无排序时稳定按主键编码排序（保证分页可复现）
            rows.sort(Comparator.comparing(row -> row.getPrimaryKey().encode()));
            return;
        }
        rows.sort((a, b) -> {
            for (SortField sort : sorts) {
                int cmp = compareColumn(a.getColumn(sort.getField()), b.getColumn(sort.getField()));
                if (!sort.isAsc()) {
                    cmp = -cmp;
                }
                if (cmp != 0) {
                    return cmp;
                }
            }
            return a.getPrimaryKey().encode().compareTo(b.getPrimaryKey().encode());
        });
    }

    /** 列值比较：null 一律排最后（与升/降序无关，近似 Tablestore 行为） */
    private static int compareColumn(TsColumnValue left, TsColumnValue right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        Double leftNumber = left.asNumberOrNull();
        Double rightNumber = right.asNumberOrNull();
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber);
        }
        return String.valueOf(left.getObject()).compareTo(String.valueOf(right.getObject()));
    }

    // ==================== 表管理 ====================

    @Override
    public void ensureTable(TableDefinition definition) {
        getMetadataRegistry().register(definition);
        tables.putIfAbsent(definition.getName(), new ConcurrentHashMap<>());
    }

    @Override
    public void ensureSearchIndex(String tableName, SearchIndexDef indexDefinition) {
        searchIndexes.computeIfAbsent(tableName, key -> new ArrayList<>())
                .add(indexDefinition.getName());
    }

    /**
     * 仿真侧字段对账注册：仿真检索不校验 Nested 子字段注册，语义保持与
     * ensureSearchIndex 一致（登记索引名；重复对账先移除同名项避免重复登记）。
     */
    @Override
    public void reconcileSearchIndex(String tableName, SearchIndexDef indexDefinition) {
        List<String> indexes = searchIndexes.computeIfAbsent(tableName, key -> new ArrayList<>());
        indexes.remove(indexDefinition.getName());
        indexes.add(indexDefinition.getName());
    }

    /** 判断索引是否已登记（仿真启动建索引验证/测试辅助） */
    public boolean hasSearchIndex(String tableName, String indexName) {
        List<String> indexes = searchIndexes.get(tableName);
        return indexes != null && indexes.contains(indexName);
    }

    /** 判断表是否已建立（仿真启动建表验证/测试辅助） */
    public boolean hasTable(String tableName) {
        return tables.containsKey(tableName);
    }

    // ==================== 内部工具 ====================

    private ConcurrentMap<String, TsRow> tableRows(String table) {
        return tables.computeIfAbsent(table, key -> new ConcurrentHashMap<>());
    }

    /** 条件校验（行存在期望 + 列等值），失败抛与 SDK 同语义的 TsConditionCheckException */
    private void checkCondition(String table, TsRow existing, RowCondition condition) {
        switch (condition.getExpectation()) {
            case EXPECT_EXIST:
                if (existing == null) {
                    throw new TsConditionCheckException(
                            "条件更新失败（EXPECT_EXIST，行不存在）: table=" + table);
                }
                break;
            case EXPECT_NOT_EXIST:
                if (existing != null) {
                    throw new TsConditionCheckException(
                            "条件更新失败（EXPECT_NOT_EXIST，行已存在）: table=" + table);
                }
                break;
            default:
                break;
        }
        if (condition.hasColumnCondition()) {
            // 列缺失视为条件不满足（passIfMissing=false，version CAS 语义）
            TsColumnValue actual = existing == null ? null
                    : existing.getColumn(condition.getExpectColumnName());
            if (actual == null || !actual.equals(condition.getExpectColumnValue())) {
                throw new TsConditionCheckException(
                        "条件更新失败（列条件 " + condition.getExpectColumnName()
                                + " 不满足）: table=" + table);
            }
        }
    }
}
