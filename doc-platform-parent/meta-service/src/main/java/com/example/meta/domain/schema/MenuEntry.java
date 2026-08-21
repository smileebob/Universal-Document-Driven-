package com.example.meta.domain.schema;

import java.io.Serializable;

/**
 * 菜单挂载条目（menu_config 表 domain 投影，EC-05）。
 *
 * <p>PK target_key = {@code {target_type}_{target_code}}（design/03 §3.10）；
 * 列：target_type / target_code / menu_name / icon / group_code /
 * sort_order / enabled(1|0) / create_time / update_time。</p>
 */
public class MenuEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 挂载目标类型：entity / doc */
    private String targetType;

    /** 挂载目标编码（实体编码 / 单据编码） */
    private String targetCode;

    /** 菜单名称 */
    private String menuName;

    /** 图标标识 */
    private String icon;

    /** 分组编码：basic_data / biz_doc / ops */
    private String groupCode;

    /** 组内排序（升序） */
    private long sortOrder;

    /** 是否挂载（停用定义时级联置 0） */
    private boolean enabled = true;

    /** 创建时间（UTC 毫秒） */
    private Long createTime;

    /** 更新时间（UTC 毫秒） */
    private Long updateTime;

    /**
     * 主键值：{target_type}_{target_code}。
     *
     * @return target_key
     */
    public String targetKey() {
        return targetType + "_" + targetCode;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetCode() {
        return targetCode;
    }

    public void setTargetCode(String targetCode) {
        this.targetCode = targetCode;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }
}
