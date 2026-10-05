package com.bemodel.cs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.cs.mapper.QaTraceMapper;
import com.bemodel.knowledge.entity.Document;
import com.bemodel.knowledge.entity.DocChunk;
import com.bemodel.knowledge.mapper.DocumentMapper;
import com.bemodel.knowledge.mapper.DocChunkMapper;
import com.bemodel.llm.DeepSeekClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DOC_QA 端到端：LLM 路由命中→检索→CS_DOC_ANSWER 作答→matched_docs 落锚点 */
@SpringBootTest
class DocQaFlowTest {

    @Autowired
    private CsService csService;
    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private DocChunkMapper docChunkMapper;
    @Autowired
    private QaTraceMapper qaTraceMapper;
    @MockBean
    private DeepSeekClient deepSeekClient;

    private Long docId;

    @BeforeEach
    void seedDoc() {
        Document d = new Document();
        d.setTitle("病区探视管理规定（测试）");
        d.setDocType("MD");
        d.setSource("UPLOAD");
        d.setStatus("PUBLISHED");
        d.setVersion(1);
        d.setUploadedBy("tester");
        documentMapper.insert(d);
        docId = d.getId();
        DocChunk c = new DocChunk();
        c.setDocumentId(docId);
        c.setSeq(0);
        c.setHeading("探视时间");
        c.setContent("病区探视时间为每日下午15时至19时，每次每床同时探视人员不超过2人，传染病区暂停探视。");
        docChunkMapper.insert(c);
    }

    @AfterEach
    void clean() {
        if (docId != null) {
            docChunkMapper.delete(new LambdaQueryWrapper<DocChunk>().eq(DocChunk::getDocumentId, docId));
            documentMapper.deleteById(docId);
        }
        qaTraceMapper.delete(new LambdaQueryWrapper<QaTrace>()
                .eq(QaTrace::getQuestion, "病区探视时间？"));
    }

    @Test
    void docQaShouldAnswerWithCitationAndTrace() {
        when(deepSeekClient.enabled()).thenReturn(true);
        when(deepSeekClient.chat(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(deepSeekClient.chat(eq("CS_ROUTE"), anyString(), anyString()))
                .thenReturn(Optional.of("DOC_QA"));
        when(deepSeekClient.chat(eq("CS_DOC_ANSWER"), anyString(), anyString()))
                .thenReturn(Optional.of("根据《病区探视管理规定（测试）》，探视时间为每日下午15时至19时。"));

        Map<String, Object> r = csService.ask("病区探视时间？");

        assertEquals("制度依据查询", r.get("intent"), "LLM 路由 DOC_QA 应进文档问答");
        assertEquals("LLM", r.get("router"));
        assertTrue(String.valueOf(r.get("answer")).contains("15时至19时"), "答案应来自制度作答");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).chat(eq("CS_DOC_ANSWER"), anyString(), userPrompt.capture());
        assertTrue(userPrompt.getValue().contains("用户问题：病区探视时间？"),
                "CS_DOC_ANSWER 的用户消息必须显式携带「用户问题：」+原问题，仅靠片段正文不保证模型看到问题");

        List<QaTrace> traces = qaTraceMapper.selectList(new LambdaQueryWrapper<QaTrace>()
                .eq(QaTrace::getQuestion, "病区探视时间？"));
        assertEquals(1, traces.size(), "DOC_QA 作答应落证据链");
        // 共享库纪律：matched_docs 的成员由 KnowledgeSearchServiceTest 成员断言覆盖；
        // 此处只锁管道——检索确实发生且证据链落了锚点（库内其他已发布文档也可能进锚点列表）
        assertFalse(traces.get(0).getMatchedDocs().isBlank(),
                "DOC_QA 作答的 matched_docs 应落检索锚点");
        assertEquals("LLM", traces.get(0).getAnswerSource());
        assertEquals("CS", traces.get(0).getScene());
    }
}
