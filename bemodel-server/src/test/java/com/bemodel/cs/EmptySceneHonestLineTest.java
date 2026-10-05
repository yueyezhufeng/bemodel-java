package com.bemodel.cs;

import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 双演示库收口·空场景(清单指向无映射数据源):物理映射段必空→诚实线必出。
 * 诚实线前提确定化:试点映射系运行时配置(dev 机已实配,默认场景段非空为常态),
 * 默认场景段空/非空随环境漂移不可作断言前提;空场景编码在任意环境两侧一致。
 */
@SpringBootTest
@TestPropertySource(properties = "bemodel.semantic.scene-ds=DS_SCENE_EMPTY")
class EmptySceneHonestLineTest {

    private static final String PREFIX = "SCENE_EMPTY_";

    @Autowired
    private SemanticQaService semanticQaService;
    @Autowired
    private OntologyMissMapper missMapper;
    @MockBean
    private DeepSeekClient deepSeekClient;

    @AfterEach
    void clean() {
        missMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OntologyMiss>()
                .like(OntologyMiss::getTerm, PREFIX).eq(OntologyMiss::getKind, "QUESTION"));
    }

    @Test
    void emptySceneShouldPrintHonestLineAndDropDemoMappings() {
        when(deepSeekClient.chat(eq("CS_SEMANTIC_PLAN"), anyString(), anyString()))
                .thenReturn(Optional.of("{\"mode\":\"UNANSWERABLE\",\"reason\":\"测试桩\",\"gapType\":\"VOCABULARY\"}"));
        semanticQaService.answer(PREFIX + "会议室几点开门", true);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).chat(eq("CS_SEMANTIC_PLAN"), anyString(), captor.capture());
        String prompt = captor.getValue();
        assertFalse(prompt.contains("DS_HIS:"), "旧演示库 DS_HIS 不得进物理映射段");
        assertFalse(prompt.contains("DS_CHARGE:"), "旧演示库 DS_CHARGE 不得进物理映射段");
        assertFalse(prompt.contains("DS_LIS:"), "旧演示库 DS_LIS 不得进物理映射段");
        assertTrue(prompt.contains("没有已接映射"), "场景内零映射应输出诚实线");
    }
}
