package com.bemodel.cs;

import com.bemodel.cs.mapper.ClarifyTaskMapper;
import com.bemodel.cs.mapper.QaTraceMapper;
import com.bemodel.common.BizException;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 澄清任务流（D2b）按规格 §8 锚点测试：分型（A/V/P/非法值）、续跑（封顶 GAVE_UP / RESOLVED）、
 * 防重复提交、无 Key 降级。LLM 用 @MockBean 精准喂 CS_SEMANTIC_PLAN 结构化 JSON，
 * 其余调用（路由/作答/追问话术）一律降级空值——分型取结构化 gapType，不嗅探 reason 文本。
 */
@SpringBootTest
@TestPropertySource(properties = "bemodel.semantic.scene-ds=DS_HIS")
class ClarifyFlowTest {

    private static final String PREFIX = "澄清流程测试";
    /** 含「统计」走关键词 SEMANTIC_QUERY；不含口径/定义等 Glossary 触发词 */
    private static final String A_JSON =
            "{\"mode\":\"UNANSWERABLE\",\"reason\":\"统计对象不明确\",\"gapType\":\"AMBIGUITY\"}";
    private static final String V_JSON =
            "{\"mode\":\"UNANSWERABLE\",\"reason\":\"本体无此概念\",\"gapType\":\"VOCABULARY\"}";
    private static final String BAD_JSON =
            "{\"mode\":\"UNANSWERABLE\",\"reason\":\"原因X\",\"gapType\":\"FOO\"}";
    private static final String QUERY_JSON =
            "{\"mode\":\"QUERY\",\"ds\":\"DS_HIS\",\"sql\":\"SELECT COUNT(*) AS cnt FROM medical_order LIMIT 1\","
                    + "\"semantics\":\"MEDICAL_ORDER(医嘱) 计数\"}";

    @Autowired
    private CsService csService;
    @Autowired
    private ClarifyTaskMapper clarifyTaskMapper;
    @Autowired
    private OntologyMissMapper missMapper;
    @Autowired
    private QaTraceMapper qaTraceMapper;
    @MockBean
    private DeepSeekClient deepSeekClient;

    @AfterEach
    void cleanup() {
        clarifyTaskMapper.delete(new LambdaQueryWrapper<ClarifyTask>()
                .like(ClarifyTask::getOriginQuestion, PREFIX));
        missMapper.delete(new LambdaQueryWrapper<OntologyMiss>()
                .like(OntologyMiss::getTerm, PREFIX).eq(OntologyMiss::getKind, "QUESTION"));
        // QUERY 成功路径会落证据链行，按问题前缀精确清理（bm_qa_trace 无唯一键，不清理会跨次累积）
        qaTraceMapper.delete(new LambdaQueryWrapper<QaTrace>()
                .like(QaTrace::getQuestion, PREFIX));
    }

    // ---------- 规格 §8.1 分型 ----------

