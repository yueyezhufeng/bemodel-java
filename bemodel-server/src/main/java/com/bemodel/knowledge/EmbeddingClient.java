package com.bemodel.knowledge;

import com.bemodel.llm.LlmLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地向量化客户端（OpenAI 兼容 /v1/embeddings，如 Ollama + bge-m3）。
 * 红线：任何失败（未配置/连接拒/超时/非 200/解析炸）一律返回空列表，调用方降级跳过向量路；
 * 每次调用落 bm_llm_log 审计行（provider=ollama，callType=EMBED_INGEST/EMBED_QUERY）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingClient {

    private static final String PROVIDER = "ollama";

    private final KnowledgeProperties props;
    private final LlmLogService llmLogService;
    private volatile RestClient restClient;
    private volatile String restClientBaseUrl;

    /** baseUrl 空 = 向量路整体关闭（application.yml 默认指向本地，env 可覆写为空关闭） */
    public boolean enabled() {
        return StringUtils.hasText(props.getEmbedding().getBaseUrl());
    }

    /** 批量向量化：返回与入参同序的向量列表；任何失败返回空列表（调用方降级），审计行照写 */
    public List<double[]> embed(List<String> texts, String callType) {
        if (!enabled() || texts == null || texts.isEmpty()) {
            return List.of();
        }
        String model = props.getEmbedding().getModel();
        String digest = "批量" + texts.size() + "条，首条：" + texts.get(0);
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("input", texts);
            EmbeddingResponse body = restClient().post()
                    .uri("/embeddings")
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        String apiKey = props.getEmbedding().getApiKey();
                        if (StringUtils.hasText(apiKey)) {
                            h.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(EmbeddingResponse.class);
            long cost = System.currentTimeMillis() - t0;
            if (body == null || body.data() == null || body.data().isEmpty()) {
                llmLogService.log(callType, model, PROVIDER, digest, cost, false, "响应缺 data 字段");
                return List.of();
            }
            List<Item> sorted = new ArrayList<>(body.data());
            sorted.sort(Comparator.comparingInt(Item::index));
            List<double[]> vectors = new ArrayList<>(sorted.size());
            for (Item item : sorted) {
                List<Double> e = item.embedding();
                if (e == null || e.isEmpty()) {
                    llmLogService.log(callType, model, PROVIDER, digest, cost, false, "响应缺向量");
                    return List.of();
                }
                double[] v = new double[e.size()];
                for (int i = 0; i < e.size(); i++) {
                    v[i] = e.get(i);
                }
                vectors.add(v);
            }
            // 成功行 errMsg 传空串而非 null：与 wire 测试 anyString() 匹配器兼容（Mockito anyString 不匹配 null）
            llmLogService.log(callType, model, PROVIDER, digest, cost, true, "");
            return vectors;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - t0;
            log.warn("文档向量服务不可用（降级为纯关键词检索，不影响主流程）: {}", e.getMessage());
            llmLogService.log(callType, model, PROVIDER, digest, cost, false, e.getMessage());
            return List.of();
        }
    }

    /** 懒建 RestClient：超时来自配置；baseUrl 变化（仅测试场景）时重建 */
    private RestClient restClient() {
        String baseUrl = props.getEmbedding().getBaseUrl();
        if (restClient == null || !baseUrl.equals(restClientBaseUrl)) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) props.getEmbedding().getTimeoutMs());
            factory.setReadTimeout((int) props.getEmbedding().getTimeoutMs());
            restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
            restClientBaseUrl = baseUrl;
        }
        return restClient;
    }

    /** OpenAI 兼容响应体：data[].index + data[].embedding */
    record EmbeddingResponse(List<Item> data) {
    }

    record Item(int index, List<Double> embedding) {
    }
}
