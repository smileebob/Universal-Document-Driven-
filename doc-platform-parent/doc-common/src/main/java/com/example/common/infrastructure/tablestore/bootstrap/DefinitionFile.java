package com.example.common.infrastructure.tablestore.bootstrap;

import java.util.ArrayList;
import java.util.List;

import com.example.common.infrastructure.tablestore.metadata.TableDefinition;

/**
 * 单个版本定义文件的反序列化结构：{@code tablestore/V{序号}__{描述}.json}。
 */
public class DefinitionFile {

    /** 版本序号（只增不改） */
    private int version;

    /** 描述 */
    private String description;

    /** 表定义清单 */
    private List<TableDefinition> tables = new ArrayList<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<TableDefinition> getTables() {
        return tables;
    }

    public void setTables(List<TableDefinition> tables) {
        this.tables = tables == null ? new ArrayList<>() : tables;
    }
}
