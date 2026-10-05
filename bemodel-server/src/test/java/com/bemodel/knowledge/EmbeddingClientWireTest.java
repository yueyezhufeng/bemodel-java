package com.bemodel.knowledge;

import com.bemodel.llm.LlmLogService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 本地向量化客户端 wire 测试：批量成功/服务端错误/连接拒绝/超时四夹具，全部降级不抛 */
class EmbeddingClientWireTest {

    private HttpServer server;
    private LlmLogService llmLog;

    @BeforeEach
    void setUp() {
        llmLog = mock(LlmLogService.class);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private KnowledgeProperties props(String baseUrl, long timeoutMs) {
        KnowledgeProperties p = new KnowledgeProperties();
        p.getEmbedding().setBaseUrl(baseUrl);
        p.getEmbedding().setModel("bge-m3");
        p.getEmbedding().setTimeoutMs(timeoutMs);
        return p;
    }

    /** 起一个本地假端点：handler 收到 POST 即按给定状态码与响应体回包 */
    private String startServer(int status, String body, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void batchEmbedShouldReturnVectorsInOrder() throws IOException {
        String url = startServer(200, "{\"data\":[{\"index\":1,\"embedding\":[0.4,0.5]},"
                + "{\"index\":0,\"embedding\":[0.1,0.2]}]}", 0);
        EmbeddingClient client = new EmbeddingClient(props(url, 3000), llmLog);

        List<double[]> vectors = client.embed(List.of("第一段文本", "第二段文本"), "EMBED_INGEST");

        assertEquals(2, vectors.size());
        assertEquals(0.1, vectors.get(0)[0], 1e-9, "应按 index 归位");
        assertEquals(0.4, vectors.get(1)[0], 1e-9);
        verify(llmLog, atLeastOnce()).log(anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyBoolean(), anyString());
    }

    @Test
    void serverErrorShouldReturnEmptyAndLogFailure() throws IOException {
        String url = startServer(500, "{\"error\":\"boom\"}", 0);
        EmbeddingClient client = new EmbeddingClient(props(url, 3000), llmLog);

        List<double[]> vectors = client.embed(List.of("文本"), "EMBED_QUERY");

        assertTrue(vectors.isEmpty(), "非 200 应降级为空列表");
        verify(llmLog, atLeastOnce()).log(anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyBoolean(), anyString());
    }

    @Test
    void connectionRefusedShouldReturnEmpty() {
        // 端口 1（tcpmux）几乎必然无监听——连接拒绝形态与「本地文档向量服务未启动」一致
        EmbeddingClient client = new EmbeddingClient(props("http://localhost:1/v1", 1000), llmLog);

        assertTrue(client.embed(List.of("文本"), "EMBED_QUERY").isEmpty());
    }

    @Test
    void timeoutShouldReturnEmptyQuickly() throws IOException {
        String url = startServer(200, "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}", 3000);
        EmbeddingClient client = new EmbeddingClient(props(url, 300), llmLog);

        long t0 = System.currentTimeMillis();
        List<double[]> vectors = client.embed(List.of("文本"), "EMBED_QUERY");

        assertTrue(vectors.isEmpty());
        assertTrue(System.currentTimeMillis() - t0 < 2500, "超时应按 timeoutMs 收口而非等满服务端 3s");
    }
}
