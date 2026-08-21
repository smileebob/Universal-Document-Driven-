package com.example.meta.infrastructure.index;

import java.util.List;

import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;

/**
 * relations Nested 容器固定子字段（两索引注册器共用，单一来源防漂移）。
 *
 * <p>子字段全集来自 runtime QueryService 实际查询引用（任务 #10 grep 实证）：</p>
 * <ul>
 *   <li>{@code code} / {@code uuid}：relations[].code / relations[].uuid 关联反查
 *       （nested_eq / nested_in，RD-09/11）；</li>
 *   <li>{@code attribute_code}：RD-09 ref 关联查询候选扫描与收敛
 *       （scanRefCandidates/applyRefDisplayFilter 的 nestedTerm(relations.attribute_code)）。</li>
 * </ul>
 */
final class RelationSubFields {

    private RelationSubFields() {
    }

    /** relations 固定子字段定义（KEYWORD 三元组） */
    static List<IndexFieldDef> definitions() {
        return List.of(
                new IndexFieldDef("code", IndexFieldDef.TYPE_KEYWORD),
                new IndexFieldDef("uuid", IndexFieldDef.TYPE_KEYWORD),
                new IndexFieldDef("attribute_code", IndexFieldDef.TYPE_KEYWORD));
    }
}
