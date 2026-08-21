package com.example.meta.web.dto;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 菜单挂载配置请求（PUT /api/v1/schema/{type}/{code}/menu，EC-05）。
 *
 * <p>{@code enabled=false} 即取消挂载（DELETE 语义承载于本 PUT）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MenuMountRequest {

    /** 菜单名称 */
    @NotBlank(message = "不可为空")
    private String menuName;

    /** 图标标识 */
    private String icon;

    /** 分组编码（basic_data/biz_doc/ops） */
    private String groupCode;

    /** 组内排序（升序，缺省 0） */
    private Long sortOrder;

    /** 是否挂载（缺省 true；false=取消挂载） */
    private Boolean enabled;

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

    public String getGroupCode() {
        return groupCode;
    }

    public void setGroupCode(String groupCode) {
        this.groupCode = groupCode;
    }

    public Long getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Long sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
