package com.bemodel.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.entity.MappingLog;
import com.bemodel.datasource.entity.PhysicalColumn;
import com.bemodel.datasource.event.MappingChangedEvent;
import com.bemodel.datasource.mapper.MappingLogMapper;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.TermMapper;
import com.bemodel.ontology.service.MissService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MappingService extends ServiceImpl<MappingMapper, Mapping> {

    @Value("${bemodel.domain-context:工业供应链管理}")
    private String domainContext = "工业供应链管理";

    private final SchemaScanService schemaScanService;
    private final ConceptMapper conceptMapper;
    private final AttributeMapper attributeMapper;
    private final TermMapper termMapper;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;
    private final MissService missService;
    private final MappingLogMapper mappingLogMapper;
    private final ApplicationEventPublisher events;

    /** F1-A：每批送入 LLM 的列数上限（顺序多批，建议合并；全部批次失败才整体降级规则） */
    @Value("${bemodel.mapping.llm-batch-size:25}")
    private int llmBatchSize;

    /** F1-C：预筛后进提示词的概念数上限（按列名/注释字面包含计分 Top-K；<=0 视为不筛） */
    @Value("${bemodel.mapping.top-k-concepts:25}")
    private int topKConcepts;

    /** F1（对抗评审）：LLM 阶段总时间预算（秒）——超预算的批不再发起 LLM 调用、就地规则兜底，
     * 最坏总时长后端自持（不依赖前端超时常量），宽表/提供方劣化也不白烧 token */
    @Value("${bemodel.mapping.llm-budget-seconds:280}")
    private int llmBudgetSeconds;

    /** 生命周期状态集（V30）：PROPOSED→ACTIVE→DEPRECATED（可重新启用） */
    public static final String PROPOSED = "PROPOSED";
    public static final String ACTIVE = "ACTIVE";
    public static final String DEPRECATED = "DEPRECATED";
    private static final Map<String, Set<String>> LIFECYCLE = Map.of(
            PROPOSED, Set.of(ACTIVE),
            ACTIVE, Set.of(DEPRECATED),
            DEPRECATED, Set.of(ACTIVE));

    public List<Mapping> list(String dsCode, String tableName) {
        return list(dsCode, tableName, null);
    }

    /** 列表：status 为空时返回全部（含各生命周期），供管理页筛选 */
    public List<Mapping> list(String dsCode, String tableName, String status) {
        return lambdaQuery()
                .eq(dsCode != null && !dsCode.isBlank(), Mapping::getDsCode, dsCode)
                .eq(tableName != null && !tableName.isBlank(), Mapping::getTableName, tableName)
                .eq(status != null && !status.isBlank(), Mapping::getStatus, status)
                .list();
    }

    /** 仅生效映射：运行时语义消费（实例投影/问数/探针/影响面）一律走这里 */
    public List<Mapping> activeList() {
        return lambdaQuery().eq(Mapping::getStatus, ACTIVE).list();
    }

    /**
     * 批量保存映射（同一物理列重复保存时覆盖）。
     * 生命周期默认（V30）：payload 未显式给 status 时，AI 来源落 PROPOSED（提议待审），人工来源落 ACTIVE。
     * 每次落库都写留痕（CREATE/UPDATE 前后对照）。
     */
    @Transactional
    public void saveBatch(List<Mapping> mappings) {
        for (Mapping m : mappings) {
            Mapping existing = lambdaQuery()
                    .eq(Mapping::getDsCode, m.getDsCode())
                    .eq(Mapping::getTableName, m.getTableName())
                    .eq(Mapping::getColumnName, m.getColumnName())
                    .one();
            if (existing != null) {
                m.setId(existing.getId());
                if (m.getStatus() == null || m.getStatus().isBlank()) {
                    // 重新采纳 AI 建议不直接覆盖生效映射：ACTIVE 映射的内容变更回退 PROPOSED 待建模员再生效
                    // （与前端「已保存提议映射（待建模员生效）」文案一致——文案按真实能力分支）
                    m.setStatus("AI".equalsIgnoreCase(m.getSource()) && ACTIVE.equals(existing.getStatus())
                            ? PROPOSED : existing.getStatus());
                } else if (!Set.of(PROPOSED, ACTIVE, DEPRECATED).contains(m.getStatus())) {
                    // 显式携带 status 同样过白名单：不给生命周期枚举外的值落库卡死映射的口子
                    throw new BizException("非法映射状态: " + m.getStatus());
                }
                m.setUpdatedBy(CurrentUser.username());
                m.setUpdatedAt(LocalDateTime.now());
                updateById(m);
                // after 取落库后状态而非 payload：MyBatis-Plus 只更新非空字段，快照失真会误导审计
                log(existing, getById(m.getId()), "UPDATE");
            } else {
                if (m.getStatus() == null || m.getStatus().isBlank()) {
                    m.setStatus("AI".equalsIgnoreCase(m.getSource()) ? PROPOSED : ACTIVE);
                }
                if (!Set.of(PROPOSED, ACTIVE, DEPRECATED).contains(m.getStatus())) {
                    throw new BizException("非法映射状态: " + m.getStatus());
                }
                m.setUpdatedBy(CurrentUser.username());
                m.setUpdatedAt(LocalDateTime.now());
                save(m);
                log(null, m, "CREATE");
            }
        }
        events.publishEvent(new MappingChangedEvent());
    }

    /** 生命周期流转：PROPOSED→ACTIVE（生效）/ ACTIVE→DEPRECATED（停用）/ DEPRECATED→ACTIVE（重新启用） */
    @Transactional
    public Mapping transition(Long id, String target) {
        if (!CurrentUser.hasAnyRole("EDITOR", "ADMIN")) {
            throw new BizException("映射生命周期操作需建模员（EDITOR）或管理员（ADMIN）");
        }
        if (!Set.of(PROPOSED, ACTIVE, DEPRECATED).contains(target)) {
            throw new BizException("非法映射状态: " + target);
        }
        Mapping current = getById(id);
        if (current == null) {
            throw new BizException("映射不存在: id=" + id);
        }
        Set<String> allowed = LIFECYCLE.getOrDefault(current.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new BizException("不允许从 " + current.getStatus() + " 流转到 " + target);
        }
        Mapping before = snapshot(current);
        current.setStatus(target);
        current.setUpdatedBy(CurrentUser.username());
        current.setUpdatedAt(LocalDateTime.now());
        updateById(current);
        log(before, current, "TRANSITION");
        events.publishEvent(new MappingChangedEvent());
        return current;
    }

    /** 编辑映射语义（概念/属性/值字典）：带前后对照留痕；状态只能走 transition */
    @Transactional
    public Mapping updateMapping(Long id, Mapping patch) {
        if (!CurrentUser.hasAnyRole("EDITOR", "ADMIN")) {
            throw new BizException("映射编辑需建模员（EDITOR）或管理员（ADMIN）");
        }
        Mapping current = getById(id);
        if (current == null) {
            throw new BizException("映射不存在: id=" + id);
        }
        Mapping before = snapshot(current);
        if (patch.getConceptCode() != null) {
            current.setConceptCode(patch.getConceptCode());
        }
        if (patch.getAttrCode() != null) {
            current.setAttrCode(patch.getAttrCode());
        }
        if (patch.getValueMap() != null) {
            current.setValueMap(patch.getValueMap());
        }
        if (patch.getConfirmed() != null) {
            current.setConfirmed(patch.getConfirmed());
        }
        current.setUpdatedBy(CurrentUser.username());
        current.setUpdatedAt(LocalDateTime.now());
        updateById(current);
        log(before, current, "UPDATE");
        events.publishEvent(new MappingChangedEvent());
        return current;
    }

    /** 删除映射：删除前留痕（DELETE），便于误删恢复与审计 */
    @Transactional
    public void deleteLogged(Long id) {
        if (!CurrentUser.hasAnyRole("EDITOR", "ADMIN")) {
            throw new BizException("映射删除需建模员（EDITOR）或管理员（ADMIN）");
        }
        Mapping current = getById(id);
        if (current == null) {
            throw new BizException("映射不存在: id=" + id);
        }
        log(snapshot(current), null, "DELETE");
        removeById(id);
        events.publishEvent(new MappingChangedEvent());
    }

    public List<MappingLog> logOf(Long id) {
        return mappingLogMapper.selectList(new LambdaQueryWrapper<MappingLog>()
                .eq(MappingLog::getMappingId, id)
                .orderByDesc(MappingLog::getId));
    }

    private Mapping snapshot(Mapping m) {
        Mapping s = new Mapping();
        s.setId(m.getId());
        s.setDsCode(m.getDsCode());
        s.setTableName(m.getTableName());
        s.setColumnName(m.getColumnName());
        s.setConceptCode(m.getConceptCode());
        s.setAttrCode(m.getAttrCode());
        s.setValueMap(m.getValueMap());
        s.setConfirmed(m.getConfirmed());
        s.setSource(m.getSource());
        s.setStatus(m.getStatus());
        return s;
    }

    /**
     * 变更留痕：留痕是硬要求（产品化分水岭），写入失败随主操作一起回滚——宁可不成功，不可零留痕。
     * 调用方均在 @Transactional 内：DELETE 幽灵留痕/部分提交批次在此一并杜绝。
     */
    private void log(Mapping before, Mapping after, String action) {
        try {
            MappingLog entry = new MappingLog();
            entry.setMappingId((after != null ? after : before).getId());
            entry.setAction(action);
            entry.setBeforeJson(before == null ? null : objectMapper.writeValueAsString(before));
            entry.setAfterJson(after == null ? null : objectMapper.writeValueAsString(after));
            entry.setOperator(CurrentUser.username());
            entry.setCreatedAt(LocalDateTime.now());
            mappingLogMapper.insert(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("映射留痕序列化失败", e);
        }
    }


    /**
     * AI 智能映射推荐：物理表结构 + 已发布概念 → deepseek-v4-flash 返回建议映射。
     * F1-A 列分批（bemodel.mapping.llm-batch-size）：每批独立 LLM 调用、建议合并，
     * 单批提示词体积不再随表宽膨胀；全部批次无产出才整体降级字段名/注释相似度规则（行为同旧版）。
     * F1-C 概念 Top-K 预筛（bemodel.mapping.top-k-concepts）：每批只送字面相关的候选概念，
     * 提示词不再随本体规模线性增长。LLM 不可用时降级为规则（同旧版）。
     */
    public Map<String, Object> aiSuggest(String dsCode, String tableName) {
        List<PhysicalColumn> columns = schemaScanService.columns(dsCode, tableName);
        if (columns.isEmpty()) {
            throw new BizException("未找到物理表，请先扫描数据源: " + dsCode + "." + tableName);
        }
        List<Concept> concepts = conceptMapper.selectList(
                new LambdaQueryWrapper<Concept>().eq(Concept::getStatus, "PUBLISHED"));
        List<Attribute> attributes = attributeMapper.selectList(null);
        // F1-C：方言术语（ONTOLOGY_MISS 挂靠、人工维护）也进针集——列注释里的口头说法靠它们命中
        List<Term> terms = termMapper.selectList(null);

        List<Map<String, Object>> suggestions = new ArrayList<>();
        boolean llmUsed = false;
        int failedBatches = 0;
        long deadline = System.currentTimeMillis() + llmBudgetSeconds * 1000L;
        for (List<PhysicalColumn> batch : partition(columns, llmBatchSize)) {
            List<Concept> candidates = topKConcepts(concepts, attributes, terms, batch, topKConcepts);
            Optional<String> llmResp = Optional.empty();
            if (System.currentTimeMillis() <= deadline) {
                String prompt = buildPrompt(tableName, batch, candidates, attributes);
                if (candidates.size() < concepts.size()) {
                    prompt += "\n注：候选概念已按列名/注释相似度预筛（Top-" + topKConcepts + "，共 "
                            + concepts.size() + " 个概念中选 " + candidates.size()
                            + " 个）。若确实无合适映射请给出空建议，不要硬配。";
                }
                llmResp = deepSeekClient.chat("MAPPING_SUGGEST",
                        "你是" + domainContext + "本体映射专家。只返回JSON数组，不要多余文字。", prompt);
            }
            List<Map<String, Object>> parsed =
                    llmResp.map(r -> parseLlmSuggestions(r, batch)).orElse(List.of());
            if (parsed.isEmpty()) {
                // 对抗评审（部分批失败第三态）：单批 LLM 失败/超预算/解析无产出时就地规则兜底，
                // 恢复旧版「每列必有建议行」不变式——不让基础设施失败伪装成整块缺口，
                // 也避免失败批的可规则命中列被误记 ATTRIBUTE miss；failedBatches 供前端如实提示
                failedBatches++;
                suggestions.addAll(ruleSuggest(batch, concepts, attributes));
                continue;
            }
            suggestions.addAll(parsed);
            llmUsed = true;
        }
        // 本体增长回路：某列没有任何概念候选可推荐时，按列注释（无则列名）采集 ATTRIBUTE miss
        for (PhysicalColumn col : columns) {
            boolean noCandidate = suggestions.stream()
                    .filter(s -> col.getColumnName().equals(s.get("column")))
                    .noneMatch(s -> s.get("conceptCode") != null && !s.get("conceptCode").toString().isBlank());
            if (noCandidate) {
                missService.recordMiss(
                        col.getColumnComment() == null || col.getColumnComment().isBlank()
                                ? col.getColumnName() : col.getColumnComment(),
                        "ATTRIBUTE", "MAPPING_AI");
            }
        }
        return Map.of(
                "llmUsed", llmUsed,
                "failedBatches", failedBatches,
                "model", deepSeekClient.model(),
                "suggestions", suggestions);
    }

    private String buildPrompt(String tableName, List<PhysicalColumn> columns,
                               List<Concept> concepts, List<Attribute> attributes) {
        StringBuilder sb = new StringBuilder();
        sb.append("物理表 ").append(tableName).append(" 的字段：\n");
        for (PhysicalColumn c : columns) {
            sb.append("- ").append(c.getColumnName()).append(" (").append(c.getDataType())
                    .append(") 注释: ").append(c.getColumnComment() == null ? "" : c.getColumnComment()).append('\n');
        }
        sb.append("\n可选标准概念及属性：\n");
        for (Concept c : concepts) {
            sb.append("概念 ").append(c.getCode()).append("(").append(c.getName()).append("): ");
            List<String> attrs = attributes.stream()
                    .filter(a -> a.getConceptCode().equals(c.getCode()))
                    .map(a -> a.getAttrCode() + "(" + a.getAttrName() + ")")
                    .toList();
            sb.append(String.join(", ", attrs)).append('\n');
        }
        sb.append("\n请为每个物理字段推荐映射，返回JSON数组，元素格式：");
        sb.append("{\"column\":\"字段名\",\"conceptCode\":\"概念编码\",\"attrCode\":\"属性编码\",\"confidence\":0.0-1.0,\"reason\":\"理由\"}。");
        sb.append("无合适映射时 attrCode 填 null。只返回JSON。");
        return sb.toString();
    }

    private List<Map<String, Object>> parseLlmSuggestions(String resp, List<PhysicalColumn> columns) {
        try {
            String json = resp;
            int start = resp.indexOf('[');
            int end = resp.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = resp.substring(start, end + 1);
            }
            JsonNode arr = objectMapper.readTree(json);
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode node : arr) {
                String column = node.path("column").asText();
                boolean exists = columns.stream().anyMatch(c -> c.getColumnName().equals(column));
                if (!exists) {
                    continue;
                }
                result.add(Map.of(
                        "column", column,
                        "conceptCode", node.path("conceptCode").asText(""),
                        "attrCode", node.path("attrCode").isNull() ? "" : node.path("attrCode").asText(""),
                        "confidence", node.path("confidence").asDouble(0.5),
                        "reason", node.path("reason").asText("")));
            }
            return result;
        } catch (Exception e) {
            log.warn("LLM 映射结果解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * F1-A 批切分（纯函数，测试直测）：size<=0 视为整批不切；余数批/空列安全。
     * subList 是视图，调用方只读不增删，无结构耦合问题。
     */
    static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> batches = new ArrayList<>();
        if (size <= 0) {
            batches.add(items);
            return batches;
        }
        for (int i = 0; i < items.size(); i += size) {
            batches.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return batches;
    }

    /**
     * F1-C Top-K 概念预筛（纯函数，测试直测）：每个概念的「命中针集」=
     * 编码（整体 + 蛇形拆词）+ 名称 + 全部属性编码/名称 + 全部方言术语，
     * 对批内各列的「列名(小写)+注释(小写)」做包含计分；零分概念不入提示词
     * （与本表任何列都无字面相关的概念是纯噪声），按分降序取前 K（K<=0 不筛）。
     * 计分用命中率（命中针数/针集大小，对抗评审归一化）：多属性通用概念不能靠针数堆积
     * 挤掉精确匹配概念。列注释与列名同口径小写——注释里的大写缩写（CT/MRI）也要能命中。
     * 漏选场景由增长回路兜底：无候选列照常记 ATTRIBUTE miss。
     */
    static List<Concept> topKConcepts(List<Concept> concepts, List<Attribute> attributes,
                                      List<Term> terms, List<PhysicalColumn> columns, int k) {
        if (k <= 0 || concepts.size() <= k) {
            return concepts;
        }
        List<String> columnTexts = columns.stream()
                .map(c -> c.getColumnName().toLowerCase() + " " + lower(c.getColumnComment()))
                .toList();
        Map<String, List<String>> needlesByConcept = new HashMap<>();
        for (Attribute a : attributes) {
            needlesByConcept.computeIfAbsent(a.getConceptCode(), x -> new ArrayList<>())
                    .addAll(List.of(lower(a.getAttrCode()), lower(a.getAttrName())));
        }
        for (Term t : terms) {
            needlesByConcept.computeIfAbsent(t.getConceptCode(), x -> new ArrayList<>())
                    .add(lower(t.getTerm()));
        }
        record Scored(Concept concept, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Concept c : concepts) {
            List<String> needles = new ArrayList<>(splitSnake(c.getCode()));
            needles.add(lower(c.getName()));
            needles.addAll(needlesByConcept.getOrDefault(c.getCode(), List.of()));
            int hitNeedles = 0;
            int scoredNeedles = 0;
            for (String needle : needles) {
                if (needle == null || needle.length() < 2) {
                    continue; // 单字针会大面积误命中，噪声大于信号
                }
                scoredNeedles++;
                for (String colText : columnTexts) {
                    if (colText.contains(needle)) {
                        hitNeedles++;
                        break; // 命中率按「是否有列命中该针」计，多列命中不放大长针集概念
                    }
                }
            }
            double score = scoredNeedles == 0 ? 0.0 : (double) hitNeedles / scoredNeedles;
            scored.add(new Scored(c, score));
        }
        // 稳定排序（同分保持原序）+ 只要有分者，截断前 K
        return scored.stream()
                .filter(s -> s.score > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(k)
                .map(Scored::concept)
                .toList();
    }

    /** 概念编码 GoodsReceipt → [goodsreceipt]：整体 + 拆词（列名多是拆词的字面） */
    static List<String> splitSnake(String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        String lower = code.toLowerCase();
        List<String> parts = new ArrayList<>();
        parts.add(lower);
        for (String p : lower.split("_+")) {
            if (p.length() >= 2) {
                parts.add(p);
            }
        }
        return parts;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    /** 降级规则：按列名/注释与属性编码/名称的包含关系打分 */
    private List<Map<String, Object>> ruleSuggest(List<PhysicalColumn> columns,
                                                  List<Concept> concepts,
                                                  List<Attribute> attributes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PhysicalColumn col : columns) {
            String colName = col.getColumnName().toLowerCase();
            String comment = col.getColumnComment() == null ? "" : col.getColumnComment();
            Map<String, Object> best = null;
            for (Attribute attr : attributes) {
                boolean hit = colName.contains(attr.getAttrCode().toLowerCase())
                        || (!comment.isEmpty() && comment.contains(attr.getAttrName()));
                if (hit) {
                    Concept concept = concepts.stream()
                            .filter(c -> c.getCode().equals(attr.getConceptCode())).findFirst().orElse(null);
                    if (concept != null) {
                        best = Map.of("column", col.getColumnName(),
                                "conceptCode", concept.getCode(),
                                "attrCode", attr.getAttrCode(),
                                "confidence", 0.6,
                                "reason", "规则匹配：列名/注释与属性相似");
                        break;
                    }
                }
            }
            if (best == null) {
                best = Map.of("column", col.getColumnName(), "conceptCode", "",
                        "attrCode", "", "confidence", 0.0, "reason", "未匹配，请人工指定");
            }
            result.add(best);
        }
        return result;
    }
}
