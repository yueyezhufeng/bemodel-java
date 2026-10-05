package com.bemodel.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.knowledge.entity.Document;
import com.bemodel.knowledge.entity.DocChunk;
import com.bemodel.knowledge.mapper.DocumentMapper;
import com.bemodel.knowledge.mapper.DocChunkMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 混合检索集成：FULLTEXT 关键词命中/停用文档不命中/口径页第四池结构（无 Ollama=向量路关退纯关键词） */
@SpringBootTest
class KnowledgeSearchServiceTest {

    @Autowired
    private KnowledgeSearchService searchService;
    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private DocChunkMapper docChunkMapper;

    private Long docId;

    @AfterEach
    void clean() {
        if (docId != null) {
            docChunkMapper.delete(new LambdaQueryWrapper<DocChunk>().eq(DocChunk::getDocumentId, docId));
            documentMapper.deleteById(docId);
        }
    }

    private void seedDoc(String title, String status, String heading, String content) {
        Document d = new Document();
        d.setTitle(title);
        d.setDocType("MD");
        d.setSource("UPLOAD");
        d.setStatus(status);
        d.setVersion(1);
        d.setUploadedBy("tester");
        documentMapper.insert(d);
        docId = d.getId();
        DocChunk c = new DocChunk();
        c.setDocumentId(docId);
        c.setSeq(0);
        c.setHeading(heading);
        c.setContent(content);
        docChunkMapper.insert(c);
    }

    @Test
    void keywordRouteShouldHitPublishedAndExcludeDeprecated() {
        seedDoc("检索测试《探视管理》", "PUBLISHED", "探视时间",
                "病区探视时间规定为每日下午15时至19时，传染病区暂停探视。");
        try {
            // 共享库纪律：不断全局 top1（库内「制度」密集片段随时可能按词频相关度反超），
            // 只断自种文档在关键词路命中且命中片段身份正确
            List<Map<String, Object>> hits = searchService.search("探视时间规定", 20);
            List<Map<String, Object>> own = hits.stream()
                    .filter(h -> String.valueOf(docId).equals(h.get("documentId"))).toList();
            assertFalse(own.isEmpty(), "PUBLISHED 片段应被关键词路命中");
            assertEquals("检索测试《探视管理》", own.get(0).get("docTitle"));
            assertEquals("探视时间", own.get(0).get("heading"));
        } finally {
            // 停用后同查询不应再命中（PUBLISHED 过滤在 join 层）
            Document d = documentMapper.selectById(docId);
            d.setStatus("DEPRECATED");
            documentMapper.updateById(d);
        }
        List<Map<String, Object>> after = searchService.search("探视时间规定", 5);
        assertTrue(after.stream().noneMatch(h -> String.valueOf(docId).equals(h.get("documentId"))),
                "停用文档片段不应再被检索命中（库内其他已发布文档同词命中不在本断言范围）");
    }

    @Test
    void fulltextHitsShouldCarryGlossaryHitShape() {
        seedDoc("检索测试《退费规定》", "PUBLISHED", "退费流程",
                "退药与退费必须成对完成，收费侧同步退费。");
        List<Map<String, Object>> hits = searchService.fulltextHits("退费", 20);
        List<Map<String, Object>> own = hits.stream()
                .filter(h -> String.valueOf(h.get("title")).contains("《检索测试《退费规定》》")).toList();
        assertFalse(own.isEmpty(), "自种文档应进入第四池（成员断言，不假设库内唯一）");
        assertEquals("文档", own.get(0).get("type"), "第四池类型标签=文档");
        assertNotNull(own.get(0).get("docId"));
    }

    @Test
    void naturalPhraseShouldHitViaSegmentedBooleanQuery() {
        // 「探视时间」与「本制度」分属两处，片段无连续「探视制度」四字——旧短语语义必零命中
        seedDoc("检索测试《探视制度短语》", "PUBLISHED", "探视时间",
                "病区探视时间为每日下午15时至19时，每次不超过2人。本制度适用于全院病区。");
        List<Map<String, Object>> kw = searchService.fulltextHits("探视制度", 20);
        assertTrue(kw.stream().anyMatch(h -> String.valueOf(h.get("title")).contains("《检索测试《探视制度短语》》")),
                "自然短语应经切词命中纯关键词路（修复前 ngram 短语语义必零命中）");
        // 窗口取 20（=关键词路召回宽度）：RRF fused top5 会被库内「制度」密集片段挤占（词频相关度已知权衡），
        // 成员断言放宽到全召回宽即确定性成立
        List<Map<String, Object>> hybrid = searchService.search("探视制度", 20);
        assertTrue(hybrid.stream().anyMatch(h -> String.valueOf(docId).equals(h.get("documentId"))),
                "混合检索同短语应命中自种文档（成员断言，不假设库内唯一）");
    }

    @Test
    void punctuationOnlyQueryShouldSkipKeywordPath() {
        assertTrue(searchService.fulltextHits("，。！？", 5).isEmpty(),
                "纯标点构不成词项，应短路返回空而不是空转 SQL");
    }
}
