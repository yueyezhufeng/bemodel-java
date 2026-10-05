package com.bemodel.cs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.common.Masking;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.entity.PhysicalTable;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.datasource.mapper.PhysicalTableMapper;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.cs.QaTrace;
import com.bemodel.cs.mapper.QaTraceMapper;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import com.bemodel.ontology.service.MetricService;
import com.bemodel.ontology.service.MissService;
import com.bemodel.ontology.service.ReconcileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 语义层驱动的开放问答（Ontology2SQL）：LLM 基于本体+映射生成查询计划，
 * SQL 经表白名单/列白名单/只读校验后在真实业务库执行，再由 LLM 把结果组织成自然语言。
 * 原则：LLM 只表达不编数——数字必须来自真实查询；任一环节失败返回 null 落能力菜单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticQaService {

    private final ConceptMapper conceptMapper;
    private final AttributeMapper attributeMapper;
    private final RelationMapper relationMapper;
    private final MappingMapper mappingMapper;
    private final PhysicalTableMapper physicalTableMapper;
    private final DatasourceService datasourceService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;
    private final MissService missService;
    private final QaTraceMapper qaTraceMapper;
    private final MetricService metricService;
    private final ReconcileService reconcileService;
    private final SemanticSceneProperties sceneProps;

    /** 查询计划:LLM 产出的结构化意图(QUERY 查数 / MODEL_ANSWER 模型规则判断 / UNANSWERABLE / METRIC 在册口径实测) */
    record Plan(String mode, String ds, String sql, String semantics, String reason,
                String conclusion, String verifySql, String gapType, List<String> metrics) {
    }

    /**
     * 问答结果：result 为 null 表示降级落能力菜单；recordedMiss 标记本次是否已回流增长回路；
     * gapType/reason 仅 UNANSWERABLE 且 LLM 分型为 A 型歧义（AMBIGUITY）时非空——
     * 该次不记 miss（歧义尚未定性），由调用方决定建澄清任务还是（续跑封顶后）回流。
     */
    public record Outcome(Map<String, Object> result, boolean recordedMiss, String gapType, String reason) {
        /** 兼容既有调用：非澄清路径的Outcome */
        public Outcome(Map<String, Object> result, boolean recordedMiss) {
            this(result, recordedMiss, null, null);
        }
    }

    /**
     * 开放问答主流程：计划 → 校验 → 执行 → 作答。
     * UNANSWERABLE / SQL 校验失败 / 执行失败会把问题原文回流本体增长回路（kind=QUESTION）；
     * 计划解析失败/LLM 不可用不记（非语义层缺口）。
     */
    public Outcome answer(String q) {
        return answer(q, false);
    }

    /** analytics=true 表示来自智能问数场景（缺口来源记 QA_ASK，与客服 CS_ASK 区分） */
    public Outcome answer(String q, boolean analytics) {
        List<Metric> candidates = prefilterMetricCards(q, probedInScene());
        Plan plan = planQuery(q, candidates);
        if (plan == null) {
            return new Outcome(null, false);
        }
        if ("UNANSWERABLE".equals(plan.mode())) {
            if (analytics && "AMBIGUITY".equals(plan.gapType())) {
                // A 型歧义：不记 miss（问题可能被答出，尚未定性为缺口），由调用方建澄清任务续跑
                return new Outcome(null, false, "AMBIGUITY", plan.reason());
            }
            recordQuestionMiss(q, analytics);
            return new Outcome(null, true);
        }
        if ("MODEL_ANSWER".equals(plan.mode())) {
            return new Outcome(modelAnswer(q, plan), false);
        }
        if ("METRIC".equals(plan.mode())) {
            return new Outcome(metricAnswer(q, plan, analytics), false);
        }
        if (!"QUERY".equals(plan.mode()) || plan.ds() == null || plan.sql().isBlank()) {
            return new Outcome(null, false);
        }
        Set<String> tables = allowedTables(plan.ds());
        Set<String> columns = allowedColumns(plan.ds());
        if (tables.isEmpty()) {
            recordQuestionMiss(q, analytics);
            return new Outcome(null, true);
        }
        String sql;
        try {
            sql = validateSql(plan.sql(), tables, columns);
        } catch (BizException e) {
            log.warn("语义查询 SQL 校验未过（降级能力菜单）: {}", e.getMessage());
            recordQuestionMiss(q, analytics);
            return new Outcome(null, true);
        }
        List<Map<String, Object>> rows;
        long start = System.currentTimeMillis();
        try {
            // 不污染共享 JdbcTemplate：基于同一 DataSource 包一层带超时/行数上限的执行器
            JdbcTemplate jdbc = new JdbcTemplate(datasourceService.jdbc(plan.ds()).getDataSource());
            jdbc.setQueryTimeout(15);
            jdbc.setMaxRows(100);
            rows = jdbc.queryForList(sql);
        } catch (Exception e) {
            log.warn("语义查询执行失败（降级能力菜单）: {}", e.getMessage());
            recordQuestionMiss(q, analytics);
            return new Outcome(null, true);
        }
        long execMs = System.currentTimeMillis() - start;
        int total = rows.size();
        // 展示行与 LLM 出域上下文同源：先脱敏再共用（rows 本身不再返回）
        List<Map<String, Object>> view = Masking.maskPatientRows(rows.size() > 20 ? rows.subList(0, 20) : rows);
        java.util.Optional<String> llmAnswer = composeAnswer(q, plan, sql, view, total);
        String answer = llmAnswer.orElseGet(() -> templateAnswer(plan, total, view));
        // 诚实标注作答来源（LLM/模板）——证据链必答「这个结论谁产的」
        String answerSource = llmAnswer.isPresent() ? "LLM" : "TEMPLATE";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", q);
        result.put("intent", "语义查询");
        result.put("router", "SEMANTIC");
        result.put("answer", answer);
        List<String> usedTables = tables.stream()
                .filter(t -> Pattern.compile("(?i)\\b" + Pattern.quote(t) + "\\b").matcher(sql).find())
                .toList();
        result.put("evidence", List.of(
                Map.of("label", "执行SQL", "value", sql),
                Map.of("label", "数据源", "value", plan.ds() + " / " + String.join(",", usedTables)),
                Map.of("label", "结果行数", "value", total + (total > 20 ? "（展示前20行）" : ""))));
        // 结构化解析（智能问数侧栏）：概念匹配/关系链来自本体真实结构，行数据来自真实查询，不编造置信度
        result.put("semantics", plan.semantics());
        result.put("rows", view);
        fillParse(result, q + " " + plan.semantics());
        // D3 证据链落库：问题→计划→校验→执行→作答 五段证据持久化（/trace 页逐段还原，锚点全平台内可达）
        String traceId = persistQaTrace(analytics, q, plan, sql, usedTables,
                conceptCodes(result.get("matchedConcepts")), total, answer, answerSource, (int) execMs);
        List<Map<String, String>> links = new ArrayList<>(conceptLinks(plan.ds(), usedTables));
        if (traceId != null) {
            result.put("traceId", traceId);
            links.add(Map.of("label", "查证据链", "route", "/trace?type=QA&key=" + traceId));
        }
        result.put("links", links);
        return new Outcome(result, false);
    }

    /** 往结果里补 概念匹配 + 关系推理链（问题与语义计划文本中真实命中的本体结构） */
    private void fillParse(Map<String, Object> result, String text) {
        try {
            List<Map<String, Object>> matched = matchConcepts(text);
            result.put("matchedConcepts", matched);
            result.put("relations", relatedRelations(matched.stream()
                    .map(m -> String.valueOf(m.get("code"))).toList()));
        } catch (Exception e) {
            log.warn("解析字段构建失败（不影响主回答）: {}", e.getMessage());
            result.put("matchedConcepts", List.of());
            result.put("relations", List.of());
        }
    }

    /** 问题/语义文本中命中的 PUBLISHED 概念：中文名(≥2字)包含或编码包含 */
    private List<Map<String, Object>> matchConcepts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase();
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Concept c : conceptMapper.selectList(
                new LambdaQueryWrapper<Concept>().eq(Concept::getStatus, "PUBLISHED"))) {
            String how = null;
            if (c.getName() != null && c.getName().length() >= 2 && text.contains(c.getName())) {
                how = "名称";
            } else if (c.getCode() != null && c.getCode().length() >= 2
                    && lower.contains(c.getCode().toLowerCase())) {
                how = "编码";
            }
            if (how != null) {
                hits.add(Map.of("code", c.getCode(), "name", c.getName() == null ? c.getCode() : c.getName(),
                        "match", how));
            }
            if (hits.size() >= 6) {
                break;
            }
        }
        return hits;
    }

    /** 命中概念相关的关系（作为推理链展示），限 8 条 */
    private List<Map<String, Object>> relatedRelations(List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        List<Relation> rels = relationMapper.selectList(new LambdaQueryWrapper<Relation>()
                .in(Relation::getFromConcept, codes).or().in(Relation::getToConcept, codes));
        Map<String, String> conceptName = new LinkedHashMap<>();
        conceptMapper.selectList(new LambdaQueryWrapper<Concept>().eq(Concept::getStatus, "PUBLISHED"))
                .forEach(c -> conceptName.put(c.getCode(), c.getName()));
        return rels.stream().limit(8).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", r.getFromConcept());
            m.put("fromName", conceptName.getOrDefault(r.getFromConcept(), r.getFromConcept()));
            m.put("relation", r.getRelationName());
            m.put("to", r.getToConcept());
            m.put("toName", conceptName.getOrDefault(r.getToConcept(), r.getToConcept()));
            return m;
        }).toList();
    }

    /** 答不了的问题回流本体增长回路（kind=QUESTION，source 按提问场景区分 CS_ASK/QA_ASK，静默降级在 recordMiss 内部） */
    private void recordQuestionMiss(String q, boolean analytics) {
        missService.recordMiss(q, "QUESTION", analytics ? "QA_ASK" : "CS_ASK");
    }

    /** D3：语义查询成功路径的证据链落库（仅 QUERY 成功记；落库失败静默降级，不影响主回答） */
    private String persistQaTrace(boolean analytics, String q, Plan plan, String sql, List<String> usedTables,
                                  String matchedConcepts, int total, String answer, String answerSource, int durationMs) {
        try {
            String traceId = "QA-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
            QaTrace t = new QaTrace();
            t.setTraceId(traceId);
            t.setScene(analytics ? "ANALYTICS" : "CS");
            t.setQuestion(q.length() > 512 ? q.substring(0, 512) : q);
            t.setDsCode(plan.ds());
            t.setUsedTables(truncate(String.join(",", usedTables), 256));
            t.setSqlText(truncate(sql, 1024));
            t.setSemantics(truncate(plan.semantics(), 512));
            t.setMatchedConcepts(truncate(matchedConcepts, 256));
            t.setRowCount(total);
            t.setAnswer(answer);
            t.setAnswerSource(answerSource);
            t.setPlanMode("METRIC".equals(plan.mode()) ? "METRIC" : "QUERY");
            t.setDurationMs(durationMs);
            t.setCreatedAt(java.time.LocalDateTime.now());
            qaTraceMapper.insert(t);
            return traceId;
        } catch (Exception e) {
            log.warn("问数证据链落库失败（静默降级，不影响主回答）: {}", e.getMessage());
            return null;
        }
    }

    /** fillParse 产物提取命中概念 code（逗号分隔，证据链锚点用），去重限 16 个 */
    private static String conceptCodes(Object matched) {
        if (!(matched instanceof List<?> list)) {
            return "";
        }
        return list.stream()
                .filter(m -> m instanceof Map<?, ?> mp && mp.get("code") != null)
                .map(m -> String.valueOf(((Map<?, ?>) m).get("code")))
                .distinct()
                .limit(16)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 模型作答：「可不可以/能不能」类业务规则问题，从本体结构推理结论，
     * verifySql 探针可选——校验或执行失败只丢探针，保留结构结论。
     */
    private Map<String, Object> modelAnswer(String q, Plan plan) {
        if (plan.conclusion() == null || plan.conclusion().isBlank()) {
            return null;
        }
        String probeSql = null;
        List<Map<String, Object>> probeRows = null;
        Set<String> tables = plan.ds() == null ? Set.of() : allowedTables(plan.ds());
        if (plan.verifySql() != null && !plan.verifySql().isBlank() && !tables.isEmpty()) {
            try {
                probeSql = validateSql(plan.verifySql(), tables, allowedColumns(plan.ds()));
                JdbcTemplate jdbc = new JdbcTemplate(datasourceService.jdbc(plan.ds()).getDataSource());
                jdbc.setQueryTimeout(15);
                jdbc.setMaxRows(100);
                probeRows = jdbc.queryForList(probeSql);
            } catch (Exception e) {
                log.warn("模型作答探针失败（丢弃探针保留结构结论）: {}", e.getMessage());
                probeSql = null;
                probeRows = null;
            }
        }

        final List<Map<String, Object>> probeFinal = probeRows;
        String answer = composeModelAnswer(q, plan, probeSql, probeRows).orElseGet(() -> {
            String base = plan.conclusion() + "（结构依据：" + plan.semantics() + "）";
            return probeFinal == null ? base
                    : base + " 验证探针返回：" + probeFinal.get(0);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", q);
        result.put("intent", "语义查询");
        result.put("router", "SEMANTIC");
        result.put("answer", answer);
        List<Map<String, String>> evidence = new ArrayList<>();
        evidence.add(Map.of("label", "结构依据", "value", plan.semantics()));
        if (probeSql != null) {
            evidence.add(Map.of("label", "执行SQL", "value", probeSql));
            evidence.add(Map.of("label", "结果行数", "value",
                    String.valueOf(probeRows.size()) + (probeRows.isEmpty() ? "" : "，首行 " + probeRows.get(0))));
        }
        result.put("evidence", evidence);
        result.put("links", conceptLinksFromText(plan.semantics() + " " + plan.conclusion()));
        return result;
    }

    /** 模型作答的 LLM 组织：结构判断 + 探针结果一起给，产出人话；失败由调用方回落模板 */
    private Optional<String> composeModelAnswer(String q, Plan plan, String probeSql,
                                                List<Map<String, Object>> probeRows) {
        String probeText;
        try {
            probeText = probeSql == null ? "无（纯结构判断）"
                    : probeSql + " → " + objectMapper.writeValueAsString(Masking.maskPatientRows(probeRows));
        } catch (Exception e) {
            probeText = "无（纯结构判断）";
        }
        return deepSeekClient.chat("CS_SEMANTIC_ANSWER",
                "你是医院信息平台的业务规则解答助手。基于给定的本体结构判断与验证探针结果用中文回答，"
                        + "先给「可以/不可以」的结论，再给依据（结构口径+探针数字），口语化，不超过200字。不要编造探针没有的数字。",
                "用户问题：" + q + "\n本体结构判断：" + plan.semantics()
                        + "\n初步结论：" + plan.conclusion() + "\n验证探针：" + probeText);
    }

    // ---------- 1. 语义上下文 ----------

    /** 给 LLM 的紧凑语义上下文：PUBLISHED 概念及属性 + confirmed 映射（按数据源分组）+ 概念关系 */
    String buildSemanticContext() {
        List<Concept> concepts = conceptMapper.selectList(
                new LambdaQueryWrapper<Concept>().eq(Concept::getStatus, "PUBLISHED"));
        Map<String, String> conceptName = new LinkedHashMap<>();
        concepts.forEach(c -> conceptName.put(c.getCode(), c.getName()));
        List<Attribute> attrs = attributeMapper.selectList(null);
        Map<String, String> attrName = new LinkedHashMap<>();
        attrs.forEach(a -> attrName.put(a.getConceptCode() + "." + a.getAttrCode(), a.getAttrName()));

        StringBuilder sb = new StringBuilder("【本体概念】\n");
        for (Concept c : concepts) {
            List<String> as = attrs.stream()
                    .filter(a -> a.getConceptCode().equals(c.getCode()))
                    .map(a -> a.getAttrName() + "/" + a.getDataType())
                    .toList();
            sb.append(c.getCode()).append('(').append(c.getName()).append(')')
                    .append(as.isEmpty() ? "" : ": " + String.join(", ", as)).append('\n');
        }

        List<PhysicalTable> tables = physicalTableMapper.selectList(null);
        Map<String, String> tableComment = new LinkedHashMap<>();
        tables.forEach(t -> tableComment.put(t.getDsCode() + "." + t.getTableName(),
                t.getTableComment() == null ? "" : t.getTableComment()));
        List<Mapping> mappings = mappingMapper.selectList(
                        new LambdaQueryWrapper<Mapping>().eq(Mapping::getStatus, "ACTIVE")).stream() // 生命周期（V30）：仅生效映射进语义上下文
                .filter(m -> sceneProps.inScene(m.getDsCode())) // 双演示库收口：物理映射段只认场景数据源
                .toList();
        Map<String, Map<String, List<Mapping>>> byDsTable = new TreeMap<>();
        for (Mapping m : mappings) {
            byDsTable.computeIfAbsent(m.getDsCode(), k -> new TreeMap<>())
                    .computeIfAbsent(m.getTableName(), k -> new ArrayList<>()).add(m);
        }
        sb.append("\n【物理映射】（概念.属性 = 数据源.表.列，枚举列为原始码=中文）\n");
        if (byDsTable.isEmpty()) {
            // 场景内零映射：诚实告知查数不可答并指回规则 8——不留空段让 LLM 自由发挥
            sb.append("（当前数据源清单内没有已接映射——查数类问题回答不了，按规则 8 返回 UNANSWERABLE，严禁编造数据源或表名。）\n");
        }
        for (Map.Entry<String, Map<String, List<Mapping>>> ds : byDsTable.entrySet()) {
            sb.append(ds.getKey()).append(":\n");
            for (Map.Entry<String, List<Mapping>> t : ds.getValue().entrySet()) {
                String comment = tableComment.get(ds.getKey() + "." + t.getKey());
                sb.append("  ").append(t.getKey())
                        .append(comment == null || comment.isEmpty() ? "" : "（" + comment + "）").append(": ");
                List<String> cols = new ArrayList<>();
                for (Mapping m : t.getValue()) {
                    StringBuilder col = new StringBuilder(m.getColumnName()).append('=')
                            .append(m.getConceptCode()).append('.')
                            .append(attrName.getOrDefault(
                                    m.getConceptCode() + "." + m.getAttrCode(), m.getAttrCode()));
                    if (m.getValueMap() != null && !m.getValueMap().isBlank()) {
                        col.append('{').append(m.getValueMap()
                                        .replace("{", "").replace("}", "").replace("\"", ""))
                                .append('}');
                    }
                    cols.add(col.toString());
                }
                sb.append(String.join(", ", cols)).append('\n');
            }
        }

        List<Relation> relations = relationMapper.selectList(null);
        if (!relations.isEmpty()) {
            sb.append("\n【概念关系】\n");
            for (Relation r : relations) {
                sb.append(r.getFromConcept()).append('(').append(conceptName.getOrDefault(r.getFromConcept(), ""))
                        .append(")—").append(r.getRelationName()).append("→")
                        .append(r.getToConcept()).append('(')
                        .append(conceptName.getOrDefault(r.getToConcept(), "")).append(")\n");
            }
        }
        return sb.toString();
    }

    // ---------- 2. 查询计划（LLM call 1） ----------

    private Plan planQuery(String q, List<Metric> candidates) {
        Optional<String> r = deepSeekClient.chat("CS_SEMANTIC_PLAN",
                "你是医疗信息平台的本体语义查询规划器，把自然语言问题编译为只读 SQL。只返回JSON，不要多余文字。",
                buildPlanPrompt(q, candidates));
        if (r.isEmpty()) {
            return null;
        }
        try {
            String resp = r.get();
            int start = resp.indexOf('{');
            int end = resp.lastIndexOf('}');
            JsonNode node = objectMapper.readTree(start >= 0 && end > start ? resp.substring(start, end + 1) : resp);
            String mode = node.path("mode").asText("");
            if ("UNANSWERABLE".equals(mode)) {
                log.info("语义查询不可答: {}", node.path("reason").asText(""));
                return new Plan(mode, null, null, null, node.path("reason").asText(""), null, null, gapType(node), null);
            }
            if ("MODEL_ANSWER".equals(mode)) {
                String conclusion = node.path("conclusion").asText("").trim();
                if (conclusion.isEmpty()) {
                    return null;
                }
                return new Plan(mode, node.path("ds").asText("").trim(),
                        null, node.path("semantics").asText(""), null,
                        conclusion, node.path("verifySql").asText("").trim(), null, null);
            }
            if ("METRIC".equals(mode)) {
                List<String> codes = new ArrayList<>();
                node.path("metrics").forEach(n -> codes.add(n.asText("").trim()));
                List<String> valid = codes.stream()
                        .filter(c -> !c.isEmpty())
                        .filter(c -> candidates.stream().anyMatch(m -> m.getMetricCode().equals(c)))
                        .toList();
                if (valid.isEmpty()) {
                    return null;
                }
                return new Plan("METRIC", null, null, null, null, null, null, null, valid);
            }
            if (!"QUERY".equals(mode)) {
                return null;
            }
            String ds = node.path("ds").asText("").trim();
            String sql = node.path("sql").asText("").trim();
            if (ds.isEmpty() || sql.isEmpty()) {
                return null;
            }
            return new Plan(mode, ds, sql, node.path("semantics").asText(""), null, null, null, null, null);
        } catch (Exception e) {
            log.warn("语义查询计划解析失败（降级）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 缺口分型（D2b）：取计划阶段 LLM 结构化输出的 gapType，不在事后对 reason 做关键词嗅探。
     * 降级规则：未输出/值非法 → 一律按 VOCABULARY（= 现状行为，回流 bm_ontology_miss）。
     */
    static String gapType(JsonNode node) {
        String g = node.path("gapType").asText("").trim().toUpperCase();
        return "AMBIGUITY".equals(g) || "VOCABULARY".equals(g) ? g : "VOCABULARY";
    }

    /** 问题与指标名/定义的 CJK 二元组(窗口式,零分词依赖) */
    static Set<String> cjkBigrams(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) {
            return out;
        }
        String cjk = s.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i + 1 < cjk.length(); i++) {
            out.add(cjk.substring(i, i + 2));
        }
        return out;
    }

    /**
     * 口径卡预筛(纯规则,零 LLM):重叠 >=2 视为相关,上限 5 张;零入围返回空表。
     * 粗筛有意放宽——带范围限定的问题也会入围,由 LLM 判定是否恰是口径卡所指。
     */
    static List<Metric> prefilterMetricCards(String q, List<Metric> probed) {
        Set<String> qb = cjkBigrams(q);
        if (qb.isEmpty()) {
            return List.of();
        }
        List<Metric> out = new ArrayList<>();
        for (Metric m : probed) {
            String text = (m.getName() == null ? "" : m.getName()) + (m.getDefinition() == null ? "" : m.getDefinition());
            Set<String> mb = cjkBigrams(text);
            long overlap = qb.stream().filter(mb::contains).count();
            if (overlap >= 2) {
                out.add(m);
                if (out.size() == 5) {
                    break;
                }
            }
        }
        return out;
    }

    /** 场景内已探针口径卡:bemodel.semantic.scene-ds 过滤(空清单=不过滤=旧行为) */
    private List<Metric> probedInScene() {
        return metricService.listProbed().stream()
                .filter(m -> sceneProps.inScene(m.getDsCode()))
                .toList();
    }

    private String buildPlanPrompt(String q, List<Metric> candidates) {
        String cards = "";
        if (!candidates.isEmpty()) {
            StringBuilder sb = new StringBuilder("\n【在册口径卡】(已接实测探针,数值由平台探针出):\n");
            for (Metric m : candidates) {
                String def = m.getDefinition() == null ? "" : m.getDefinition();
                if (def.length() > 80) {
                    def = def.substring(0, 80);
                }
                sb.append("- ").append(m.getMetricCode()).append("(").append(m.getName()).append("): ")
                        .append(def).append("\n");
            }
            sb.append("规则补充：\n")
              .append("9. 用户问题恰好是上面某张口径卡所指的指标本身(不带科室/病区/时段等范围限定)时,")
              .append("返回 {\"mode\":\"METRIC\",\"metrics\":[\"口径卡编码\",...]}(可多张);\n")
              .append("   带范围限定或与口径卡所指不符的问题,忽略本条,按规则 1-8 作答。\n");
            cards = sb.toString();
        }
        return buildSemanticContext()
                + "\n规则：\n"
                + "1. ds 与表名只能取【物理映射】段中列出的数据源编码和物理表名（如 DS_CHARGE 的 pay_record），"
                + "概念 code 是语义名不是表名，严禁当作表名使用；列只能用该表 = 号左侧列出的物理列名。\n"
                + "2. 枚举列把中文反解为原始码（如「已发药」→ status='1'）。\n"
                + "3. SQL 必须以 SELECT 开头；单表优先，JOIN 只允许同一数据源内的表；必须带 LIMIT 且 ≤100。\n"
                + "4. 统计类问题用 COUNT/SUM/AVG；明细问题列出关键列。\n"
                + "5. 名称类条件（科室、药品、项目等）用 LIKE '%关键词%' 宽松匹配中文名称列"
                + "（如 drug_name、item_name、dept 这类映射为名称属性的列），严禁拿编码列（如 drug_code、item_code）做中文匹配。\n"
                + "6. 今天日期：" + LocalDate.now() + "（昨天/上周等相对时间据此换算；业务数据多在 2026-08 月，"
                + "若用户问的时间显然无数据仍照常生成 SQL，不要编造）。\n"
                + "7. 「可不可以/能不能/是否支持/是否允许」这类业务规则问题不要查数，返回模型作答："
                + "{\"mode\":\"MODEL_ANSWER\",\"ds\":\"相关数据源编码\",\"semantics\":\"基于哪个概念/哪个关键属性的结构判断\","
                + "\"conclusion\":\"可以/不可以 + 一句依据\",\"verifySql\":\"可选，验证该结构判断的探针SQL"
                + "（同 QUERY 校验规则），没有把握就留空\"}。\n"
                + "   示例：「多个患者的处方可以一起结算吗？」→ {\"mode\":\"MODEL_ANSWER\",\"ds\":\"DS_CHARGE\","
                + "\"semantics\":\"SETTLEMENT(结算记录)按 inhos_no 维系：一张结算单对应一个患者的一次住院\","
                + "\"conclusion\":\"不可以。结算单按单个患者的单次住院维系，不支持跨患者合并结算\","
                + "\"verifySql\":\"SELECT COUNT(*) AS cnt, COUNT(DISTINCT inhos_no) AS patients FROM settlement\"}\n"
                + "8. 语义层确实回答不了时返回 {\"mode\":\"UNANSWERABLE\",\"reason\":\"一句话原因\","
                + "\"gapType\":\"AMBIGUITY 问题歧义（口径/时间范围/统计对象不明，追问用户可解） 或 VOCABULARY 本体无此概念\"}。\n"
                + cards
                + "\n用户问题：" + q + "\n"
                + "只输出JSON：{\"mode\":\"QUERY\",\"ds\":\"数据源编码\",\"sql\":\"SELECT ...\",\"semantics\":\"一句话说明查了什么、用了哪些概念\"}";
    }

    // ---------- 3. SQL 安全校验（纯函数，表/列白名单由调用方给） ----------

    private static final Set<String> FORBIDDEN = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE", "GRANT", "REVOKE",
            "REPLACE", "LOAD", "CALL", "EXEC", "EXECUTE", "HANDLER", "LOCK", "UNLOCK", "SET", "INTO", "OUTFILE");

    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "ON",
            "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "AS", "AND", "OR", "NOT", "IN", "IS", "NULL",
            "LIKE", "BETWEEN", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "DISTINCT", "UNION",
            "ALL", "ASC", "DESC", "TRUE", "FALSE", "INTERVAL",
            "COUNT", "SUM", "AVG", "MAX", "MIN", "NOW", "CURDATE", "CURRENT_DATE", "CURRENT_TIMESTAMP",
            "DATE", "DATEDIFF", "DATE_FORMAT", "IFNULL", "IF", "COALESCE", "ROUND", "CAST", "CONCAT",
            "SUBSTRING", "YEAR", "MONTH", "DAY", "CHAR", "SIGNED", "UNSIGNED", "DECIMAL");

    private static final Pattern IDENT = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern FROM_JOIN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-zA-Z_][a-zA-Z0-9_]*)(?:\\s+(?:as\\s+)?([a-zA-Z_][a-zA-Z0-9_]*))?");
    private static final Pattern AS_ALIAS = Pattern.compile("(?i)\\bas\\s+([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern LIMIT = Pattern.compile("(?i)\\blimit\\s+(\\d+)");

    /**
     * 只读校验 + 表白名单 + 列白名单 + LIMIT 钳制。通过则返回最终可执行 SQL，否则抛 BizException。
     * 说明：SELECT 子句里非表达式首位的标识符视为数字别名放行（如 COUNT(*) cnt），
     * 因此「在 SELECT 首位直接引用白名单外列」会被拒，而合法别名不受误伤。
     */
    public static String validateSql(String sql, Set<String> allowedTables, Set<String> allowedColumns) {
        if (sql == null || sql.isBlank()) {
            throw new BizException("SQL 为空");
        }
        String cleaned = stripCommentsAndLiterals(sql);
        if (cleaned.contains(";")) {
            throw new BizException("不允许多语句（含分号）");
        }
        String[] toks = cleaned.trim().split("\\s+");
        if (toks.length == 0 || !"SELECT".equalsIgnoreCase(toks[0])) {
            throw new BizException("只允许 SELECT 查询");
        }
        for (String bad : FORBIDDEN) {
            if (Pattern.compile("(?i)(?<![a-zA-Z0-9_])" + bad + "(?![a-zA-Z0-9_])").matcher(cleaned).find()) {
                throw new BizException("SQL 含禁用关键字: " + bad);
            }
        }
        Set<String> tables = lowerAll(allowedTables);
        Set<String> columns = lowerAll(allowedColumns);

        // 表校验 + 表别名收集
        Set<String> aliases = new LinkedHashSet<>();
        Matcher tm = FROM_JOIN.matcher(cleaned);
        while (tm.find()) {
            String table = tm.group(1).toLowerCase();
            if (!tables.contains(table)) {
                throw new BizException("表不在该数据源的映射白名单: " + table);
            }
            if (tm.group(2) != null && !KEYWORDS.contains(tm.group(2).toUpperCase())) {
                aliases.add(tm.group(2).toLowerCase());
            }
        }
        Matcher am = AS_ALIAS.matcher(cleaned);
        while (am.find()) {
            aliases.add(am.group(1).toLowerCase());
        }
        // SELECT 子句里的数字别名（非表达式首位的裸标识符，如 COUNT(*) cnt 的 cnt）
        int firstFrom = indexOfWord(cleaned, "from");
        String selectClause = firstFrom > 0 ? cleaned.substring(0, firstFrom) : cleaned;
        boolean expectExpr = false;
        Matcher sm = IDENT.matcher(selectClause);
        while (sm.find()) {
            String id = sm.group();
            if (KEYWORDS.contains(id.toUpperCase())) {
                expectExpr = Set.of("SELECT", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE").contains(id.toUpperCase());
                continue;
            }
            if (nextNonSpaceIs(selectClause, sm.end(), '(')) {
                expectExpr = false; // 函数名
                continue;
            }
            // 逗号/左括号后是新表达式的首位（如 SELECT a, b 的 b），不是别名
            if (prevNonSpaceIs(selectClause, sm.start(), ',') || prevNonSpaceIs(selectClause, sm.start(), '(')) {
                expectExpr = true;
            }
            // 点号两侧是 表别名.列 的引用，永远不是别名
            if (prevNonSpaceIs(selectClause, sm.start(), '.') || nextNonSpaceIs(selectClause, sm.end(), '.')) {
                expectExpr = true;
            }
            if (!expectExpr) {
                aliases.add(id.toLowerCase());
            }
            expectExpr = false;
        }

        // 列校验：所有标识符必须落在 关键字/函数/别名/表白名单/列白名单 之内
        Matcher im = IDENT.matcher(cleaned);
        while (im.find()) {
            String id = im.group();
            String low = id.toLowerCase();
            if (KEYWORDS.contains(id.toUpperCase()) || aliases.contains(low) || tables.contains(low)) {
                continue;
            }
            if (nextNonSpaceIs(cleaned, im.end(), '(')) {
                continue; // 函数调用
            }
            if (prevNonSpaceIs(cleaned, im.start(), '.')) {
                if (!columns.contains(low)) {
                    throw new BizException("列不在映射白名单: " + id);
                }
                continue;
            }
            if (nextNonSpaceIs(cleaned, im.end(), '.')) {
                if (!tables.contains(low) && !aliases.contains(low)) {
                    throw new BizException("限定词不在白名单: " + id);
                }
                continue;
            }
            if (!columns.contains(low)) {
                throw new BizException("列不在映射白名单: " + id);
            }
        }

        // LIMIT 钳制
        String finalSql = sql.trim();
        Matcher lm = LIMIT.matcher(finalSql);
        if (lm.find()) {
            int v = Integer.parseInt(lm.group(1));
            if (v > 100) {
                finalSql = finalSql.substring(0, lm.start()) + "LIMIT 100" + finalSql.substring(lm.end());
            }
        } else {
            finalSql += " LIMIT 100";
        }
        return finalSql;
    }

    /** 去注释（--、#、块注释）与字符串字面量（''/"" 转义成对处理；反引号保留标识符内容） */
    private static String stripCommentsAndLiterals(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '#') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
            } else if (c == '\'' || c == '"') {
                char q = c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (sql.charAt(i) == q) {
                        if (i + 1 < n && sql.charAt(i + 1) == q) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
            } else if (c == '`') {
                i++;
                while (i < n && sql.charAt(i) != '`') {
                    out.append(sql.charAt(i));
                    i++;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int indexOfWord(String text, String word) {
        Matcher m = Pattern.compile("(?i)(?<![a-zA-Z0-9_])" + word + "(?![a-zA-Z0-9_])").matcher(text);
        return m.find() ? m.start() : -1;
    }

    private static boolean nextNonSpaceIs(String s, int from, char c) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i < s.length() && s.charAt(i) == c;
    }

    private static boolean prevNonSpaceIs(String s, int from, char c) {
        int i = from - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        return i >= 0 && s.charAt(i) == c;
    }

    private static Set<String> lowerAll(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        in.forEach(s -> out.add(s.toLowerCase()));
        return out;
    }

    // ---------- 4. 执行与作答（LLM call 2） ----------

    private Optional<String> composeAnswer(String q, Plan plan, String sql,
                                           List<Map<String, Object>> view, int total) {
        String rowsJson;
        try {
            rowsJson = objectMapper.writeValueAsString(view);
        } catch (Exception e) {
            rowsJson = "[]";
        }
        if (rowsJson.length() > 3000) {
            rowsJson = rowsJson.substring(0, 3000) + "…";
        }
        return deepSeekClient.chat("CS_SEMANTIC_ANSWER",
                "你是医院信息平台的数据问答助手。严格基于给定查询结果用中文回答，先给结论再给关键数字，"
                        + "口语化，不超过200字。结果里没有的信息不要编造，结果为0条就如实说没有。",
                "用户问题：" + q + "\n查询语义：" + plan.semantics() + "\n执行SQL：" + sql
                        + "\n结果行数：" + total + (total > 20 ? "（仅展示前20行）" : "")
                        + "\n结果JSON：" + rowsJson);
    }

    /** LLM 失败时的模板拼装（数字同样来自真实查询，绝不编造） */
    private String templateAnswer(Plan plan, int total, List<Map<String, Object>> view) {
        if (total == 0) {
            return "按本体映射到 " + plan.ds() + " 查询，没有符合条件的记录。（" + plan.semantics() + "）";
        }
        return "按本体映射查询到 " + total + " 条记录（" + plan.semantics() + "）。首条明细：" + view.get(0);
    }

    /** 从实际用到的表反查映射概念，给 1-2 个本体页跳转链接 */
    private List<Map<String, String>> conceptLinks(String ds, List<String> usedTables) {
        if (usedTables.isEmpty()) {
            return List.of();
        }
        List<Mapping> mappings = mappingMapper.selectList(new LambdaQueryWrapper<Mapping>()
                .eq(Mapping::getDsCode, ds).eq(Mapping::getStatus, "ACTIVE")
                .in(Mapping::getTableName, usedTables));
        List<String> codes = mappings.stream().map(Mapping::getConceptCode).distinct().limit(2).toList();
        List<Map<String, String>> links = new ArrayList<>();
        for (String code : codes) {
            links.add(Map.of("label", "去本体页看概念 " + code, "route", "/ontology?concept=" + code));
        }
        return links;
    }

    /** 模型作答无表可用时，从文本里识别提及的概念（code 或中文名）给跳转链接 */
    private List<Map<String, String>> conceptLinksFromText(String text) {
        if (text == null) {
            return List.of();
        }
        List<Concept> concepts = conceptMapper.selectList(
                new LambdaQueryWrapper<Concept>().eq(Concept::getStatus, "PUBLISHED"));
        List<Map<String, String>> links = new ArrayList<>();
        for (Concept c : concepts) {
            if (text.contains(c.getCode()) || (c.getName() != null && text.contains(c.getName()))) {
                links.add(Map.of("label", "去本体页看概念 " + c.getCode(),
                        "route", "/ontology?concept=" + c.getCode()));
                if (links.size() >= 2) {
                    break;
                }
            }
        }
        return links;
    }

    private Set<String> allowedTables(String ds) {
        if (!sceneProps.inScene(ds)) {
            return Set.of(); // 场景外数据源：白名单为空 → 走既有 miss 回流，不放行
        }
        return new LinkedHashSet<>(mappingMapper.selectList(new LambdaQueryWrapper<Mapping>()
                        .eq(Mapping::getDsCode, ds).eq(Mapping::getStatus, "ACTIVE"))
                .stream().map(Mapping::getTableName).toList());
    }

    private Set<String> allowedColumns(String ds) {
        if (!sceneProps.inScene(ds)) {
            return Set.of(); // 场景外数据源：白名单为空 → 走既有 miss 回流，不放行
        }
        return new LinkedHashSet<>(mappingMapper.selectList(new LambdaQueryWrapper<Mapping>()
                        .eq(Mapping::getDsCode, ds).eq(Mapping::getStatus, "ACTIVE"))
                .stream().map(Mapping::getColumnName).toList());
    }

    /**
     * METRIC 模式执行:命中口径卡逐卡跑受信探针出实测值,关联对账组摘要;LLM 只组织表达不编数。
     * 探针失败:card.error 如实呈现,绝不回落现场 SQL(回落=数值断层回归,禁止)。
     */
    private Map<String, Object> metricAnswer(String q, Plan plan, boolean analytics) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String code : plan.metrics()) {
            Metric m = metricService.getByCode(code);
            if (m == null) {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("metricCode", m.getMetricCode());
            card.put("name", m.getName());
            card.put("definition", m.getDefinition());
            card.put("formula", m.getFormula());
            card.put("probeSql", m.getProbeSql());
            card.put("dsCode", m.getDsCode());
            card.put("hasProbe", m.getProbeSql() != null && !m.getProbeSql().isBlank());
            card.put("owner", m.getOwner());
            card.put("warnThreshold", m.getWarnThreshold());
            try {
                Map<String, Object> ev = metricService.evaluate(code);
                card.put("value", ev.get("value"));
                card.put("alarm", ev.get("alarm"));
                card.put("evaluatedAt", ev.get("evaluatedAt"));
            } catch (Exception e) {
                card.put("error", e.getMessage());
            }
            cards.add(card);
        }
        List<Map<String, Object>> groups = reconcileGroupsFor(plan.metrics());
        Optional<String> llmAnswer = composeMetricAnswer(q, cards, groups);
        String answer = llmAnswer.orElseGet(() -> templateMetricAnswer(cards, groups));
        String answerSource = llmAnswer.isPresent() ? "LLM" : "TEMPLATE";
        long durationMs = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", q);
        result.put("intent", "口径实测");
        result.put("router", "SEMANTIC");
        result.put("answer", answer);
        List<Map<String, String>> evidence = new ArrayList<>();
        for (Map<String, Object> c : cards) {
            String value = c.get("error") != null
                    ? "实测失败——" + c.get("error")
                    : String.valueOf(c.get("value")) + "（探针:" + c.get("dsCode") + "）";
            evidence.add(Map.of("label", "口径:" + c.get("name"), "value", value));
        }
        result.put("evidence", evidence);
        result.put("metricCards", cards);
        result.put("reconcileGroups", groups);
        String sqlSummary = cards.stream()
                .map(c -> c.get("probeSql") == null ? "" : String.valueOf(c.get("probeSql")))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" ; "));
        String traceId = persistQaTrace(analytics, q, plan, truncate(sqlSummary, 1024),
                List.of(), "", cards.size(), answer, answerSource, (int) durationMs);
        List<Map<String, String>> links = new ArrayList<>();
        for (Map<String, Object> c : cards) {
            links.add(Map.of("label", "口径卡:" + c.get("name"), "route", "/glossary?metric=" + c.get("metricCode")));
        }
        if (traceId != null) {
            result.put("traceId", traceId);
            links.add(Map.of("label", "查证据链", "route", "/trace?type=QA&key=" + traceId));
        }
        result.put("links", links);
        return result;
    }

    /** 命中口径卡关联的对账组(splitCodes 精确判定,不做 LIKE 裸匹配);失败不影响口径回答 */
    private List<Map<String, Object>> reconcileGroupsFor(List<String> codes) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> g : reconcileService.listGroups()) {
                Object mc = g.get("metricCodes");
                if (mc instanceof List<?> list && list.stream().anyMatch(c -> codes.contains(String.valueOf(c)))) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("groupCode", g.get("groupCode"));
                    item.put("name", g.get("name"));
                    item.put("latestDiff", g.get("latest") == null ? null
                            : ((Map<?, ?>) g.get("latest")).get("diffValue"));
                    item.put("disputeStatus", g.get("disputeStatus"));
                    out.add(item);
                }
            }
        } catch (Exception e) {
            log.warn("对账组关联查询失败（不影响口径回答）: {}", e.getMessage());
        }
        return out;
    }

    /** METRIC 答案表达:LLM 只组织语言,数字全部为注入真数(callType 复用 CS_SEMANTIC_ANSWER);prompt 必含用户问题原文 */
    private Optional<String> composeMetricAnswer(String q, List<Map<String, Object>> cards, List<Map<String, Object>> groups) {
        StringBuilder ctx = new StringBuilder("口径实测结果（数字已由平台探针实测，只能引用不得改算）：\n");
        for (Map<String, Object> c : cards) {
            ctx.append("- ").append(c.get("name")).append("(").append(c.get("metricCode")).append("): ")
                    .append(c.get("error") != null ? "实测失败——" + c.get("error") : c.get("value"))
                    .append("\n");
        }
        if (!groups.isEmpty()) {
            ctx.append("关联对账组：\n");
            for (Map<String, Object> g : groups) {
                ctx.append("- ").append(g.get("name")).append(": 差额 ").append(g.get("latestDiff"))
                        .append("，分歧状态 ").append(g.get("disputeStatus")).append("\n");
            }
        }
        ctx.append("\n用户问题：").append(q).append("\n")
           .append("用两三句话回答：各口径实测值、差额与对账状态；实测失败就说失败原因，不要编数，不要再自己写 SQL。");
        return deepSeekClient.chat("CS_SEMANTIC_ANSWER",
                "你是医疗信息平台的口径解答员，基于给定的实测数据组织回答，不编造任何数字。",
                ctx.toString());
    }

    /** LLM 降级的确定性模板:逐卡「口径名:值」,两卡附相差,对账组状态如实 */
    private String templateMetricAnswer(List<Map<String, Object>> cards, List<Map<String, Object>> groups) {
        StringBuilder sb = new StringBuilder("按在册口径卡实测：\n");
        for (Map<String, Object> c : cards) {
            sb.append("- ").append(c.get("name")).append(": ")
                    .append(c.get("error") != null ? "实测失败（" + c.get("error") + "）" : c.get("value"))
                    .append("\n");
        }
        if (cards.size() > 1) {
            Object a = cards.get(0).get("value");
            Object b = cards.get(1).get("value");
            if (a instanceof Number x && b instanceof Number y) {
                sb.append("两口径相差 ").append(Math.abs(x.longValue() - y.longValue())).append("。\n");
            }
        }
        for (Map<String, Object> g : groups) {
            sb.append("对账组「").append(g.get("name")).append("」差额 ")
                    .append(g.get("latestDiff") == null ? "算不出" : g.get("latestDiff"))
                    .append("，状态 ").append(g.get("disputeStatus")).append("。\n");
        }
        return sb.toString();
    }
}
