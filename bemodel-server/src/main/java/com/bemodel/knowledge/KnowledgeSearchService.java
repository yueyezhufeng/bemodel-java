package com.bemodel.knowledge;

import com.bemodel.knowledge.entity.DocChunk;
import com.bemodel.knowledge.entity.Document;
import com.bemodel.knowledge.mapper.DocumentMapper;
import com.bemodel.knowledge.mapper.DocChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库混合检索：关键词 FULLTEXT + 向量余弦双路召回，RRF 合并（纯确定性代码，LLM 不参与排序）。
 * 本地向量化服务不可用时向量路自动关闭，纯关键词照常出结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchService {

    private static final int RRF_K = 60;

    private final DocChunkMapper docChunkMapper;
    private final DocumentMapper documentMapper;
    private final EmbeddingClient embeddingClient;

    /** 混合检索 top limit：片段视图带文档标题（供作答引用《标题》与前端展示） */
    public List<Map<String, Object>> search(String query, int limit) {
        String booleanQ = FulltextQuery.build(query);
        List<DocChunk> keyword = booleanQ.isBlank() ? List.of() : docChunkMapper.fulltextSearch(booleanQ, 20);
        List<DocChunk> vector = vectorTop(query, 20);
        if (keyword.isEmpty() && vector.isEmpty()) {
            return List.of();
        }
        List<String> fused = Rrf.fuse(List.of(ids(keyword), ids(vector)), RRF_K, limit);
        return assemble(fused);
    }

    /** 口径页第四池：纯关键词轻检索（不走向量），hit 结构与三池对齐（type/title/conceptCode/content/docId） */
    public List<Map<String, Object>> fulltextHits(String query, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String booleanQ = FulltextQuery.build(query);
        if (booleanQ.isBlank()) {
            return out;
        }
        for (DocChunk c : docChunkMapper.fulltextSearch(booleanQ, limit)) {
            Document d = documentMapper.selectById(c.getDocumentId());
            if (d == null || !"PUBLISHED".equals(d.getStatus())) {
                continue;
            }
            String heading = c.getHeading() == null ? "" : c.getHeading();
            out.add(Map.of(
                    "type", "文档",
                    "title", "《" + d.getTitle() + "》" + (heading.isBlank() ? "" : " " + heading),
                    "conceptCode", c.getConceptCode() == null ? "" : c.getConceptCode(),
                    "content", c.getContent(),
                    "docId", String.valueOf(d.getId())));
        }
        return out;
    }

    /** 向量路：查询先向量化（EMBED_QUERY），已向量化片段 Java 余弦暴力扫 top N；失败返回空（跳过此路） */
    private List<DocChunk> vectorTop(String query, int limit) {
        if (!embeddingClient.enabled()) {
            return List.of();
        }
        List<double[]> qv = embeddingClient.embed(List.of(query == null ? "" : query.trim()), "EMBED_QUERY");
        if (qv.isEmpty()) {
            return List.of();
        }
        double[] q = qv.get(0);
        List<DocChunk> candidates = docChunkMapper.selectPublishedWithEmbedding(5000);
        List<double[]> vectors = new ArrayList<>(candidates.size());
        for (DocChunk c : candidates) {
            double[] v = CosineUtil.parse(c.getEmbedding());
            vectors.add(v);
        }
        List<Integer> order = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(
                CosineUtil.similarity(q, vectors.get(b)), CosineUtil.similarity(q, vectors.get(a))));
        List<DocChunk> top = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, order.size()); i++) {
            if (CosineUtil.similarity(q, vectors.get(order.get(i))) > 0) {
                top.add(candidates.get(order.get(i)));
            }
        }
        return top;
    }

    private List<String> ids(List<DocChunk> chunks) {
        return chunks.stream().map(c -> String.valueOf(c.getId())).toList();
    }

    /** 按 RRF 融合序组装片段视图（docId:seq 锚点由调用方拼，这里给齐 documentId 与 seq） */
    private List<Map<String, Object>> assemble(List<String> fusedIds) {
        List<Map<String, Object>> out = new ArrayList<>(fusedIds.size());
        for (String id : fusedIds) {
            DocChunk c = docChunkMapper.selectById(Long.valueOf(id));
            Document d = c == null ? null : documentMapper.selectById(c.getDocumentId());
            if (c == null || d == null || !"PUBLISHED".equals(d.getStatus())) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("documentId", String.valueOf(d.getId()));
            view.put("seq", c.getSeq());
            view.put("heading", c.getHeading() == null ? "" : c.getHeading());
            view.put("content", c.getContent());
            view.put("docTitle", d.getTitle());
            out.add(view);
        }
        return out;
    }
}
