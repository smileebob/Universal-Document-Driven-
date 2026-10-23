package com.example.runtime.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.exception.BizException;
import com.example.runtime.domain.model.AttributeDef;
import com.example.runtime.domain.model.AttributeType;
import com.example.runtime.domain.model.EntitySchema;
import com.example.runtime.domain.port.UniqueIndexStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 唯一约束换值判定回归（评审修复 Major-5）。
 *
 * <p>值变化与旧值释放一律走 {@link com.example.runtime.domain.model.UniqueValues}
 * 归一化串比较：NUMBER 属性 Integer 1 与 Double 1.0 同值异构原样提交，
 * 不得误判为换值而报假 40005。</p>
 */
class UniqueConstraintServiceTest {

    /** 记录型占位存储：记录 reserve/release 调用供断言 */
    private static class RecordingStore implements UniqueIndexStore {

        private final List<String> reserveCalls = new ArrayList<>();
        private final List<String> releaseCalls = new ArrayList<>();
        private boolean rejectNextReserve;

        @Override
        public boolean tryReserve(String targetCode, String attrCode, String value, String ownerUuid) {
            if (rejectNextReserve) {
                return false;
            }
            reserveCalls.add(targetCode + "/" + attrCode + "/" + value);
            return true;
        }

        @Override
        public boolean release(String targetCode, String attrCode, String value, String expectedOwner) {
            releaseCalls.add(targetCode + "/" + attrCode + "/" + value + "/" + expectedOwner);
            return true;
        }

        @Override
        public java.util.Optional<String> findOwner(String targetCode, String attrCode, String value) {
            return java.util.Optional.empty();
        }
    }

    private RecordingStore store;
    private UniqueConstraintService service;
    private EntitySchema schema;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        service = new UniqueConstraintService(store);
        AttributeDef level = new AttributeDef("level", "等级", AttributeType.NUMBER,
                false, true, null, 0, null, true, null, 1);
        schema = new EntitySchema("customer", "客户", EntitySchema.STATUS_ACTIVE, List.of(level));
    }

    @Test
    @DisplayName("NUMBER 同值异构（Integer 1 → Double 1.0）：不判为换值，不占新位、不抛 40005")
    void numericSameValueDifferentType_notTreatedAsChange() {
        Map<String, Object> oldAttributes = Map.of("level", 1);
        Map<String, Object> newAttributes = Map.of("level", 1.0d);

        List<UniqueConstraintService.Reserved> reserved =
                service.reserveChanged(schema, oldAttributes, newAttributes, "uuid-1");

        assertThat(reserved).isEmpty();
        assertThat(store.reserveCalls).isEmpty();

        service.releaseChangedOldValues(schema, oldAttributes, newAttributes, "uuid-1");
        assertThat(store.releaseCalls).isEmpty();
    }

    @Test
    @DisplayName("NUMBER 去尾零同值（80000.00 → 80000）：同样不判为换值")
    void numericTrailingZeros_notTreatedAsChange() {
        Map<String, Object> oldAttributes = Map.of("level", 80000L);
        Map<String, Object> newAttributes = Map.of("level", 80000.00d);

        assertThat(service.reserveChanged(schema, oldAttributes, newAttributes, "uuid-1")).isEmpty();
        service.releaseChangedOldValues(schema, oldAttributes, newAttributes, "uuid-1");
        assertThat(store.releaseCalls).isEmpty();
    }

    @Test
    @DisplayName("真实换值（1 → 2）：占归一化新值、成功后释放归一化旧值")
    void realChange_reservesNormalizedNewAndReleasesOld() {
        Map<String, Object> oldAttributes = Map.of("level", 1);
        Map<String, Object> newAttributes = Map.of("level", 2.0d);

        List<UniqueConstraintService.Reserved> reserved =
                service.reserveChanged(schema, oldAttributes, newAttributes, "uuid-1");
        assertThat(reserved).hasSize(1);
        assertThat(store.reserveCalls).containsExactly("customer/level/2");

        service.releaseChangedOldValues(schema, oldAttributes, newAttributes, "uuid-1");
        assertThat(store.releaseCalls).containsExactly("customer/level/1/uuid-1");
    }

    @Test
    @DisplayName("真实换值冲突：仍报 40005 并回滚已占位")
    void realChange_conflict_throws40005() {
        store.rejectNextReserve = true;
        Map<String, Object> oldAttributes = Map.of("level", 1);
        Map<String, Object> newAttributes = Map.of("level", 2);

        assertThatThrownBy(() -> service.reserveChanged(schema, oldAttributes, newAttributes, "uuid-1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(40005);
    }
}
