package com.bemodel.ontology.service;

import com.bemodel.common.BizException;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.ConceptParentMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Read-only working-model visualization and queries. Never publishes or materializes drafts. */
@Service
@RequiredArgsConstructor
public class RelationQueryService {
    private final ConceptMapper conceptMapper;
    private final RelationMapper relationMapper;
    private final ConceptParentMapper parentMapper;
    private static final int MAX_DEPTH = 12;

    @Transactional(readOnly = true)
    public Map<String, Object> graph() {
        return Map.of("concepts", conceptMapper.selectList(null), "relations", relationMapper.selectList(null),
                "parents", parentMapper.selectList(null), "scope", "working", "published", false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(String relation, String concept, String mode, String direction) {
        List<Concept> concepts = conceptMapper.selectList(null);
        if (concepts.stream().noneMatch(c -> Objects.equals(c.getCode(), concept)))
            throw new BizException("概念不存在: " + concept);
        if (!Set.of("closure", "paths").contains(mode)) throw new BizException("不支持的查询类型");
        if (!Set.of("outgoing", "incoming").contains(direction)) throw new BizException("不支持的查询方向");
        if (relation == null || relation.isBlank()) throw new BizException("请选择关系");
        List<Relation> all = relationMapper.selectList(null);
        boolean transitive = all.stream().anyMatch(r -> Objects.equals(relation, r.getRelationName())
                && Integer.valueOf(1).equals(r.getIsTransitive()));
        if ("closure".equals(mode) && !transitive)
            throw new BizException("此关系未声明传递公理，请使用关系路径查询");
        if (!"*".equals(relation) && all.stream().noneMatch(r -> Objects.equals(relation, r.getRelationName())))
            throw new BizException("关系不存在: " + relation);
        Set<String> codes = new HashSet<>();
        concepts.forEach(c -> codes.add(c.getCode()));
        List<Relation> edges = all.stream().filter(r -> codes.contains(r.getFromConcept()) && codes.contains(r.getToConcept()))
                .filter(r -> "*".equals(relation) || relation.equals(r.getRelationName())).toList();
        return traverse(edges, relation, concept, mode, direction, MAX_DEPTH);
    }

    static Map<String, Object> traverse(List<Relation> edges, String relation, String concept,
                                         String mode, String direction, int maxDepth) {
        record Step(String target, String relation) {}
        record Visit(String code, List<String> path, List<String> relations) {}
        Map<String, List<Step>> adj = new TreeMap<>();
        for (Relation edge : edges) {
            String from = "incoming".equals(direction) ? edge.getToConcept() : edge.getFromConcept();
            String to = "incoming".equals(direction) ? edge.getFromConcept() : edge.getToConcept();
            adj.computeIfAbsent(from, key -> new ArrayList<>()).add(new Step(to, edge.getRelationName()));
        }
        adj.values().forEach(items -> items.sort(Comparator.comparing(Step::target).thenComparing(Step::relation)));
        Set<String> seen = new HashSet<>(Set.of(concept));
        Deque<Visit> queue = new ArrayDeque<>();
        queue.add(new Visit(concept, List.of(concept), List.of()));
        List<Map<String, Object>> reachable = new ArrayList<>();
        boolean capped = false;
        while (!queue.isEmpty()) {
            Visit current = queue.removeFirst();
            for (Step step : adj.getOrDefault(current.code(), List.of())) {
                if (seen.contains(step.target())) continue;
                if (current.relations().size() >= maxDepth) { capped = true; continue; }
                seen.add(step.target());
                List<String> path = new ArrayList<>(current.path());path.add(step.target());
                List<String> names = new ArrayList<>(current.relations());names.add(step.relation());
                reachable.add(Map.of("concept", step.target(), "depth", names.size(), "path", path, "relations", names));
                queue.add(new Visit(step.target(), path, names));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("relation", relation);result.put("concept", concept);result.put("mode", mode);
        result.put("direction", direction);result.put("scope", "working");result.put("published", false);
        result.put("count", reachable.size());result.put("reachable", reachable);result.put("capped", capped);
        result.put("maxDepth", maxDepth);result.put("semanticInference", "closure".equals(mode));
        return result;
    }
}
