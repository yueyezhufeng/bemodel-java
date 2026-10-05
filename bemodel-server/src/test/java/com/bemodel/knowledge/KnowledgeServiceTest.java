package com.bemodel.knowledge;

import com.bemodel.common.BizException;
import com.bemodel.knowledge.entity.Document;
import com.bemodel.knowledge.entity.DocChunk;
import com.bemodel.knowledge.entity.Knowledge;
import com.bemodel.knowledge.mapper.DocumentMapper;
import com.bemodel.knowledge.mapper.DocChunkMapper;
import com.bemodel.knowledge.mapper.KnowledgeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 摄取管线与条目治理：上传解析切分/去重/新版本/生命周期/条目审批编辑/entryContent/basisFor */
@SpringBootTest
class KnowledgeServiceTest {

    @Autowired
    private KnowledgeService knowledgeService;
    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private DocChunkMapper docChunkMapper;
    @Autowired
    private KnowledgeMapper knowledgeMapper;

    /** 本测试创建的文档 id 集合，afterEach 统一清理（文档+级联片段） */
    private final java.util.List<Long> createdDocs = new java.util.ArrayList<>();
    private final java.util.List<Long> createdEntries = new java.util.ArrayList<>();

    @AfterEach
    void clean() {
        for (Long id : createdDocs) {
            docChunkMapper.delete(new LambdaQueryWrapper<DocChunk>().eq(DocChunk::getDocumentId, id));
            documentMapper.deleteById(id);
        }
        for (Long id : createdEntries) {
            knowledgeMapper.deleteById(id);
        }
        SecurityContextHolder.clearContext();
    }

    /** 生命周期门禁依赖当前用户（对齐 MappingLifecycleTest/ConceptServiceTest 范式） */
    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private MockMultipartFile md(String title, String body) {
        return new MockMultipartFile("file", title, "text/markdown",
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadShouldParseChunkAndPersistDocument() {
        loginAs("EDITOR");
        Document d = knowledgeService.upload(md("摄取测试A.md", "# 标题一\n\n第一段规定内容。\n\n## 标题二\n\n第二段细则。"), "tester");
        createdDocs.add(d.getId());

        assertEquals("DRAFT", d.getStatus());
        assertEquals("MD", d.getDocType());
        assertNotNull(d.getContentHash());
        List<DocChunk> chunks = knowledgeService.chunksOf(d.getId());
        assertEquals(2, chunks.size(), "两个标题段应切出两片");
        assertEquals("标题一", chunks.get(0).getHeading());
        assertEquals("标题一/标题二", chunks.get(1).getHeading());
    }

    @Test
    void duplicateContentShouldBeRejectedButNewVersionAllowed() {
        loginAs("EDITOR");
        Document first = knowledgeService.upload(md("摄取测试B.md", "# 规则\n\n正文一段。"), "tester");
        createdDocs.add(first.getId());
        assertThrows(BizException.class,
                () -> knowledgeService.upload(md("摄取测试B.md", "# 规则\n\n正文一段。"), "tester"),
                "同内容重复上传应被拒");
        Document v2 = knowledgeService.upload(md("摄取测试B.md", "# 规则\n\n修订后的正文二段。"), "tester");
        createdDocs.add(v2.getId());
        assertEquals(2, v2.getVersion(), "同标题不同内容应递增版本");
    }

    @Test
    void transitionShouldFollowLifecycleAndDeprecateOldPublished() {
        // ADMIN 全程操作：绕过防自审比对（防自审细节由概念域同款逻辑覆盖，此处不重复测）
        loginAs("ADMIN");
        Document v1 = knowledgeService.upload(md("摄取测试C.md", "# 一版\n\n一版内容。"), "tester");
        createdDocs.add(v1.getId());
        knowledgeService.transition(v1.getId(), "REVIEW");
        knowledgeService.transition(v1.getId(), "PUBLISHED");
        Document v2 = knowledgeService.upload(md("摄取测试C.md", "# 二版\n\n二版内容。"), "tester");
        createdDocs.add(v2.getId());
        knowledgeService.transition(v2.getId(), "REVIEW");
        knowledgeService.transition(v2.getId(), "PUBLISHED");

        assertEquals("DEPRECATED", documentMapper.selectById(v1.getId()).getStatus(),
                "发布新版本应置旧版 DEPRECATED");
        assertEquals("PUBLISHED", documentMapper.selectById(v2.getId()).getStatus());
        assertThrows(BizException.class, () -> knowledgeService.transition(v2.getId(), "REVIEW"),
                "状态机不允许 PUBLISHED→REVIEW");
        assertThrows(BizException.class, () -> knowledgeService.delete(v2.getId()),
                "已发布文档可停用不可物理删");
    }

    @Test
    void publishedEntryEditShouldDropBackToReview() {
        loginAs("EDITOR");
        Knowledge k = new Knowledge();
        k.setCode("TEST_ENTRY_XYZ");
        k.setTitle("测试条目");
        k.setContent("初版内容。");
        k.setAppliesTo("FEE_DETAIL");
        k.setSource("ADMIN");
        k.setStatus("PUBLISHED");
        k.setVersion(1);
        k.setOwner("tester");
        knowledgeMapper.insert(k);
        createdEntries.add(k.getId());

        assertEquals("初版内容。", knowledgeService.entryContent("TEST_ENTRY_XYZ"));
        assertTrue(knowledgeService.basisFor("FEE_DETAIL").contains("初版内容。"), "依据位应包含已发布条目正文");

        k.setContent("修订内容。");
        knowledgeService.updateEntry(k);
        assertEquals("REVIEW", knowledgeMapper.selectById(k.getId()).getStatus(),
                "已发布条目编辑应自动回到待审");
        assertNull(knowledgeService.entryContent("TEST_ENTRY_XYZ"),
                "REVIEW 态条目不再对外提供内容");
    }

    @Test
    void deprecatingPublishedDocumentRequiresReviewer() {
        // 废弃分层门禁（与概念域 H2 对齐）：停用已发布文档须评审员/管理员，建模员被拒；
        // 未发布草稿的废弃不设门禁（从未对外，废弃即撤销）
        loginAs("EDITOR");
        Document doc = knowledgeService.upload(md("停用门禁测试.md", "# 门禁\n\n停用门禁内容。"), "tester");
        createdDocs.add(doc.getId());
        knowledgeService.transition(doc.getId(), "REVIEW");
        loginAs("ADMIN");
        knowledgeService.transition(doc.getId(), "PUBLISHED");

        loginAs("EDITOR");
        assertThrows(BizException.class, () -> knowledgeService.transition(doc.getId(), "DEPRECATED"),
                "建模员不得停用已发布文档");
        loginAs("REVIEWER");
        assertEquals("DEPRECATED", knowledgeService.transition(doc.getId(), "DEPRECATED").getStatus(),
                "评审员可停用已发布文档");

        Document draft = knowledgeService.upload(md("停用门禁测试B.md", "# 草稿\n\n草稿停用不受限。"), "tester");
        createdDocs.add(draft.getId());
        assertEquals("DEPRECATED", knowledgeService.transition(draft.getId(), "DEPRECATED").getStatus(),
                "未发布草稿废弃不设门禁");
    }
}
