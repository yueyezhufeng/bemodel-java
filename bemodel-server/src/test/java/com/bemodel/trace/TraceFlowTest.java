package com.bemodel.trace;

import com.bemodel.common.BizException;
import com.bemodel.cs.CsService;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.service.MappingService;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import com.bemodel.ontology.service.ConceptService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.bemodel.llm.DeepSeekClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 证据链 trace（D3）：五段式组装 + 无锚点不输出 + 锚点全平台内可达（route 以 / 开头）。
 * QA 链路用 @MockBean 喂语义计划，走真实查询后落 bm_qa_trace 再聚合还原。
 */
@SpringBootTest
@TestPropertySource(properties = "bemodel.semantic.scene-ds=DS_HIS")
class TraceFlowTest {

    private static final String CODE = "TEST_TRACE_CONCEPT";
    private static final String DS = "DS_TEST";
    private static final String TABLE = "t_trace_test";

    @Autowired
    private CsService csService;
    @Autowired
    private TraceService traceService;
    @Autowired
    private MappingService mappingService;
    @Autowired
    private ConceptService conceptService;
    @Autowired
    private ConceptMapper conceptMapper;
    @Autowired
    private AttributeMapper attributeMapper;
    @Autowired
    private OntologyMissMapper missMapper;
    @Autowired
    private com.bemodel.cs.mapper.QaTraceMapper qaTraceMapper;
    @MockBean
    private DeepSeekClient deepSeekClient;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        mappingService.list(DS, TABLE, null).forEach(m -> mappingService.removeById(m.getId()));
        conceptService.lambdaUpdate().eq(Concept::getCode, CODE).remove();
        attributeMapper.delete(new LambdaQueryWrapper<Attribute>().eq(Attribute::getConceptCode, CODE));
        missMapper.delete(new LambdaQueryWrapper<OntologyMiss>()
                .eq(OntologyMiss::getAdoptedConceptCode, CODE));
        // 问数成功会落证据链行：按本测试问题精确清理，不跨次累积
        qaTraceMapper.delete(new LambdaQueryWrapper<com.bemodel.cs.QaTrace>()
                .like(com.bemodel.cs.QaTrace::getQuestion, "证据链测试统计医嘱记录"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object o) {
        return (List<Map<String, Object>>) o;
    }

    /** 锚点全平台内可达：route 一律以 / 开头（drugmodel 前车之鉴：站外相对路径=证据链断尾） */
    private void assertRoutesInternal(Map<String, Object> trace) {
        for (Map<String, Object> a : castList(trace.get("anchors"))) {
            String route = String.valueOf(a.get("route"));
            assertTrue(route.startsWith("/"), "证据锚点必须平台内可达: " + route);
        }
        for (Map<String, Object> r : castList(trace.get("refs"))) {
            String route = String.valueOf(r.get("route"));
            assertTrue(route.startsWith("/"), "参考文件必须平台内可达: " + route);
        }
        assertFalse(castList(trace.get("anchors")).isEmpty(), "无锚点不输出");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 问数答案落证据链并可逐段还原() {
        when(deepSeekClient.enabled()).thenReturn(true);
        when(deepSeekClient.chat(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        String planJson = "{\"mode\":\"QUERY\",\"ds\":\"DS_HIS\","
                + "\"sql\":\"SELECT COUNT(*) AS cnt FROM medical_order LIMIT 1\","
                + "\"semantics\":\"MEDICAL_ORDER(医嘱) 计数\"}";
        when(deepSeekClient.chat(eq("CS_SEMANTIC_PLAN"), anyString(), anyString()))
                .thenAnswer(inv -> Optional.of(planJson));

        Map<String, Object> r = csService.ask("证据链测试统计医嘱记录", CsService.SCENE_ANALYTICS);
        String traceId = (String) r.get("traceId");
        assertNotNull(traceId, "语义查询成功应返回 traceId");

        Map<String, Object> t = traceService.assemble("QA", traceId);
        List<Map<String, Object>> spans = castList(t.get("spans"));
        assertEquals(5, spans.size(), "五段：提问/计划/校验/执行/作答");
        assertEquals("提问", spans.get(0).get("title"));
        assertTrue(String.valueOf(spans.get(3).get("detail")).contains("DS_HIS"), "执行段含真实数据源");
        assertTrue(String.valueOf(t.get("summary")).contains("TEMPLATE") || String.valueOf(t.get("summary")).contains("LLM"),
                "作答来源诚实标注");
        assertRoutesInternal(t);

        assertThrows(BizException.class, () -> traceService.assemble("QA", "QA-NOPE"),
                "不存在的 traceId 应显式报错而非编链");
    }

    @Test
    void 映射证据链含生命周期留痕() {
        loginAs("EDITOR");
        Mapping m = new Mapping();
        m.setDsCode(DS);
        m.setTableName(TABLE);
        m.setColumnName("trace_col");
        m.setConceptCode(CODE);
        m.setAttrCode("trace_attr_col");
        m.setSource("AI");
        mappingService.saveBatch(List.of(m));
        Long id = mappingService.list(DS, TABLE, null).stream()
                .filter(x -> "trace_col".equals(x.getColumnName())).findFirst().orElseThrow().getId();
        mappingService.transition(id, "ACTIVE");

        Map<String, Object> t = traceService.assemble("MAPPING", String.valueOf(id));
        assertTrue(String.valueOf(t.get("summary")).contains("PROPOSED") || String.valueOf(t.get("summary")).contains("ACTIVE"));
        List<Map<String, Object>> spans = castList(t.get("spans"));
        assertTrue(spans.stream().anyMatch(s -> String.valueOf(s.get("title")).contains("CREATE")));
        assertTrue(spans.stream().anyMatch(s -> String.valueOf(s.get("title")).contains("TRANSITION")
                && s.get("actor") != null), "流转段应含操作人（与评审员/建模员角色互相成全）");
        assertRoutesInternal(t);
    }

    @Test
    void 概念证据链含词表来源与覆盖缺口提示() {
        loginAs("ADMIN");
        Concept c = new Concept();
        c.setCode(CODE);
        c.setName("证据链测试概念");
        c.setDomainCode("OPS");
        conceptService.create(c);

        // 词表来源：miss 采纳回流
        OntologyMiss miss = new OntologyMiss();
        miss.setTerm("证据链测试缺口说法");
        miss.setKind("CONCEPT");
        miss.setSource("QA_ASK");
        miss.setCount(2);
        miss.setAdoptedConceptCode(CODE);
        miss.setRevoked(0);
        missMapper.insert(miss);

        Map<String, Object> t = traceService.assemble("CONCEPT", CODE);
        List<Map<String, Object>> spans = castList(t.get("spans"));
        assertTrue(spans.stream().anyMatch(s -> "词表缺口回流".equals(s.get("title"))),
                "应还原 miss→概念 的词表来源段");
        assertTrue(castList(t.get("spans")).stream().anyMatch(s -> String.valueOf(s.get("detail")).contains("CONCEPT_NO_ATTR")),
                "裸概念应提示覆盖门禁缺陷");
        assertTrue(((List<?>) t.get("actions")).stream().anyMatch(a -> String.valueOf(a).contains("补属性")),
                "建议动作应给出补属性提示");

        // 补属性后缺口提示消失
        Attribute a = new Attribute();
        a.setConceptCode(CODE);
        a.setAttrCode("trace_attr");
        a.setAttrName("追踪属性");
        a.setDataType("STRING");
        attributeMapper.insert(a);
        Map<String, Object> t2 = traceService.assemble("CONCEPT", CODE);
        assertFalse(String.valueOf(t2.get("actions")).contains("补属性定义"), "有属性后不再提示补属性");
        assertRoutesInternal(t2);
    }

    private void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
