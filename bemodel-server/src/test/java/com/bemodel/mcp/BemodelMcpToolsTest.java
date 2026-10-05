package com.bemodel.mcp;

import com.bemodel.common.BizException;
import com.bemodel.cs.CsService;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.service.MetricService;
import com.bemodel.search.SearchService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MCP 工具的领域装配逻辑:定位/渲染纯逻辑,协议装配在 McpEndpointTest 端到端覆盖 */
class BemodelMcpToolsTest {

    private final CsService csService = mock(CsService.class);
    private final SearchService searchService = mock(SearchService.class);
    private final MetricService metricService = mock(MetricService.class);
    private final BemodelMcpTools tools = new BemodelMcpTools(csService, searchService, metricService);

    private static Metric seedMetric() {
        Metric m = new Metric();
        m.setMetricCode("DISCHARGE_COUNT");
        m.setName("出院人数");
        m.setDefinition("统计周期内完成出院结算的住院就诊人次");
        m.setFormula("COUNT(DISTINCT 住院号)");
        m.setOwner("陈财务");
        return m;
    }

    @Test
    void renderAskAppendsEvidenceAndTraceId() {
        Map<String, Object> r = Map.of(
                "answer", "内科共有 12 名在院患者",
                "evidence", List.of(Map.of("label", "执行SQL", "value", "SELECT ..."),
                        Map.of("label", "结果行数", "value", 1)),
                "traceId", "T-123");
        String text = tools.renderAsk(r);
        assertTrue(text.contains("内科共有 12 名在院患者"));
        assertTrue(text.contains("证据："));
        assertTrue(text.contains("· 执行SQL：SELECT ..."));
        assertTrue(text.contains("· 结果行数：1"));
        assertTrue(text.contains("追溯编号：T-123"));
    }

    @Test
    void renderAskEmbedsMetricCardWhenPresent() {
        // 问数命中指标时 CsService.ask 返回内嵌口径卡（metric 字段）——MCP 文本面须把卡逐字段透出，
        // 缺失字段（此处无 dsCode/owner）静默跳过，不得输出字面 "null"
        Map<String, Object> r = Map.of(
                "answer", "出院人数口径如下",
                "metric", Map.of("definition", "统计周期内完成出院结算的住院就诊人次",
                        "formula", "COUNT(DISTINCT 住院号)", "probeSql", "SELECT COUNT(*) FROM fee_detail",
                        "warnThreshold", 100, "lastVal", 12));
        String text = tools.renderAsk(r);
        assertTrue(text.contains("—— 口径卡 ——"));
        assertTrue(text.contains("· 口径定义：统计周期内完成出院结算的住院就诊人次"));
        assertTrue(text.contains("· 计算公式：COUNT(DISTINCT 住院号)"));
        assertTrue(text.contains("· 探针 SQL：SELECT COUNT(*) FROM fee_detail"));
        assertTrue(text.contains("· 预警阈值：100"));
        assertTrue(text.contains("· 最近实测：12"));
        assertFalse(text.contains("数据源"), "缺失字段应整行跳过而非输出 null");
        assertFalse(text.contains("null"));
    }

    @Test
    void renderMetricCardAlarmedMarksThresholdExceeded() {
        Map<String, Object> eval = Map.of("value", 305, "alarm", true,
                "evaluatedAt", LocalDateTime.of(2026, 9, 23, 9, 30));
        String text = tools.renderMetricCard(seedMetric(), eval);
        assertTrue(text.contains("最近实测：305（已超预警阈值）"));
    }

    @Test
    void renderAskKeepsCapabilityMenuAsIs() {
        Map<String, Object> r = Map.of(
                "intent", "能力引导",
                "answer", "我目前能查证这几类问题",
                "evidence", List.of(Map.of("label", "开放查询", "value", "示例")));
        String text = tools.renderAsk(r);
        assertTrue(text.contains("我目前能查证这几类问题"));
        assertTrue(text.contains("· 开放查询：示例"));
        assertFalse(text.contains("追溯编号"));
    }

    @Test
    void renderMetricCardWithoutProbeStatesItHonestly() {
        String text = tools.renderMetricCard(seedMetric(), null);
        assertTrue(text.contains("【口径卡】出院人数"));
        assertTrue(text.contains("COUNT(DISTINCT 住院号)"));
        assertTrue(text.contains("未绑定巡检探针"));
    }

    @Test
    void renderMetricCardWithEvalShowsValueAndAlarm() {
        Map<String, Object> eval = Map.of("value", 12, "alarm", false,
                "evaluatedAt", LocalDateTime.of(2026, 9, 18, 10, 0));
        String text = tools.renderMetricCard(seedMetric(), eval);
        assertTrue(text.contains("最近实测：12（正常）"));
        assertTrue(text.contains("实测时间：2026-09-18 10:00"));
    }

    @Test
    void resolveMetricCodeByExactCode() {
        when(metricService.getByCode("DISCHARGE_COUNT")).thenReturn(seedMetric());
        assertEquals("DISCHARGE_COUNT", tools.resolveMetricCode("DISCHARGE_COUNT"));
    }

    @Test
    void resolveMetricCodeByNameViaSearch() {
        when(metricService.getByCode("出院人数")).thenReturn(null);
        when(searchService.search("出院人数", false)).thenReturn(Map.of("hits", List.of(
                Map.of("type", "术语", "title", "出院", "content", "x"),
                Map.of("type", "指标", "title", "出院人数", "name", "出院人数", "metricCode", "DISCHARGE_COUNT"))));
        assertEquals("DISCHARGE_COUNT", tools.resolveMetricCode("出院人数"));
    }

    @Test
    void resolveMetricCodeMissThrowsBiz() {
        when(metricService.getByCode("不存在的指标XYZ")).thenReturn(null);
        when(searchService.search("不存在的指标XYZ", false)).thenReturn(Map.of("hits", List.of()));
        BizException e = assertThrows(BizException.class, () -> tools.resolveMetricCode("不存在的指标XYZ"));
        assertTrue(e.getMessage().contains("没有找到"));
    }

    @Test
    void resolveMetricCodeBlankThrowsBiz() {
        assertThrows(BizException.class, () -> tools.resolveMetricCode("  "));
    }
}
