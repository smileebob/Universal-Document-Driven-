package com.example.meta.service.doc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.example.common.exception.BizException;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.meta.api.dto.StateMachineDTO;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.doc.StateMachineConfig;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.web.dto.StateMachineRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StateMachineService SM-01/02 业务测试（全 Mockito：保存即生效 + 预校验分支）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StateMachineServiceTest {

    @Mock
    private StateMachineConfig.Repository stateMachineRepository;
    @Mock
    private DocSchemaDefinition.Repository docSchemaRepository;
    @Mock
    private MenuConfigRepository menuConfigRepository;
    @Mock
    private CacheService cacheService;
    @Mock
    private SchemaInvalidationPublisher publisher;
    @Mock
    private ConfigAuditService configAuditService;

    private StateMachineService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new StateMachineService(stateMachineRepository, docSchemaRepository,
                menuConfigRepository, cacheService, publisher, objectMapper, configAuditService);
        when(menuConfigRepository.incrementSchemaVersion()).thenReturn(1L);
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(new DocSchemaDefinition()));
    }

    private Map<String, Object> state(long status, String name, boolean terminal) {
        Map<String, Object> state = new HashMap<>();
        state.put("status", status);
        state.put("name", name);
        state.put("editable", status == 100L);
        state.put("terminal", terminal);
        return state;
    }

    private Map<String, Object> transition(long start, List<Long> end) {
        Map<String, Object> transition = new HashMap<>();
        transition.put("start", start);
        transition.put("end", end);
        return transition;
    }

    private StateMachineRequest validRequest() {
        return new StateMachineRequest(
                List.of(state(100L, "暂存", false), state(10L, "已提交", false),
                        state(99L, "已关闭", true)),
                List.of(transition(100L, List.of(10L)), transition(10L, List.of(99L))));
    }

    // ==================== SM-01 保存 ====================

    @Test
    @DisplayName("保存：新建 insert + 即时失效链 + 审计，返回 doc_code/version")
    void save_new_ok() {
        when(stateMachineRepository.findByDocCode("po")).thenReturn(Optional.empty());

        Map<String, Object> result = service.save("po", validRequest());

        assertThat(result.get("doc_code")).isEqualTo("po");
        assertThat(result.get("version")).isEqualTo(1L);
        assertThat((List<?>) result.get("warnings")).isEmpty();
        verify(stateMachineRepository).insert(any(StateMachineConfig.class));
        verify(cacheService).evict(CacheKeyBuilder.stateMachine("po"));
        verify(publisher).publish(any(SchemaInvalidationEvent.class));
        verify(configAuditService).recordConfig(eq("state_machine"), eq("po"), eq("save"), any(), any());
    }

    @Test
    @DisplayName("保存：已存在配置走 updateWithVersion，version 递增")
    void save_update_ok() {
        StateMachineConfig existing = new StateMachineConfig();
        existing.setDocCode("po");
        existing.setVersion(3L);
        when(stateMachineRepository.findByDocCode("po")).thenReturn(Optional.of(existing));

        Map<String, Object> result = service.save("po", validRequest());
        assertThat(result.get("version")).isEqualTo(4L);
        verify(stateMachineRepository).updateWithVersion(existing);
        verify(stateMachineRepository, never()).insert(any());
    }

    @Test
    @DisplayName("保存：单据定义不存在抛 40401")
    void save_docNotFound_40401() {
        when(docSchemaRepository.findByCode("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save("ghost", validRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("预校验：states 非法分支汇总 40011（缺失/重复/缺名/缺 100）")
    void save_invalid_states_40011() {
        List<Map<String, Object>> states = new ArrayList<>();
        Map<String, Object> noStatus = new HashMap<>();
        noStatus.put("name", "无状态值");
        states.add(noStatus);
        states.add(state(10L, "已提交", false));
        states.add(state(10L, "重复", false));
        Map<String, Object> noName = new HashMap<>();
        noName.put("status", 20L);
        states.add(noName);

        assertThatThrownBy(() -> service.save("po",
                new StateMachineRequest(states, List.of())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo(40011);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) biz.getData();
                    @SuppressWarnings("unchecked")
                    List<String> reasons = (List<String>) data.get("reasons");
                    assertThat(reasons)
                            .anyMatch(r -> r.contains("缺失 status"))
                            .anyMatch(r -> r.contains("状态重复"))
                            .anyMatch(r -> r.contains("缺失名称"))
                            .anyMatch(r -> r.contains("必须包含"));
                });
        verify(stateMachineRepository, never()).insert(any());
    }

    @Test
    @DisplayName("预校验：transitions 非法分支汇总 40011（起止未定义/99 起始/终态出边/缺 end）")
    void save_invalid_transitions_40011() {
        List<Map<String, Object>> states = List.of(
                state(100L, "暂存", false), state(30L, "已生效", true), state(99L, "已关闭", true));
        List<Map<String, Object>> transitions = new ArrayList<>();
        transitions.add(transition(55L, List.of(100L)));
        transitions.add(transition(99L, List.of(100L)));
        transitions.add(transition(30L, List.of(100L)));
        Map<String, Object> noEnd = new HashMap<>();
        noEnd.put("start", 100L);
        transitions.add(noEnd);
        Map<String, Object> badEnd = new HashMap<>();
        badEnd.put("start", 100L);
        badEnd.put("end", List.of("not_number", 77L));
        transitions.add(badEnd);
        Map<String, Object> noStart = new HashMap<>();
        noStart.put("end", List.of(100L));
        transitions.add(noStart);

        assertThatThrownBy(() -> service.save("po",
                new StateMachineRequest(states, transitions)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo(40011);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) biz.getData();
                    @SuppressWarnings("unchecked")
                    List<String> reasons = (List<String>) data.get("reasons");
                    assertThat(reasons)
                            .anyMatch(r -> r.contains("起始状态未定义"))
                            .anyMatch(r -> r.contains("不可作为流转起始"))
                            .anyMatch(r -> r.contains("终态无出边"))
                            .anyMatch(r -> r.contains("缺失目标状态 end"))
                            .anyMatch(r -> r.contains("非法状态值"))
                            .anyMatch(r -> r.contains("目标状态未定义"))
                            .anyMatch(r -> r.contains("缺失 start"));
                });
    }

    @Test
    @DisplayName("预校验：不可达状态仅告警（warnings），保存成功")
    void save_warnings_unreachable() {
        StateMachineRequest request = new StateMachineRequest(
                List.of(state(100L, "暂存", false), state(10L, "已提交", false),
                        state(20L, "已审核", false)),
                List.of(transition(100L, List.of(10L))));
        when(stateMachineRepository.findByDocCode("po")).thenReturn(Optional.empty());

        Map<String, Object> result = service.save("po", request);
        assertThat((List<?>) result.get("warnings"))
                .anyMatch(w -> String.valueOf(w).contains("20"));
        verify(stateMachineRepository).insert(any());
    }

    @Test
    @DisplayName("保存：并发 CAS 冲突抛 40901")
    void save_conflict_40901() {
        when(stateMachineRepository.findByDocCode("po")).thenReturn(Optional.empty());
        doThrow(new TsConditionCheckException("condition check fail"))
                .when(stateMachineRepository).insert(any());
        assertThatThrownBy(() -> service.save("po", validRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
    }

    // ==================== 查询/运行侧契约 ====================

    @Test
    @DisplayName("get：命中返回；未配置 40401")
    void get() {
        StateMachineConfig config = new StateMachineConfig();
        config.setDocCode("po");
        when(stateMachineRepository.findByDocCode("po")).thenReturn(Optional.of(config));
        assertThat(service.get("po").getDocCode()).isEqualTo("po");
        when(stateMachineRepository.findByDocCode("none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("none"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("getPublishedDto：缓存命中/空值占位/回源回填/未配置 40401")
    void getPublishedDto_paths() throws Exception {
        StateMachineConfig config = new StateMachineConfig();
        config.setDocCode("po");
        config.setVersion(2L);
        StateMachineDTO dto = DocDtoMapper.toDto(config);

        when(cacheService.get(CacheKeyBuilder.stateMachine("po")))
                .thenReturn(objectMapper.writeValueAsString(dto));
        assertThat(service.getPublishedDto("po").getDocCode()).isEqualTo("po");

        when(cacheService.get(CacheKeyBuilder.stateMachine("null")))
                .thenReturn(CacheService.NULL_PLACEHOLDER);
        assertThatThrownBy(() -> service.getPublishedDto("null"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);

        when(cacheService.get(CacheKeyBuilder.stateMachine("po2"))).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn("token");
        when(stateMachineRepository.findByDocCode("po2")).thenReturn(Optional.of(config));
        assertThat(service.getPublishedDto("po2").getDocCode()).isEqualTo("po");
        verify(cacheService).putIfNotEvicted(anyString(), anyString(), anyLong());
        verify(cacheService).releaseRebuildLock(anyString(), anyString());

        when(cacheService.get(CacheKeyBuilder.stateMachine("ghost"))).thenReturn(null);
        when(stateMachineRepository.findByDocCode("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPublishedDto("ghost"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
        verify(cacheService).putNull(CacheKeyBuilder.stateMachine("ghost"));
    }
}
