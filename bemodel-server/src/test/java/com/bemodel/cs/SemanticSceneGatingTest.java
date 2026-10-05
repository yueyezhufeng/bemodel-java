package com.bemodel.cs;

import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 双演示库收口·默认场景(yml 试点三源):场景外数据源的 QUERY 计划拒答并回流 miss / 场景外指标不出卡。
 * 试点映射系运行时配置(dev 机已实配,默认场景段非空为常态),段空前提不可依赖——诚实线钉子移 EmptySceneHonestLineTest。
 * 共享库注意:问题带唯一前缀,miss 按 kind=QUESTION 精确清理(ClarifyFlowTest 同款)。
 */
@SpringBootTest
class SemanticSceneGatingTest {

    private static final String PREFIX = "SCENE_GATE_";

    @Autowired
    private SemanticQaService semanticQaService;
    @Autowired
    private OntologyMissMapper missMapper;
    @Autowired
    private CsService csService;
    @MockBean
    private DeepSeekClient deepSeekClient;

    @AfterEach
    void clean() {
        missMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OntologyMiss>()
                .like(OntologyMiss::getTerm, PREFIX).eq(OntologyMiss::getKind, "QUESTION"));
    }

    @Test
    void smuggledDemoDsShouldBeRejectedAsMiss() {
        when(deepSeekClient.chat(eq("CS_SEMANTIC_PLAN"), anyString(), anyString()))
                .thenReturn(Optional.of("{\"mode\":\"QUERY\",\"ds\":\"DS_HIS\","
                        + "\"sql\":\"SELECT COUNT(*) AS cnt FROM inpatient LIMIT 1\",\"semantics\":\"测试\"}"));
        SemanticQaService.Outcome outcome = semanticQaService.answer(PREFIX + "偷渡旧演示库数住院人数", true);
        assertNull(outcome.result(), "场景外数据源不得放行");
        assertTrue(outcome.recordedMiss(), "拒答应回流 miss(诚实缺口而非静默)");
    }

    @Test
    void demoCaliberCardShouldNotAttachInDefaultScene() {
        Map<String, Object> r = csService.ask(PREFIX + "检验撤销率怎么算", "ANALYTICS");
        assertEquals("口径查询", r.get("intent"), "口径试探应命中词表");
        assertNull(r.get("card"), "场景外指标(DS_LIS 检验撤销率)不得出口径卡");
        assertEquals("/glossary",
                ((Map<?, ?>) ((List<?>) r.get("links")).get(0)).get("route"), "退回统一口径页通用链接");
        assertTrue(String.valueOf(r.get("answer")).contains("检验撤销率"), "定义文本仍照常回答");
    }
}
