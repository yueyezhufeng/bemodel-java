package com.bemodel.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 知识双轨配置：切分参数与本地向量化端点（Ollama 未配置 = 向量路整体关闭，纯关键词照常） */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {

    private final Chunk chunk = new Chunk();
    private final Embedding embedding = new Embedding();

    @Data
    public static class Chunk {
        /** 片段目标字数 */
        private int size = 500;
        /** 相邻片段重叠字数 */
        private int overlap = 50;
    }

    @Data
    public static class Embedding {
        /** 本地向量化端点（OpenAI 兼容 /v1/embeddings）；空 = 关闭向量路 */
        private String baseUrl = "http://localhost:11434/v1";
        private String model = "bge-m3";
        /** 单次调用超时（毫秒）：超时/失败一律降级，不拖垮摄取与检索主流程 */
        private long timeoutMs = 3000;
        /** 本地 Ollama 默认无鉴权；留空则不带 Authorization 头 */
        private String apiKey = "";
    }
}
