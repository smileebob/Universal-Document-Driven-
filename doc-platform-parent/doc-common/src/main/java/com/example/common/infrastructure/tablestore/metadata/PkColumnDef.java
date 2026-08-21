package com.example.common.infrastructure.tablestore.metadata;

/**
 * 主键列定义（JSON 定义文件元素，TS-06）。
 */
public class PkColumnDef {

    /** 主键列类型 */
    public static final String TYPE_STRING = "STRING";
    public static final String TYPE_INTEGER = "INTEGER";

    private String name;
    private String type = TYPE_STRING;

    public PkColumnDef() {
    }

    public PkColumnDef(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
