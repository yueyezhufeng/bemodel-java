package com.bemodel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 无 Key 秒败不落审计行（Plan 9）：测试 JVM 钉空 key 后不再向共享 dev 库刷零耗时失败行；降级语义不变 */
class DeepSeekClientNoKeyTest {

    @Test
    void 无Key调用照常降级且零审计行() {
        LlmLogService logService = Mockito.mock(LlmLogService.class);
        DeepSeekClient client = new DeepSeekClient(new DeepSeekProperties(), new ObjectMapper(), logService);

        Optional<String> r = client.chat("CS_REPLY", "系统提示", "用户问句");

        assertTrue(r.isEmpty(), "无 Key 照常返回空，上层降级语义不变");
        verify(logService, never()).log(anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyBoolean(), any());
    }
}
