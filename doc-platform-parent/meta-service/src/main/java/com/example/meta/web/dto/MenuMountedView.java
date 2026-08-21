package com.example.meta.web.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 已挂载菜单视图（GET /api/v1/menu/mounted 响应 data，EC-05）。
 *
 * <p>扁平 items 结构（契约基准 prd/11 §一 + design/10 §2.1，与前端 MenuItem 类型
 * 及 doc-web mock 口径一致）：条目按 group_code 分组序（basic_data/biz_doc/ops）
 * + 组内 sort_order 升序排列，前端按 group_code 自行组织侧边栏分组；
 * 仅包含已挂载且对应定义已发布的条目；携带全局 schema_version 版本探针
 * （design/10 §6.2 ②，prd/11 §一补充）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MenuMountedView {

    /** 全局版本探针（任一 schema/menu 变更即自增） */
    private long schemaVersion;

    /** 菜单项（分组序 + 组内 sort_order 升序） */
    private List<MenuItem> items = new ArrayList<>();

    /** 菜单项 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class MenuItem {

        /** 目标编码（实体/单据编码，前端路由参数） */
        private String code;

        /** 菜单名称 */
        private String menuName;

        /** 图标 */
        private String icon;

        /** 目标类型（entity/doc） */
        private String objType;

        /** 分组编码（basic_data/biz_doc/ops，prd/12 §1.11） */
        private String groupCode;

        /** 排序值 */
        private long sortOrder;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMenuName() {
            return menuName;
        }

        public void setMenuName(String menuName) {
            this.menuName = menuName;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String icon) {
            this.icon = icon;
        }

        public String getObjType() {
            return objType;
        }

        public void setObjType(String objType) {
            this.objType = objType;
        }

        public String getGroupCode() {
            return groupCode;
        }

        public void setGroupCode(String groupCode) {
            this.groupCode = groupCode;
        }

        public long getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(long sortOrder) {
            this.sortOrder = sortOrder;
        }
    }

    public long getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(long schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public List<MenuItem> getItems() {
        return items;
    }

    public void setItems(List<MenuItem> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }
}
