package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Domain;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.DomainMapper;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import com.bemodel.ontology.mapper.TermMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本体增长回路：词表外说法（搜索零命中 / AI 映射失败）的采集与人工处置。
 * 核心原则：忽略是标记不是删除（计数照涨、可撤回）；采纳只创建 DRAFT 概念
 * （自动化系统不直接发布任何东西）；采纳可撤销（不删数据，概念置 DEPRECATED）。
 * 第三种处置：说法也可挂为现有概念的方言术语（adoptAsTerm），撤销时删除本次创建的术语行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissService extends ServiceImpl<OntologyMissMapper, OntologyMiss> {

    @Value("${bemodel.domain-context:工业供应链管理}")
    private String domainContext = "工业供应链管理";

    private final ConceptService conceptService;
    private final DomainMapper domainMapper;
    private final TermMapper termMapper;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    /** 采纳为术语时写入 bm_term.source_product 的溯源标记（revoke 据此确认归属，防止误删既有术语） */
    static final String TERM_SOURCE = "ONTOLOGY_MISS";

    /**
     * 采集入口：单语句 upsert（唯一键 term+kind 撞行原子 count+1），无并发竞态。
     * 已忽略的行计数照涨（忽略是标记不是删除）。静默降级：任何异常只打日志，绝不影响主流程。
     */
    public void recordMiss(String term, String kind, String source) {
        try {
            if (term == null) {
                return;
            }
            String t = term.trim();
            if (t.length() < 2 || t.length() > 128) {
                return;
            }
            baseMapper.upsert(t, kind, source);
        } catch (Exception e) {
            log.warn("记录本体 miss 失败（静默降级）: term={}, kind={}, source={}, err={}",
                    term, kind, source, e.getMessage());
        }
    }

    /** 审核看板：待处理（未忽略且未有效采纳）在前按 count desc，已忽略/已采纳在后 */
    public Map<String, Object> board() {
        List<OntologyMiss> all = list();
        List<OntologyMiss> items = all.stream()
                .sorted(Comparator.comparing((OntologyMiss m) -> isPending(m) ? 0 : 1)
                        .thenComparing(Comparator.comparing(OntologyMiss::getCount).reversed())
                        .thenComparing(OntologyMiss::getId))
                .toList();
        long pendingCount = all.stream().filter(this::isPending).count();
        return Map.of("items", items, "pendingCount", pendingCount);
    }

    /** 待处理 = 未忽略 且（从未采纳 或 采纳已撤销回池）。提案生成器选样复用同一口径（public） */
    public boolean isPending(OntologyMiss m) {
        return !Integer.valueOf(1).equals(m.getDismissed())
                && (m.getAdoptedConceptCode() == null || Integer.valueOf(1).equals(m.getRevoked()));
    }

    public OntologyMiss dismiss(Long id, String reason) {
        OntologyMiss miss = requireMiss(id);
        miss.setDismissed(1);
        miss.setDismissReason(reason);
        updateById(miss);
        return miss;
    }

    public OntologyMiss undismiss(Long id) {
        OntologyMiss miss = requireMiss(id);
        miss.setDismissed(0);
        updateById(miss);
        return miss;
    }

    /** 采纳：校验后复用 ConceptService 创建 DRAFT 概念；已采纳且未撤销时重复 adopt 报错（幂等） */
    public Concept adopt(Long id, String code, String name, String domainCode, String definition) {
        OntologyMiss miss = requireMiss(id);
        if (miss.getAdoptedConceptCode() != null && !Integer.valueOf(1).equals(miss.getRevoked())) {
            throw new BizException("该 miss 已采纳，概念: " + miss.getAdoptedConceptCode());
        }
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new BizException("概念编码与名称不能为空");
        }
        if (conceptService.getByCode(code) != null) {
            throw new BizException("概念编码已存在: " + code);
        }
        Long domainCnt = domainMapper.selectCount(
                new LambdaQueryWrapper<Domain>().eq(Domain::getCode, domainCode));
        if (domainCnt == null || domainCnt == 0) {
            throw new BizException("业务域不存在: " + domainCode);
        }
        Concept concept = new Concept();
        concept.setCode(code);
        concept.setName(name);
        concept.setDomainCode(domainCode);
        concept.setDefinition(definition);
        conceptService.create(concept);
        miss.setAdoptedConceptCode(code);
        miss.setAdoptedAs("CONCEPT");
        miss.setRevoked(0);
        updateById(miss);
        return concept;
    }

    /** 采纳为术语：miss 的说法挂为某个现有概念的方言术语（bm_term），同 term+concept 不重复插 */
    public OntologyMiss adoptAsTerm(Long id, String conceptCode) {
        OntologyMiss miss = requireMiss(id);
        if (miss.getAdoptedConceptCode() != null && !Integer.valueOf(1).equals(miss.getRevoked())) {
            throw new BizException("该 miss 已采纳，概念: " + miss.getAdoptedConceptCode());
        }
        if (conceptCode == null || conceptCode.isBlank()) {
            throw new BizException("概念编码不能为空");
        }
        if (conceptService.getByCode(conceptCode) == null) {
            throw new BizException("概念不存在: " + conceptCode);
        }
        Term samePair = termMapper.selectOne(new LambdaQueryWrapper<Term>()
                .eq(Term::getTerm, miss.getTerm())
                .eq(Term::getConceptCode, conceptCode).last("LIMIT 1"), false);
        if (samePair == null) {
            // 唯一键 uk_term(term, source_product)：同一说法已有本回路建的术语行时改挂新概念，避免撞键
            Term ownRow = termMapper.selectOne(new LambdaQueryWrapper<Term>()
                    .eq(Term::getTerm, miss.getTerm())
                    .eq(Term::getSourceProduct, TERM_SOURCE).last("LIMIT 1"), false);
            if (ownRow != null) {
                ownRow.setConceptCode(conceptCode);
                termMapper.updateById(ownRow);
            } else {
                Term t = new Term();
                t.setTerm(miss.getTerm());
                t.setConceptCode(conceptCode);
                t.setSourceProduct(TERM_SOURCE);
                t.setTermType("ALIAS");
                t.setCodeSystem("平台标准");
                termMapper.insert(t);
            }
        }
        miss.setAdoptedConceptCode(conceptCode);
        miss.setAdoptedAs("TERM");
        miss.setRevoked(0);
        updateById(miss);
        return miss;
    }

    /**
     * 仅落采纳标记（提案生成器 P1a 用）：概念已由组内首 miss 经 adopt() 创建时，
     * 同组其余 miss 用此方法挂到同一概念上，不再重复走编码/域校验与建概念。
     * 与 adopt() 同一套幂等守卫（已采纳且未撤销时报错）。
     */
    public OntologyMiss attachAdoption(Long id, String conceptCode) {
        OntologyMiss miss = requireMiss(id);
        if (miss.getAdoptedConceptCode() != null && !Integer.valueOf(1).equals(miss.getRevoked())) {
            throw new BizException("该 miss 已采纳，概念: " + miss.getAdoptedConceptCode());
        }
        miss.setAdoptedConceptCode(conceptCode);
        miss.setAdoptedAs("CONCEPT");
        miss.setRevoked(0);
        updateById(miss);
        return miss;
    }

    /** 撤销采纳：TERM 形态删除本次创建的术语行（术语无下游引用风险，可删）；CONCEPT 形态置 DEPRECATED；不删 miss 数据 */
    public OntologyMiss revoke(Long id) {
        OntologyMiss miss = requireMiss(id);
        if (miss.getAdoptedConceptCode() == null || Integer.valueOf(1).equals(miss.getRevoked())) {
            throw new BizException("该 miss 未采纳或已撤销");
        }
        if ("TERM".equals(miss.getAdoptedAs())) {
            // 三列定位 + 溯源标记确认是本次创建的术语行，防止误删平台既有术语
            termMapper.delete(new LambdaQueryWrapper<Term>()
                    .eq(Term::getTerm, miss.getTerm())
                    .eq(Term::getConceptCode, miss.getAdoptedConceptCode())
                    .eq(Term::getSourceProduct, TERM_SOURCE));
        } else {
            Concept concept = conceptService.getByCode(miss.getAdoptedConceptCode());
            // 状态机已支持 DRAFT/REVIEW→DEPRECATED 直达（维护治理加固 H2）：一次流转到位，
            // 不再途经瞬态 PUBLISHED（版本不虚增）；已发布概念的废弃仍须评审员/管理员（评审决定，
            // 撤销即废弃，语义对位），草稿撤销建模员即可。
            // 共享概念防护（对抗评审）：提案归并采纳后多个 miss 指向同一概念，任一成员撤销
            // 只解除自身标记；仅当它是最后一个未撤销引用者时才废弃概念，避免把其余成员
            // 留在「已采纳但概念已废弃」的矛盾态
            long others = lambdaQuery().eq(OntologyMiss::getAdoptedConceptCode, miss.getAdoptedConceptCode())
                    .eq(OntologyMiss::getRevoked, 0)
                    .ne(OntologyMiss::getId, miss.getId()).count();
            if (others == 0 && concept != null && !"DEPRECATED".equals(concept.getStatus())) {
                conceptService.transition(concept.getCode(), "DEPRECATED");
            }
        }
        miss.setRevoked(1);
        updateById(miss);
        return miss;
    }

    /**
     * AI 自动归类建议：AI 只给建议与人可读理由，采纳仍走 adopt 由人确认。
     * LLM 不可用或返回无法解析时明确降级（degraded=true 的兜底建议），不抛错。
     */
    public Map<String, Object> classify(Long missId) {
        OntologyMiss miss = requireMiss(missId);
        Optional<String> llm = deepSeekClient.chat("MISS_CLASSIFY",
                "你是" + domainContext + "本体治理助手，为平台使用中出现的词表外说法建议标准概念归类。只返回JSON，不要多余文字。",
                buildClassifyPrompt(miss));
        Map<String, Object> suggestion = llm.map(this::parseClassifySuggestion).orElse(null);
        if (suggestion == null) {
            return degradedSuggestion(miss);
        }
        // 只标注不修改：code 撞车让人决定换名，域非法则清空并标记
        String code = (String) suggestion.get("code");
        suggestion.put("codeTaken", !code.isBlank() && conceptService.getByCode(code) != null);
        String domainCode = (String) suggestion.get("domainCode");
        boolean domainValid = !domainCode.isBlank() && domainMapper.selectCount(
                new LambdaQueryWrapper<Domain>().eq(Domain::getCode, domainCode)) > 0;
        if (!domainValid) {
            suggestion.put("domainCode", "");
        }
        suggestion.put("domainValid", domainValid);
        suggestion.put("degraded", false);
        return suggestion;
    }

    /** 域清单 + 按域分组概念清单：人工归类（classify）与提案生成器（P1a）共用的本体上下文段 */
    public String buildOntologyContext() {
        List<Domain> domains = domainMapper.selectList(
                new LambdaQueryWrapper<Domain>().orderByAsc(Domain::getSort));
        List<Concept> concepts = conceptService.listByDomain(null);
        StringBuilder sb = new StringBuilder();
        sb.append("可选业务域：\n");
        for (Domain d : domains) {
            sb.append("- ").append(d.getCode()).append('(').append(d.getName()).append(')')
                    .append(d.getDescription() == null ? "" : ": " + d.getDescription()).append('\n');
        }
        sb.append("\n现有概念（按域分组）：\n");
        for (Domain d : domains) {
            List<String> cs = concepts.stream()
                    .filter(c -> d.getCode().equals(c.getDomainCode()))
                    .map(c -> c.getCode() + "(" + c.getName() + ")")
                    .toList();
            if (!cs.isEmpty()) {
                sb.append(d.getCode()).append(": ").append(String.join(", ", cs)).append('\n');
            }
        }
        return sb.toString();
    }

    private String buildClassifyPrompt(OntologyMiss miss) {
        StringBuilder sb = new StringBuilder();
        sb.append("词表外说法：\"").append(miss.getTerm()).append("\"（类型 ").append(miss.getKind())
                .append("，来源 ").append(miss.getSource())
                .append("，累计出现 ").append(miss.getCount()).append(" 次）\n\n");
        sb.append(buildOntologyContext());
        sb.append("\n请为该说法建议一个标准概念，只返回JSON对象：");
        sb.append("{\"code\":\"稳定业务编码（风格参考 Lot、Demand）\",\"name\":\"中文名称\",");
        sb.append("\"domainCode\":\"从上面业务域列表中选择的编码\",\"definition\":\"业务定义\",");
        sb.append("\"reason\":\"一句中文人话解释为什么这样归类\"}。只返回JSON。");
        return sb.toString();
    }

    /** 容忍 ```json 包裹与前后废话：截取首尾花括号后解析；失败返回 null 视为 LLM 失败 */
    private Map<String, Object> parseClassifySuggestion(String resp) {
        try {
            String json = resp;
            int start = resp.indexOf('{');
            int end = resp.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = resp.substring(start, end + 1);
            }
            JsonNode node = objectMapper.readTree(json);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("code", node.path("code").asText("").trim());
            s.put("name", node.path("name").asText("").trim());
            s.put("domainCode", node.path("domainCode").asText("").trim());
            s.put("definition", node.path("definition").asText(""));
            s.put("reason", node.path("reason").asText(""));
            return s;
        } catch (Exception e) {
            log.warn("LLM 归类建议解析失败（走降级）: {}", e.getMessage());
            return null;
        }
    }

    /** LLM 失败兜底：结构不变，degraded=true 明示前端走手工填写 */
    private Map<String, Object> degradedSuggestion(OntologyMiss miss) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", "");
        s.put("name", miss.getTerm());
        s.put("domainCode", "");
        s.put("definition", "");
        s.put("reason", "AI 服务不可用，请手工填写");
        s.put("codeTaken", false);
        s.put("domainValid", false);
        s.put("degraded", true);
        return s;
    }

    private OntologyMiss requireMiss(Long id) {
        OntologyMiss miss = getById(id);
        if (miss == null) {
            throw new BizException("miss 不存在: " + id);
        }
        return miss;
    }
}
