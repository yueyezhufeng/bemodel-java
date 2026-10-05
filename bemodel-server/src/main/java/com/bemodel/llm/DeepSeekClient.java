package com.bemodel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeepSeek 大模型网关。所有 LLM 调用统一入口：
 * 超时可控（全局 + 按 callType 覆盖，F1-B）、异常收敛为 Optional.empty（调用方据此走规则降级），
 * 请求体带 max_tokens 封顶输出（F1-A），每次调用记录审计日志（绑定调用时的本体发布版本），
 * 无 Key 时降级不断链。
 * 借鉴 4：主备双端点路由——主路故障（5xx/超时/挂起）自动换备路，每次尝试各写一行审计（provider 列）；
 * 内存熔断跳过已知故障端点（重启复位，无定时探活）；备路未配 Key 时行为与单提供方时代逐字节一致。
 */
@Slf4j
@Component
public class DeepSeekClient {

    /** 预算预留（毫秒）：多候选时首位候选让出，给末位候选留接管余量（spec §4 勘定） */
    private static final long RESERVE_MS = 5_000L;
    /** 尝试预算低于此值直接跳过该候选（毫秒）：避免发出注定超时的请求 */
    private static final long MIN_ATTEMPT_MS = 1_000L;

    private final DeepSeekProperties props;
    /** 按 baseUrl+读超时秒数缓存 RestClient（借鉴 4 改双元键：多 host 不串实例） */
    private final ConcurrentHashMap<String, RestClient> clientsByEndpoint = new ConcurrentHashMap<>();
    private final java.net.http.HttpClient jdkClient;
    private final ObjectMapper objectMapper;
    private final LlmLogService llmLogService;
    /** 主备两路的内存熔断状态（路由槽位级：端点配置变更不复位——单用户演示平台可接受） */
    private final CircuitState primaryCircuit = new CircuitState();
    private final CircuitState backupCircuit = new CircuitState();

