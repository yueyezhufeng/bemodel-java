package com.bemodel.search;

import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.MetricMapper;
import com.bemodel.ontology.mapper.TermMapper;
import com.bemodel.ontology.service.MissService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义搜索：自然语言查业务口径。
 * 检索策略：名称反向包含（如指标名"出院人数"被问题"出院人数怎么算"包含）+ 定义n-gram命中；
 * LLM 可用时把召回结果组织成自然语言答案，不可用时直接返回结构化命中。
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private final TermMapper termMapper;
    private final ConceptMapper conceptMapper;
    private final MetricMapper metricMapper;
    private final DeepSeekClient deepSeekClient;
    private final MissService missService;
    private final com.bemodel.knowledge.KnowledgeSearchService knowledgeSearchService;

    public Map<String, Object> search(String q) {
        return search(q, true);
    }

    /** recordMiss=false 用于问数场景口径卡前置试探：未命中还会继续进语义层，不应记词表外缺口 */
    public Map<String, Object> search(String q, boolean recordMiss) {
        String query = q == null ? "" : q.trim();
        List<Map<String, Object>> hits = new ArrayList<>();
        // 仅废弃概念命中时不回流 miss：词有归宿（已废弃），回流会诱导重建、对抗废弃决定
        boolean deprecatedMatch = false;

        for (Term t : termMapper.selectList(null)) {
            if (nameMatch(query, t.getTerm())) {
                Concept c = conceptMapper.selectById(conceptIdByCode(t.getConceptCode()));
                if (c != null && "DEPRECATED".equals(c.getStatus())) {
                    deprecatedMatch = true;
                    continue;
                }
                hits.add(Map.of("type", "术语", "title", t.getTerm() + "（" + t.getSourceProduct() + "）",
                        "conceptCode", t.getConceptCode(),
                        "content", "标准概念：" + t.getConceptCode()
                                + (c == null ? "" : "，定义：" + (c.getDefinition() == null ? "无" : c.getDefinition()))));
            }
        }
        for (Concept c : conceptMapper.selectList(null)) {
            if (nameMatch(query, c.getName()) || defMatch(query, c.getDefinition())) {
                // 废弃概念隔离（维护治理加固 H3）：不再作为口径命中输出，避免废弃口径继续作答
                if ("DEPRECATED".equals(c.getStatus())) {
                    deprecatedMatch = true;
                    continue;
                }
                hits.add(Map.of("type", "概念", "title", c.getName() + "（" + c.getCode() + "）",
                        "conceptCode", c.getCode(),
                        "content", c.getDefinition() == null ? "" : c.getDefinition()));
            }
        }
        for (Metric m : metricMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Metric>().orderByAsc(Metric::getId))) {
            if (nameMatch(query, m.getName()) || defMatch(query, m.getDefinition())) {
                hits.add(Map.of("type", "指标",
                        "title", m.getName() + "（负责人：" + (m.getOwner() == null ? "-" : m.getOwner()) + "）",
                        "conceptCode", m.getConceptCode() == null ? "" : m.getConceptCode(),
                        "metricCode", m.getMetricCode() == null ? "" : m.getMetricCode(),
                        "name", m.getName() == null ? "" : m.getName(),
                        "content", "口径：" + m.getDefinition()
                                + " 公式：" + (m.getFormula() == null ? "-" : m.getFormula())));
            }
        }

        // 第四池（知识双轨）：制度文档片段——append 在尾部，不抢术语/概念/指标的 top 位次
        //（glossaryAnswer 取 hits.get(0) 作答）；命中计入 hits，避免误记词表外缺口
        hits.addAll(knowledgeSearchService.fulltextHits(query, 5));

        // 本体增长回路：概念维度零命中才记"词表外说法"——指标/术语命中不算 miss；
        // 仅废弃概念命中同样不算（词有归宿，只是已下架）
        if (hits.isEmpty() && recordMiss && !deprecatedMatch) {
            missService.recordMiss(query, "CONCEPT", "SEARCH");
        }

        String answer = "";
        boolean llmUsed = false;
        if (!hits.isEmpty()) {
            StringBuilder ctx = new StringBuilder("用户问题：" + query + "\n\n平台检索到的口径定义：\n");
            for (Map<String, Object> h : hits) {
                ctx.append("- [").append(h.get("type")).append("] ").append(h.get("title"))
                        .append("：").append(h.get("content")).append('\n');
            }
            ctx.append("\n请基于以上内容用中文简洁回答用户问题（不超过200字）。术语/概念/指标是口径定义，"
                    + "《文档》条目是制度条文出处；定义不足以回答的部分请说明缺口，制度条文里没有的内容不要编。");
            Optional<String> llm = deepSeekClient.chat("SEARCH_ANSWER",
                    "你是医疗本体平台的口径解答助手，严格基于给定定义回答。", ctx.toString());
            if (llm.isPresent()) {
                answer = llm.get();
                llmUsed = true;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("llmUsed", llmUsed);
        result.put("answer", answer);
        result.put("hits", hits);
        return result;
    }

    /** 名称匹配：正向包含（短查询）或反向包含（名称是问题的子串） */
    private boolean nameMatch(String query, String name) {
        if (query.isEmpty() || name == null || name.isEmpty()) {
            return false;
        }
        return name.contains(query) || query.contains(name);
    }

    /** 定义匹配：问题的4/3字滑窗命中定义文本 */
    private boolean defMatch(String query, String definition) {
        if (query.length() < 3 || definition == null || definition.isEmpty()) {
            return false;
        }
        for (int n = 4; n >= 3; n--) {
            for (int i = 0; i + n <= query.length(); i++) {
                if (definition.contains(query.substring(i, i + n))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Long conceptIdByCode(String code) {
        Concept c = conceptMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Concept>()
                        .eq(Concept::getCode, code));
        return c == null ? -1L : c.getId();
    }
}
