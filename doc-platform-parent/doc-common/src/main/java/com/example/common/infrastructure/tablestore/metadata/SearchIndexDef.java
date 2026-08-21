package com.example.common.infrastructure.tablestore.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * 多元索引定义（JSON 定义文件元素）。
 */
public class SearchIndexDef {

    private String name;
    private List<IndexFieldDef> fields = new ArrayList<>();

    public SearchIndexDef() {
    }

    public SearchIndexDef(String name, List<IndexFieldDef> fields) {
        this.name = name;
        this.fields = fields == null ? new ArrayList<>() : fields;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<IndexFieldDef> getFields() {
        return fields;
    }

    public void setFields(List<IndexFieldDef> fields) {
        this.fields = fields == null ? new ArrayList<>() : fields;
    }
}
