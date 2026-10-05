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
 * 红线钉子:scene-ds 置空 = 不过滤 = 旧行为——物理映射段照常含旧演示库
 * (dev 库 Flyway 种子映射稳定在场)。收口后任何行为漂移在此现形。
 */
@SpringBootTest
@TestPropertySource(properties = "bemodel.semantic.scene-ds=")
class SceneOffBehaviorTest {

    private static final String PREFIX = "SCENE_OFF_";

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
    void emptySceneListShouldKeepLegacyMappingSegment() {
        when(deepSeekClient.chat(eq("CS_SEMANTIC_PLAN"), anyString(), anyString()))
                .thenReturn(Optional.of("{\"mode\":\"UNANSWERABLE\",\"reason\":\"测试桩\",\"gapType\":\"VOCABULARY\"}"));
        semanticQaService.answer(PREFIX + "会议室几点开门", true);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).chat(eq("CS_SEMANTIC_PLAN"), anyString(), captor.capture());
        String prompt = captor.getValue();
        assertTrue(prompt.contains("DS_HIS:"), "空清单时旧演示库映射照常进段(旧行为)");
        assertFalse(prompt.contains("没有已接映射"), "旧行为不输出诚实线");
    }
}
