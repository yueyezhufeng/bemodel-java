package com.bemodel.cs;

import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.service.MetricService;
import com.bemodel.ontology.service.MissService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * prompt 注入门禁:零入围时 prompt 不含任何口径卡内容且收尾与改动前一致(逐字节不变的运行时锚点);
 * 有入围时【在册口径卡】段恰好插在规则 8 与「用户问题：」之间。MetricService 桩死隔离共享库。
 */
@SpringBootTest
class SemanticMetricPromptTest {

    @Autowired
    private SemanticQaService semanticQaService;
    @MockBean
    private DeepSeekClient deepSeekClient;
    @MockBean
    private MetricService metricService;
    @MockBean
    private MissService missService;

    private void stubUnanswerable() {
        when(deepSeekClient.chat(eq("CS_SEMANTIC_PLAN"), anyString(), anyString()))
                .thenReturn(Optional.of("{\"mode\":\"UNANSWERABLE\",\"reason\":\"测试桩\",\"gapType\":\"VOCABULARY\"}"));
    }

    private String capturedPlanPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).chat(eq("CS_SEMANTIC_PLAN"), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void zeroCandidatesShouldInjectNothing() {
        when(metricService.listProbed()).thenReturn(List.of());
        stubUnanswerable();
        String q = "会议室几点开门";
        semanticQaService.answer(q, true);
        String prompt = capturedPlanPrompt();
        assertFalse(prompt.contains("在册口径卡"), "零入围不得注入口径卡段");
        assertFalse(prompt.contains("规则补充"), "零入围不得注入 METRIC 规则");
        assertTrue(prompt.endsWith("\n用户问题：" + q + "\n"
                + "只输出JSON：{\"mode\":\"QUERY\",\"ds\":\"数据源编码\",\"sql\":\"SELECT ...\","
                + "\"semantics\":\"一句话说明查了什么、用了哪些概念\"}"), "收尾与改动前逐字节一致");
        assertTrue(prompt.contains("1. ds 与表名只能取"), "规则 1-8 原样保留");
    }

    @Test
    void candidatesShouldInjectCardSegmentBeforeQuestion() {
        Metric m = new Metric();
        m.setMetricCode("M_PROMPT_1");
        m.setName("9月出院人数(病案口径)");
        m.setDefinition("出院且首页已交");
        m.setDsCode("DS_HIS_INP");
        when(metricService.listProbed()).thenReturn(List.of(m));
        stubUnanswerable();
        String q = "9月出院多少人";
        semanticQaService.answer(q, true);
        String prompt = capturedPlanPrompt();
        int cards = prompt.indexOf("【在册口径卡】");
        int question = prompt.indexOf("\n用户问题：");
        assertTrue(cards > 0, "有入围必须注入口径卡段");
        assertTrue(prompt.contains("M_PROMPT_1"), "口径卡编码进段");
        assertTrue(prompt.contains("规则补充"), "METRIC 判定规则进段");
        assertTrue(cards < question, "段插在规则 8 之后、问题之前");
    }
}