    public DeepSeekClient(DeepSeekProperties props, ObjectMapper objectMapper,
                          @Lazy LlmLogService llmLogService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.llmLogService = llmLogService;
        // JDK HttpClient:对非幂等 POST 默认 NEVER_RETRY。
        // 真实库试点发现(2026-09-15)：API 故障时提供方先回响应头、响应体永不结束(直接 curl 可复现)。
        // 原 SimpleClientHttpRequestFactory(HttpURLConnection)会静默重试,把 30s 读超时放大成 60~90s;
        // JDK 客户端不重试,每次调用精确超时后走降级。注意:体阶段读超时会被 RestClient 统一包装为
        // "Error while extracting response ...",字样易误导(并非类型转换失败),按 latency≈超时值 判读即可
        this.jdkClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(props.getTimeoutSeconds()))
                .build();
    }

    /** 按 callType 解析读超时：callTimeouts 覆盖优先，未配置回落全局 timeoutSeconds */
    public int timeoutFor(String callType) {
        return props.getCallTimeouts().getOrDefault(callType, props.getTimeoutSeconds());
    }

    /** 按 callType 解析输出上限：callMaxTokens 覆盖优先，未配置回落全局 maxTokens */
    public int maxTokensFor(String callType) {
        return props.getCallMaxTokens().getOrDefault(callType, props.getMaxTokens());
    }

    /** 包级可见：缓存复用语义由单测直测（同 baseUrl+秒数同实例） */
    RestClient restClientFor(ProviderEndpoint ep, int readTimeoutSeconds) {
        return clientsByEndpoint.computeIfAbsent(ep.baseUrl() + "|" + readTimeoutSeconds, key -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
            factory.setReadTimeout(java.time.Duration.ofSeconds(readTimeoutSeconds));
            return RestClient.builder()
                    .baseUrl(ep.baseUrl())
                    .requestFactory(factory)
                    .build();
        });
    }

    public boolean enabled() {
        return props.enabled();
    }

    public String model() {
        return props.getModel();
    }

    public Optional<String> chat(String callType, String systemPrompt, String userPrompt) {
        long start = System.currentTimeMillis();
        ProviderEndpoint primary = props.primaryEndpoint();
        if (!primary.hasKey()) {
            // 无 Key 是环境未配置而非调用失败：不落审计行。否则测试 JVM（surefire 钉空 key）
            // 每次 mvn test 都向共享 dev 库刷一行零耗时失败，审计成功率被拉成假低（Plan 9 根治）。
            // 对上层语义零变化：仍返回空走既有降级通道。
            log.warn("DeepSeek API Key 未配置，LLM 能力降级");
            return Optional.empty();
        }
        ProviderEndpoint backup = props.backupEndpoint(primary);
        long deadlineMs = start + timeoutFor(callType) * 1000L;
        long now = System.currentTimeMillis();
        List<ProviderEndpoint> candidates = RoutePlanner.plan(callType, primary, backup,
                props.getCallPrimaryRoute(),
                !primaryCircuit.isUsable(now),
                backup == null || !backupCircuit.isUsable(now));
        for (int i = 0; i < candidates.size(); i++) {
            // 预算分配（spec §4 勘定）：多候选时首位让出 RESERVE_MS 给末位接管；主路快速失败时
            // 备路继承全部剩余——挂起型主路故障（连接活、响应体永不回）也在 T 内被接管
            long budgetMs = deadlineMs - System.currentTimeMillis()
                    - (i == 0 && candidates.size() > 1 ? RESERVE_MS : 0);
            if (budgetMs < MIN_ATTEMPT_MS) {
                log.warn("LLM 尝试预算不足，跳过端点: callType={} endpoint={}", callType, candidates.get(i).name());
                continue;
            }
            Attempt attempt = attempt(callType, candidates.get(i), systemPrompt, userPrompt,
                    (int) (budgetMs / 1000L), start);
            CircuitState circuit = candidates.get(i) == backup ? backupCircuit : primaryCircuit;
            if (attempt.ok()) {
                circuit.recordSuccess();
                return Optional.of(attempt.content());
            }
            circuit.recordFailure(System.currentTimeMillis());
        }
        return Optional.empty();
    }

    /** 单次尝试结果：content 非空=成功，errMsg 非空=失败（互斥）。截断/缺 content 视同失败——
     * 审计 success 如实为 0，熔断计入失败（提供方未交付可用输出） */
    private record Attempt(String content, String errMsg) {
        boolean ok() {
            return errMsg == null;
        }
    }

    private Attempt attempt(String callType, ProviderEndpoint ep, String systemPrompt, String userPrompt,
                            int readTimeoutSeconds, long start) {
        String digest = digest(systemPrompt, userPrompt);
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", ep.model());
            body.put("temperature", 0.2);
            // F1-A：输出封顶——防止异常复读拖满读超时，也压住成本
            body.put("max_tokens", maxTokensFor(callType));
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            // byte[] 接收再 UTF-8 解码:正常路径与 String 等价,异常路径少一层转换器歧义
            byte[] respBytes = restClientFor(ep, readTimeoutSeconds).post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + ep.apiKey())
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(byte[].class);
            String resp = respBytes == null ? "" : new String(respBytes, StandardCharsets.UTF_8);

            // 对抗评审（F1）：输出截断（finish_reason=length）视同失败——半截 JSON 被当成功留痕
            // （success=1）会误导诊断；换下一候选或走可见降级
            if ("length".equals(finishReason(resp, objectMapper))) {
                String msg = "输出在 max_tokens 处截断（finish_reason=length）";
                log.warn("LLM 输出截断（走降级）: callType={} endpoint={}", callType, ep.name());
                llmLogService.log(callType, ep.model(), ep.name(), digest,
                        System.currentTimeMillis() - start, false, msg);
                return new Attempt(null, msg);
            }
            String content = objectMapper.readTree(resp)
                    .path("choices").path(0).path("message").path("content").asText(null);
            llmLogService.log(callType, ep.model(), ep.name(), digest,
                    System.currentTimeMillis() - start, content != null,
                    content == null ? "响应缺 content 字段" : null);
            return content == null ? new Attempt(null, "响应缺 content 字段") : new Attempt(content, null);
        } catch (Exception e) {
            log.warn("DeepSeek 调用失败（换路或走降级）: endpoint={} {}", ep.name(), e.getMessage());
            llmLogService.log(callType, ep.model(), ep.name(), digest,
                    System.currentTimeMillis() - start, false, e.getMessage());
            return new Attempt(null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** 路由健康视图（GET /api/llm/routes 消费）：host 脱敏只回主机名；healthy=有 Key 且熔断未开路 */
    public List<Map<String, Object>> routes() {
        long now = System.currentTimeMillis();
        ProviderEndpoint primary = props.primaryEndpoint();
        ProviderEndpoint backup = props.backupEndpoint(primary);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(routeView(primary, primaryCircuit.isUsable(now), true));
        if (backup != null) {
            out.add(routeView(backup, backupCircuit.isUsable(now),
                    props.getCallPrimaryRoute().containsValue("backup")));
        }
        return out;
    }

    private Map<String, Object> routeView(ProviderEndpoint ep, boolean usable, boolean inUse) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", ep.name());
        view.put("model", ep.model());
        view.put("host", URI.create(ep.baseUrl()).getHost());
        view.put("healthy", ep.hasKey() && usable);
        view.put("inUse", inUse);
        return view;
    }

    /** 包级可见供测试：finish_reason 提取口径与 chat() 一致（解析失败按空串=未截断处理） */
    static String finishReason(String resp, ObjectMapper om) {
        try {
            return om.readTree(resp).path("choices").path(0).path("finish_reason").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String digest(String systemPrompt, String userPrompt) {
        String s = (systemPrompt == null ? "" : systemPrompt) + " | "
                + (userPrompt == null ? "" : userPrompt);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
