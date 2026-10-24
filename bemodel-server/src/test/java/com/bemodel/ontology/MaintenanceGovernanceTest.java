package com.bemodel.ontology;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.bemodel.datasource.service.MappingService;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.instance.InstanceService;
import com.bemodel.notice.AlertNotice;
import com.bemodel.notice.NoticeService;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import com.bemodel.ontology.service.ConceptService;
import com.bemodel.ontology.service.MissService;
import com.bemodel.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本体维护治理加固端到端（spec: 2026-09-16-ontology-maintenance-hardening-design）：
 * H1 编辑字段白名单（状态/版本/负责人/编码不可越权改写）；
 * H2 未发布概念直达废弃（版本不虚增）+ 已发布废弃维持评审门禁 + 撤销采纳直达废弃；
 * H3 废弃联动在线世界（ACTIVE 映射幂等告警、搜索隔离且不回流 miss、实例投影显式拒绝）；
 * 工业元数据保留维护与留痕边界。
 */
@SpringBootTest
class MaintenanceGovernanceTest {

    private static final String CODE = "TEST_MAINT_HARDEN";
    private static final String DS = "DS_MAINT";
    private static final String TABLE = "t_maint_test";

    @Autowired
    private ConceptService conceptService;
    @Autowired
    private MissService missService;
    @Autowired
    private OntologyMissMapper missMapper;
    @Autowired
    private MappingService mappingService;
    @Autowired
    private SearchService searchService;
    @Autowired
    private InstanceService instanceService;
    @Autowired
    private NoticeService noticeService;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        conceptService.lambdaUpdate().eq(Concept::getCode, CODE).remove();
        for (Mapping m : mappingService.list(DS, TABLE, null)) {
            mappingService.removeById(m.getId());
        }
        noticeService.remove(new LambdaQueryWrapper<AlertNotice>()
                .likeRight(AlertNotice::getMetricCode, "CONCEPT_DEPRECATED:TEST_MAINT"));
        missMapper.delete(new LambdaQueryWrapper<OntologyMiss>()
                .likeRight(OntologyMiss::getTerm, "维护治理"));
    }

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Concept draft(String name) {
        Concept c = new Concept();
        c.setCode(CODE);
        c.setName(name);
        c.setDomainCode("OPS");
        return conceptService.create(c);
    }

    // ---------- H1 编辑字段白名单 ----------

    @Test
    void 编辑白名单_状态版本负责人编码不可越权改写() {
        draft("原名");
        Concept saved = conceptService.getByCode(CODE);
        assertEquals(1, saved.getVersion());

        Concept patch = new Concept();
        patch.setId(saved.getId());
        patch.setStatus("PUBLISHED");   // 越权：跳过评审门禁直改状态
        patch.setVersion(99);           // 越权：虚增版本
        patch.setOwner("attacker");     // 越权：改负责人
        patch.setCode("HACKED");        // 越权：改业务键
        patch.setName(" 改名后 ");
        patch.setDefinition("新定义");
        patch.setDomainCode("OPS");
        conceptService.updateContent(patch);

        Concept after = conceptService.getByCode(CODE);
        assertEquals("DRAFT", after.getStatus(), "状态不可经编辑通道改写");
        assertEquals(1, after.getVersion(), "版本不可经编辑通道改写");
        assertNull(after.getOwner(), "负责人不可经编辑通道改写");
        assertEquals(CODE, after.getCode(), "业务键不可经编辑通道改写");
        assertEquals("改名后", after.getName(), "白名单内容字段应生效（且 trim）");
        assertEquals("新定义", after.getDefinition());
        assertNull(conceptService.getByCode("HACKED"), "不应产生改码效果");
    }

    @Test
    void 编辑白名单_域不存在报错() {
        Concept saved = draft("原名");
        Concept patch = new Concept();
        patch.setId(saved.getId());
        patch.setDomainCode("NO_SUCH_DOMAIN");
        BizException ex = assertThrows(BizException.class, () -> conceptService.updateContent(patch));
        assertTrue(ex.getMessage().contains("业务域不存在"), "实际: " + ex.getMessage());
    }

    @Test
    void 编辑白名单_IRI可清空且不回写治理字段() {
        Concept saved = draft("IRI清空测试");
        conceptService.lambdaUpdate()
                .eq(Concept::getId, saved.getId())
                .set(Concept::getIri, "https://old.example/onto#X")
                .update();

        Concept patch = new Concept();
        patch.setId(saved.getId());
        patch.setIri("   "); // 空白 → 应显式置 NULL 落库（MyBatis-Plus 默认 NOT_NULL 策略会跳过 null 字段）
        Concept after = conceptService.updateContent(patch);
        assertNull(after.getIri(), "空白 IRI 应清空");
        assertNull(conceptService.getByCode(CODE).getIri(), "清空应真实落库");
    }

    // ---------- H2 状态机直达废弃 + 撤销采纳 ----------

    @Test
    void 未发布概念废弃直达且版本不虚增() {
        loginAs("EDITOR");
        draft("废弃直达");
        conceptService.transition(CODE, "REVIEW");
        Concept deprecated = conceptService.transition(CODE, "DEPRECATED");
        assertEquals("DEPRECATED", deprecated.getStatus());
        assertEquals(1, deprecated.getVersion(), "未发布概念废弃不应虚增版本（旧实现途经瞬态 PUBLISHED 会 +1）");
    }

    @Test
    void 废弃已发布概念须评审员() {
        draft("发布后废弃");
        conceptService.transition(CODE, "REVIEW");
        loginAs("REVIEWER");
        conceptService.transition(CODE, "PUBLISHED");
        loginAs("EDITOR");
        BizException ex = assertThrows(BizException.class, () -> conceptService.transition(CODE, "DEPRECATED"));
        assertTrue(ex.getMessage().contains("废弃已发布概念需评审员"), "实际: " + ex.getMessage());
        loginAs("REVIEWER");
        assertEquals("DEPRECATED", conceptService.transition(CODE, "DEPRECATED").getStatus());
    }

    @Test
    void 撤销采纳直达废弃不途经发布() {
        loginAs("EDITOR");
        missService.recordMiss("维护治理测试词", "CONCEPT", "SEARCH");
        OntologyMiss miss = missMapper.selectOne(new LambdaQueryWrapper<OntologyMiss>()
                .eq(OntologyMiss::getTerm, "维护治理测试词").eq(OntologyMiss::getKind, "CONCEPT"));
        assertNotNull(miss);
        missService.adopt(miss.getId(), CODE, "维护治理测试概念", "OPS", "撤销采纳测试定义");
        assertEquals("DRAFT", conceptService.getByCode(CODE).getStatus());

        missService.revoke(miss.getId());

        Concept after = conceptService.getByCode(CODE);
        assertEquals("DEPRECATED", after.getStatus(), "撤销采纳应直达废弃");
        assertEquals(1, after.getVersion(), "撤销采纳不应虚增版本");
        OntologyMiss reloaded = missMapper.selectById(miss.getId());
        assertEquals(1, reloaded.getRevoked());
    }

    // ---------- H3 废弃联动在线世界 ----------

    @Test
    void 废弃联动_幂等告警_搜索隔离与投影拒绝() {
        // 发布概念 + 建 ACTIVE 映射
        draft("维护治理检索专名XYZQ");
        conceptService.transition(CODE, "REVIEW");
        loginAs("REVIEWER");
        conceptService.transition(CODE, "PUBLISHED");
        loginAs("EDITOR");
        mappingService.saveBatch(List.of(mapping()));
        assertEquals("ACTIVE", mappingService.list(DS, TABLE, null).get(0).getStatus());

        // 废弃前：搜索命中
        Map<String, Object> before = searchService.search("维护治理检索专名XYZQ");
        assertTrue(hitsContain(before, CODE), "发布概念应被搜索命中");

        // 废弃（REVIEWER 门禁）：生成映射复查告警
        loginAs("REVIEWER");
        conceptService.transition(CODE, "DEPRECATED");
        List<AlertNotice> notices = noticesFor(CODE);
        assertEquals(1, notices.size(), "废弃带 ACTIVE 映射的概念应生成一条告警");
        assertTrue(notices.get(0).getMessage().contains("ACTIVE 映射"), "文案: " + notices.get(0).getMessage());
        assertEquals("未读", notices.get(0).getStatus());

        // 废弃后：搜索不再命中该概念，且不回流 miss（词有归宿）
        Map<String, Object> after = searchService.search("维护治理检索专名XYZQ");
        assertFalse(hitsContain(after, CODE), "废弃概念不应再被搜索命中");
        Long missCnt = missMapper.selectCount(new LambdaQueryWrapper<OntologyMiss>()
                .eq(OntologyMiss::getTerm, "维护治理检索专名XYZQ").eq(OntologyMiss::getKind, "CONCEPT"));
        assertEquals(0L, missCnt, "仅废弃概念命中时不应回流 miss");

        // 实例投影显式拒绝
        BizException ex = assertThrows(BizException.class,
                () -> instanceService.instances(CODE, null, 1, 10));
        assertTrue(ex.getMessage().contains("概念已废弃"), "实际: " + ex.getMessage());

        // 重建再废弃：旧告警仍未读 → 幂等不重复
        conceptService.transition(CODE, "DRAFT");
        conceptService.transition(CODE, "REVIEW");
        loginAs("REVIEWER");
        conceptService.transition(CODE, "PUBLISHED");
        conceptService.transition(CODE, "DEPRECATED");
        assertEquals(1, noticesFor(CODE).size(), "旧告警未读期间再次废弃不应重复告警");
    }

    @Test
    void 废弃无映射概念不产生告警() {
        draft("维护治理无映射概念");
        conceptService.transition(CODE, "REVIEW");
        loginAs("REVIEWER");
        conceptService.transition(CODE, "PUBLISHED");
        conceptService.transition(CODE, "DEPRECATED");
        assertEquals(0, noticesFor(CODE).size(), "无 ACTIVE 映射的废弃不应产生告警");
    }

    // ---------- 澄清任务列表（维护者可见性） ----------



    // ---------- helpers ----------

    private Mapping mapping() {
        Mapping m = new Mapping();
        m.setDsCode(DS);
        m.setTableName(TABLE);
        m.setColumnName("maint_col");
        m.setConceptCode(CODE);
        m.setAttrCode("name");
        m.setSource("MANUAL"); // MANUAL 直接 ACTIVE（V30）
        return m;
    }

    private boolean hitsContain(Map<String, Object> result, String conceptCode) {
        List<?> hits = (List<?>) result.get("hits");
        return hits != null && hits.stream().anyMatch(h ->
                conceptCode.equals(((Map<?, ?>) h).get("conceptCode")));
    }

    private List<AlertNotice> noticesFor(String conceptCode) {
        return noticeService.list(new LambdaQueryWrapper<AlertNotice>()
                .eq(AlertNotice::getMetricCode, "CONCEPT_DEPRECATED:" + conceptCode));
    }
}
