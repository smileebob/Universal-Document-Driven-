package com.example.runtime.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.common.exception.ErrorCode;
import com.example.runtime.domain.model.AttributeDef;
import com.example.runtime.domain.model.EntitySchema;
import com.example.runtime.domain.model.FieldError;
import com.example.runtime.domain.model.UniqueValues;
import com.example.runtime.domain.port.UniqueIndexStore;
import com.example.runtime.domain.validation.DataValidationChain;

/**
 * 唯一约束编排服务（校验链第④步，design/03 §五强一致占位）。
 *
 * <p>流程（任务书步骤 5-9 + leader 裁决）：</p>
 * <ul>
 *   <li>新增：先占全部 unique 值（EXPECT_NOT_EXIST CAS）→ 写主行；主行失败回滚占位；</li>
 *   <li>修改：先占新值 → 改主行（version CAS）→ 成功后释放旧值；CAS 失败回滚新值占位；</li>
 *   <li>逻辑删：唯一行<b>保留不释放</b>（design/03 §5.6，design 为唯一权威）。</li>
 * </ul>
 */
@Service
public class UniqueConstraintService {

    private final UniqueIndexStore uniqueIndexStore;

    public UniqueConstraintService(UniqueIndexStore uniqueIndexStore) {
        this.uniqueIndexStore = uniqueIndexStore;
    }

    /** 已占位的唯一值（回滚/释放用） */
    public record Reserved(String attrCode, String value) {
    }

    /**
     * 新增场景：占用全部 unique 属性值（任一冲突回滚已占位并抛 40005）。
     *
     * @param schema    实体定义
     * @param attributes 目标属性值
     * @param ownerUuid  新数据 uuid
     * @return 已占位列表（主行写入失败时整体回滚）
     */
    public List<Reserved> reserveForCreate(EntitySchema schema, Map<String, Object> attributes,
                                           String ownerUuid) {
        List<Reserved> reserved = new ArrayList<>();
        for (AttributeDef def : schema.uniqueAttributes()) {
            Object value = attributes.get(def.getCode());
            if (value == null) {
                continue;
            }
            String normalized = UniqueValues.normalize(value);
            if (!uniqueIndexStore.tryReserve(schema.getCode(), def.getCode(), normalized, ownerUuid)) {
                releaseAll(schema.getCode(), reserved, ownerUuid);
                throw conflict(def);
            }
            reserved.add(new Reserved(def.getCode(), normalized));
        }
        return reserved;
    }

    /**
     * 修改场景：仅对取值变化的 unique 属性占用新值（冲突回滚并抛 40005）。
     *
     * @param schema       实体定义
     * @param oldAttributes 存量属性值
     * @param newAttributes 合并后属性值
     * @param ownerUuid    数据 uuid
     * @return 已占位的新值列表（version CAS 失败时整体回滚）
     */
    public List<Reserved> reserveChanged(EntitySchema schema, Map<String, Object> oldAttributes,
                                         Map<String, Object> newAttributes, String ownerUuid) {
        List<Reserved> reserved = new ArrayList<>();
        for (AttributeDef def : schema.uniqueAttributes()) {
            Object newValue = newAttributes.get(def.getCode());
            // 值变化判定一律归一化串比较（评审修复 Major-5：Integer 1 vs Double 1.0 不算换值）
            if (newValue == null || sameNormalized(newValue, oldAttributes.get(def.getCode()))) {
                continue;
            }
            String normalized = UniqueValues.normalize(newValue);
            if (!uniqueIndexStore.tryReserve(schema.getCode(), def.getCode(), normalized, ownerUuid)) {
                releaseAll(schema.getCode(), reserved, ownerUuid);
                throw conflict(def);
            }
            reserved.add(new Reserved(def.getCode(), normalized));
        }
        return reserved;
    }

    /**
     * 修改成功后释放取值已变化的旧 unique 值（换值流程第三步）。
     *
     * @param schema       实体定义
     * @param oldAttributes 存量属性值
     * @param newAttributes 合并后属性值
     */
    public void releaseChangedOldValues(EntitySchema schema, Map<String, Object> oldAttributes,
                                        Map<String, Object> newAttributes, String ownerUuid) {
        for (AttributeDef def : schema.uniqueAttributes()) {
            Object oldValue = oldAttributes.get(def.getCode());
            // 同 reserveChanged：归一化串比较，避免数值同值异构误判为换值（评审修复 Major-5）
            if (oldValue == null || sameNormalized(oldValue, newAttributes.get(def.getCode()))) {
                continue;
            }
            uniqueIndexStore.release(schema.getCode(), def.getCode(),
                    UniqueValues.normalize(oldValue), ownerUuid);
        }
    }

    /**
     * 归一化串等值判定（评审修复 Major-5）：两侧均经 {@link UniqueValues#normalize(Object)}
     * 归一后比较，数值同值异构（Integer 1 vs Double 1.0）视为未变化；
     * 任一侧为 null 视为不相等（调用方已先行判空一侧）。
     *
     * @param left  左值（非 null）
     * @param right 右值（可 null）
     * @return true=归一后相等
     */
    private static boolean sameNormalized(Object left, Object right) {
        if (right == null) {
            return false;
        }
        return UniqueValues.normalize(left).equals(UniqueValues.normalize(right));
    }

    /**
     * 回滚占位（主行写入/CAS 失败时调用）。
     *
     * @param targetCode 实体编码
     * @param reserved   已占位列表
     * @param ownerUuid  占位属主（条件释放，防止误删易主后的新占位）
     */
    public void releaseAll(String targetCode, List<Reserved> reserved, String ownerUuid) {
        for (Reserved item : reserved) {
            uniqueIndexStore.release(targetCode, item.attrCode(), item.value(), ownerUuid);
        }
    }

    /** 唯一冲突 → 40005（field_errors 明细） */
    private RuntimeException conflict(AttributeDef def) {
        return DataValidationChain.toException(ErrorCode.UNIQUE_CONFLICT, List.of(
                new FieldError(def.getCode(), def.getName(),
                        "「" + def.getName() + "」的值已存在")));
    }
}
