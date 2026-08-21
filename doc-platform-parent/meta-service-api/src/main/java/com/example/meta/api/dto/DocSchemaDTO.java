package com.example.meta.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 单据定义 DTO（doc_schema 表契约投影，DC-01~05）。
 *
 * <p>字段按 prd/04 DC 与 design/02 §四（单据状态约定）：</p>
 * <ul>
 *   <li>attributes：头属性列表，元素结构 {@code {code,name,data_type,required,unique,
 *       ref_entity_code,display_field,max_length,default_value,sort_order,sub_attributes}}；
 *       data_type 域为 string/integer/decimal/boolean/date/datetime/ref/table，
 *       table 型经 sub_attributes 承载明细子属性（同结构递归）；</li>
 *   <li>status_defs：五状态字典（prd/12 §四口径），元素 {@code {status,name,editable,terminal}}，
 *       外销订单示例 100 暂存/10 已提交/20 已审核/30 已下达/99 已关闭；</li>
 *   <li>status：定义状态（design/02 §四三套口径之「定义状态」：100 草稿/1 生效/0 停用），
 *       与单据实例 current_status 严格区分，不得混用。</li>
 * </ul>
 *
 * <p>attributes/status_defs 采用松散 Map 契约：单据属性类型域宽于实体
 * {@link AttributeDTO}（含 table/decimal/datetime），运行侧 MetaSchemaGateway
 * 内完成适配，不经强类型枚举外溢。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DocSchemaDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 定义唯一标识 */
    private String uuid;

    /** 单据编码（业务主键） */
    private String code;

    /** 单据名称 */
    private String name;

    /** 单据类型（业务分类，如 sales_order） */
    private String docType;

    /** 描述 */
    private String description;

    /** 定义状态：100=草稿、1=生效、0=停用（design/02 §四定义状态口径） */
    private Integer status;

    /** 定义版本（发布递增，缓存失效水位） */
    private Integer schemaVersion;

    /** 头属性定义列表（含 table 明细 sub_attributes，结构见类注释） */
    private List<Map<String, Object>> attributes;

    /** 状态字典（五状态约定，结构见类注释） */
    private List<Map<String, Object>> statusDefs;

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public List<Map<String, Object>> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<Map<String, Object>> attributes) {
        this.attributes = attributes;
    }

    public List<Map<String, Object>> getStatusDefs() {
        return statusDefs;
    }

    public void setStatusDefs(List<Map<String, Object>> statusDefs) {
        this.statusDefs = statusDefs;
    }
}
