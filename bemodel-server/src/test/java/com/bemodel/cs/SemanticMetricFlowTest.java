package com.bemodel.cs;

import com.bemodel.cs.mapper.QaTraceMapper;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.entity.ReconcileGroup;
import com.bemodel.ontology.service.MetricService;
import com.bemodel.ontology.service.ReconcileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * METRIC 问数全链(真 MetricService/ReconcileService,LLM 桩死):
 * 多卡并答+对账组关联 / LLM 失败走模板 / 探针失败不回落现场 SQL / 带限定走 MODEL_ANSWER。
 * 测试 JVM 深度桩:surefire 已钉空 deepseek.api-key,LLM 只剩这里的 @MockBean。
 */
@SpringBootTest
@TestPropertySource(properties = "bemodel.semantic.scene-ds=DS_HIS")
class SemanticMetricFlowTest {

    @Autowired
    private SemanticQaService semanticQaService;
    @Autowired
    private MetricService metricService;
    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private QaTraceMapper qaTraceMapper;
    @MockBean
    private DeepSeekClient deepSeekClient;

    private static final String Q = "METRIC_FLOW 专属问题:9月出院多少人";
    private static final String M1 = "METRIC_FLOW_M1";
    private static final String M2 = "METRIC_FLOW_M2";
    private static final String G = "METRIC_FLOW_G";

    @AfterEach
    void clean() {
        var g = reconcileService.getByCode(G);
        if (g != null) {
            reconcileService.removeById(g.getId());
        }
        deleteMetric(M1);
        deleteMetric(M2);
        qaTraceMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.cs.QaTrace>()
                .eq(com.bemodel.cs.QaTrace::getQuestion, Q));
    }

    private void seed() {
        // 注:名称带「9月」使预筛命中需连续 CJK 二元组 月出+出院(brief Step10 提示的名/定义核对项)
        save(M1, "METRIC_FLOW 9月出院人数(病案口径)", "SELECT 5");
        save(M2, "METRIC_FLOW 9月出院人数(结算口径)", "SELECT 9");
        ReconcileGroup g = new ReconcileGroup();
        g.setGroupCode(G);
        g.setName("METRIC_FLOW 对账组");
        g.setDefinition("自建自清");
        g.setOwner("测试");
        g.setMetricCodes(M1 + "," + M2);
        reconcileService.create(g);
    }

    private void save(String code, String name, String probeSql) {
        Metric m = new Metric();
        m.setMetricCode(code);
        m.setName(name);
        m.setDefinition("出院且首页已交");
        m.setOwner("测试");
        m.setDsCode("DS_HIS");
        m.setProbeSql(probeSql);
        metricService.save(m);
    }

    private void deleteMetric(String code) {
        var m = metricService.getByCode(code);
        if (m != null) {
            metricService.removeById(m.getId());
        }
    }

    private void stubMetricPlan(String... codes) {
        // 注:brief 原版逐码拼 "," 会留尾逗号成非法 JSON(计划解析失败降级 null),改 join 去尾逗
        String arr = String.join(",", java.util.Arrays.stream(codes).map(c -> "\"" + c + "\"").toList());
        when(deepSeekClient.chat(eq("CS_SEMANTIC_PLAN"), anyString(), anyString()))
                .thenReturn(Optional.of("{\"mode\":\"METRIC\",\"metrics\":[" + arr + "]}"));
    }

    @Test
    void metricModeShouldAnswerWithProbedValuesAndGroups() {
        seed();
        stubMetricPlan(M1, M2);
        when(deepSeekClient.chat(eq("CS_SEMANTIC_ANSWER"), anyString(), anyString()))
                .thenReturn(Optional.of("病案口径 5,结算口径 9,差 4,对账组在跟进。"));

        var outcome = semanticQaService.answer(Q, true);
        Map<String, Object> result = outcome.result();
        assertNotNull(result);
        List<?> cards = (List<?>) result.get("metricCards");
        assertEquals(2, cards.size());
        Map<?, ?> c1 = (Map<?, ?>) cards.get(0);
        assertEquals(5, c1.get("value"), "探针实测值进卡");
        assertEquals("病案口径 5,结算口径 9,差 4,对账组在跟进。", result.get("answer"));
        List<?> groups = (List<?>) result.get("reconcileGroups");
        assertEquals(1, groups.size(), "关联对账组摘要");
        assertEquals(G, ((Map<?, ?>) groups.get(0)).get("groupCode"));
        var trace = qaTraceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.cs.QaTrace>()
                        .eq(com.bemodel.cs.QaTrace::getQuestion, Q));
        assertNotNull(trace, "METRIC 行落 bm_qa_trace");
        assertEquals("METRIC", trace.getPlanMode());
    }

    @Test
    void llmFailureShouldFallBackToTemplate() {
        seed();
        stubMetricPlan(M1, M2);
        when(deepSeekClient.chat(eq("CS_SEMANTIC_ANSWER"), anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = semanticQaService.answer(Q, true).result();
        String answer = String.valueOf(result.get("answer"));
        assertTrue(answer.contains("按在册口径卡实测"), "模板兜底");
        assertTrue(answer.contains("5") && answer.contains("9"), "模板数字来自探针真数");
    }

    @Test
    void probeFailureShouldReportErrorWithoutSqlFallback() {
        save(M1, "METRIC_FLOW 9月出院人数(病案口径)", "SELECT 1 FROM no_such_table_xyz");
        stubMetricPlan(M1);
        when(deepSeekClient.chat(eq("CS_SEMANTIC_ANSWER"), anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = semanticQaService.answer(Q, true).result();
        List<?> cards = (List<?>) result.get("metricCards");
        Map<?, ?> c1 = (Map<?, ?>) cards.get(0);
        assertNotNull(c1.get("error"), "探针失败如实进卡");
        assertNull(c1.get("value"));
        assertTrue(String.valueOf(result.get("answer")).contains("实测失败"), "答案说明实测失败");
        assertFalse(result.containsKey("rows"), "不得回落现场 SQL(QUERY 路径键不得出现)");
    }

    @Test
    void qualifiedQuestionShouldStayOnModelAnswer() {
        when(deepSeekClient.chat(eq("CS_SEMANTIC_PLAN"), anyString(), anyString()))
                .thenReturn(Optional.of("{\"mode\":\"MODEL_ANSWER\",\"ds\":\"DS_HIS\","
                        + "\"semantics\":\"范围限定超出口径卡\",\"conclusion\":\"按病区细分的问题请走明细查询\","
                        + "\"verifySql\":\"\"}"));

        Map<String, Object> result = semanticQaService.answer("内科" + Q, true).result();
        assertNotNull(result);
        assertFalse(result.containsKey("metricCards"), "带范围限定不触发 METRIC");
    }
}