    @Test
    void A型建澄清任务且不记miss() {
        stubPlan(A_JSON);
        Map<String, Object> r = csService.ask(PREFIX + "统计对象不明的问题", CsService.SCENE_ANALYTICS);

        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) r.get("clarifyTask");
        assertNotNull(card, "A 型歧义应返回澄清卡");
        assertEquals(PREFIX + "统计对象不明的问题", card.get("originQuestion"));
        assertNotNull(card.get("id"));
        assertTrue(String.valueOf(card.get("question")).contains("统计对象不明确"),
                "追问话术应含 LLM 给出的 reason（模板降级也含）");
        assertNull(r.get("missRecorded"), "A 型不记 miss（歧义尚未定性）");
        ClarifyTask t = clarifyTaskMapper.selectById(((Number) card.get("id")).longValue());
        assertEquals("PENDING", t.getStatus());
        assertEquals(1, t.getRounds());
        assertEquals(0, missCount(PREFIX + "统计对象不明的问题"), "不应回流 bm_ontology_miss");
    }

    @Test
    void V型与非法gapType不建任务且记miss() {
        stubPlan(V_JSON);
        String q1 = PREFIX + "词表缺口问题统计";
        Map<String, Object> r1 = csService.ask(q1, CsService.SCENE_ANALYTICS);
        assertNull(r1.get("clarifyTask"), "V 型不建澄清任务");
        assertEquals(Boolean.TRUE, r1.get("missRecorded"), "V 型照旧回流增长回路");
        assertEquals(1, missCount(q1));

        stubPlan(BAD_JSON);
        String q2 = PREFIX + "非法分型问题统计";
        Map<String, Object> r2 = csService.ask(q2, CsService.SCENE_ANALYTICS);
        assertNull(r2.get("clarifyTask"), "gapType 非法值按 V 型降级");
        assertEquals(Boolean.TRUE, r2.get("missRecorded"));
        assertEquals(1, missCount(q2));
    }

    @Test
    void P型路径不建任务且记miss() {
        // 代码路径天然确定的 P 型：计划给未知数据源 → 白名单为空 → 工程侧缺口，不追问用户
        stubPlan("{\"mode\":\"QUERY\",\"ds\":\"DS_NOPE\",\"sql\":\"SELECT 1 FROM t LIMIT 1\",\"semantics\":\"s\"}");
        String q = PREFIX + "平台缺口问题统计";
        Map<String, Object> r = csService.ask(q, CsService.SCENE_ANALYTICS);
        assertNull(r.get("clarifyTask"), "P 型不建澄清任务");
        assertEquals(Boolean.TRUE, r.get("missRecorded"));
        assertEquals(1, missCount(q));
    }

    @Test
    void P型SQL校验失败不建任务且记miss() {
        // §8.1 P 型三路径之二：计划 SQL 过不了安全校验（多语句分号）→ 工程侧缺口，不追问用户
        stubPlan("{\"mode\":\"QUERY\",\"ds\":\"DS_HIS\","
                + "\"sql\":\"SELECT order_id FROM medical_order; SELECT 1\",\"semantics\":\"s\"}");
        String q = PREFIX + "校验失败问题统计";
        Map<String, Object> r = csService.ask(q, CsService.SCENE_ANALYTICS);
        assertNull(r.get("clarifyTask"), "P 型不建澄清任务");
        assertEquals(Boolean.TRUE, r.get("missRecorded"));
        assertEquals(1, missCount(q));
    }

    @Test
    void P型执行失败不建任务且记miss() {
        // §8.1 P 型三路径之三：白名单内 SQL 真实执行报错（EXP 溢出是 MySQL 确定性错误）
        stubPlan("{\"mode\":\"QUERY\",\"ds\":\"DS_HIS\",\"sql\":\"SELECT EXP(9999) AS bomb\",\"semantics\":\"s\"}");
        String q = PREFIX + "执行失败问题统计";
        Map<String, Object> r = csService.ask(q, CsService.SCENE_ANALYTICS);
        assertNull(r.get("clarifyTask"), "P 型不建澄清任务");
        assertEquals(Boolean.TRUE, r.get("missRecorded"));
        assertEquals(1, missCount(q));
    }

    // ---------- 规格 §8.2 续跑 ----------

    @Test
    @SuppressWarnings("unchecked")
    void 续跑两轮封顶GAVE_UP并回流miss与证据() {
        stubPlan(A_JSON, A_JSON, A_JSON);
        String q = PREFIX + "两轮封顶问题统计";
        Map<String, Object> r1 = csService.ask(q, CsService.SCENE_ANALYTICS);
        Long taskId = ((Number) ((Map<String, Object>) r1.get("clarifyTask")).get("id")).longValue();

        // 第 1 次补充 → 仍 A 型 → 第 2 轮追问卡（rounds=2）
        Map<String, Object> r2 = csService.clarifyAnswer(taskId, "时间范围为8月");
        Map<String, Object> card2 = (Map<String, Object>) r2.get("clarifyTask");
        assertNotNull(card2, "第 1 轮补充后仍歧义应出第 2 轮追问卡");
        assertEquals(2, card2.get("rounds"));
        assertEquals(taskId, ((Number) card2.get("id")).longValue());

        // PENDING 重复提交同一份补充 → 拒绝（不覆盖证据）
        BizException dup = assertThrows(BizException.class, () -> csService.clarifyAnswer(taskId, "时间范围为8月"));
        assertTrue(dup.getMessage().contains("已提交过"));

        // 第 2 次补充 → 仍 A 型且 rounds=2 封顶 → GAVE_UP 回流
        Map<String, Object> r3 = csService.clarifyAnswer(taskId, "科室为心内科");
        assertEquals(Boolean.TRUE, r3.get("clarifyGaveUp"));
        assertNull(r3.get("clarifyTask"), "封顶后不再追问");
        assertEquals(Boolean.TRUE, r3.get("missRecorded"));
        assertEquals(1, missCount(q), "封顶时才回流 miss");

        ClarifyTask t = clarifyTaskMapper.selectById(taskId);
        assertEquals("GAVE_UP", t.getStatus());
        assertNotNull(t.getMissId(), "miss_id 应回填");
        assertTrue(t.getSupplement().contains("时间范围为8月") && t.getSupplement().contains("科室为心内科"),
                "两轮补充证据都在任务行上");
    }

    @Test
    void 续跑真实答出则RESOLVED且不记miss() {
        stubPlan(A_JSON, QUERY_JSON);
        String q = PREFIX + "澄清后可答问题统计";
        Map<String, Object> r1 = csService.ask(q, CsService.SCENE_ANALYTICS);
        Long taskId = ((Number) ((Map<String, Object>) r1.get("clarifyTask")).get("id")).longValue();

        Map<String, Object> r2 = csService.clarifyAnswer(taskId, "限定全院全月");
        assertEquals(Boolean.TRUE, r2.get("clarifyResolved"), "补充后真实答出应标记 clarifyResolved");
        assertEquals("SEMANTIC", r2.get("router"), "续跑走真实查询");
        assertEquals(0, missCount(q), "澄清后答出 = 本体词表没病，不记 miss");

        ClarifyTask t = clarifyTaskMapper.selectById(taskId);
        assertEquals("RESOLVED", t.getStatus());
        assertNull(t.getMissId());
    }

    @Test
    void 续跑计划失败任务保持PENDING且标记clarifyRetry() {
        // 非缺口失败（计划 mode 非法=LLM 波动）：不记 miss、不给终态，任务保持 PENDING 可再补
        stubPlan(A_JSON, "{\"mode\":\"BAD\",\"reason\":\"x\"}");
        String q = PREFIX + "续跑波动问题统计";
        Map<String, Object> r1 = csService.ask(q, CsService.SCENE_ANALYTICS);
        Long taskId = ((Number) ((Map<String, Object>) r1.get("clarifyTask")).get("id")).longValue();

        Map<String, Object> r2 = csService.clarifyAnswer(taskId, "时间范围为8月");
        assertEquals(Boolean.TRUE, r2.get("clarifyRetry"), "非缺口失败应标记 clarifyRetry");
        assertNull(r2.get("clarifyResolved"), "计划失败不是答案，不得标记已答出");
        assertNull(r2.get("clarifyTask"));
        assertEquals(0, missCount(q), "LLM 波动不是词表缺口，不记 miss");
        ClarifyTask t = clarifyTaskMapper.selectById(taskId);
        assertEquals("PENDING", t.getStatus(), "任务保持进行中");
    }

    @Test
    void 续跑兜底菜单不误标RESOLVED() {
        // 回归：补充带「口径」→ q' 翻转路由进 GLOSSARY，词表未命中落兜底菜单——
        // 兜底菜单不是答案：任务保持 PENDING，不得返回 clarifyResolved（否则绿色「已答出」叠在未命中文案上）
        stubPlan(A_JSON);
        String q = PREFIX + "补充不误判问题统计";
        Map<String, Object> r1 = csService.ask(q, CsService.SCENE_ANALYTICS);
        Long taskId = ((Number) ((Map<String, Object>) r1.get("clarifyTask")).get("id")).longValue();

        Map<String, Object> r2 = csService.clarifyAnswer(taskId, "统计口径为甲乙丙丁");
        assertNull(r2.get("clarifyResolved"), "兜底菜单不得标记已答出");
        assertEquals(Boolean.TRUE, r2.get("clarifyRetry"));
        ClarifyTask t = clarifyTaskMapper.selectById(taskId);
        assertEquals("PENDING", t.getStatus(), "任务保持进行，可换措辞再补");
    }

    // ---------- 规格 §8.3 降级 ----------

    @Test
    void 无LLM时澄清功能整体不出现() {
        // 不 stub enabled() → 默认 false = 无 Key 现状路径：关键词路由进语义层后 plan=null，落菜单
        String q = PREFIX + "无降级问题统计";
        Map<String, Object> r = csService.ask(q, CsService.SCENE_ANALYTICS);
        assertFalse(r.containsKey("clarifyTask"), "无 Key 不产澄清卡字段");
        assertNull(r.get("missRecorded"), "无 Key 计划不可得，不误记缺口（与现状一致）");
    }

    // ---------- helpers ----------

    /** 喂 CS_SEMANTIC_PLAN 的结构化计划；其余 callType（路由/作答/追问话术）一律降级空值 */
    private void stubPlan(String... planJsons) {
        when(deepSeekClient.enabled()).thenReturn(true);
        when(deepSeekClient.chat(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        when(deepSeekClient.chat(eq("CS_SEMANTIC_PLAN"), anyString(), anyString()))
                .thenAnswer(inv -> Optional.of(planJsons[Math.min(idx.getAndIncrement(), planJsons.length - 1)]));
    }

    private long missCount(String origin) {
        return missMapper.selectCount(new LambdaQueryWrapper<OntologyMiss>()
                .eq(OntologyMiss::getTerm, origin).eq(OntologyMiss::getKind, "QUESTION"));
    }
}
