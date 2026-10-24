package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Domain;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.entity.OntologyProposal;
import com.bemodel.ontology.entity.ProposalRun;
import com.bemodel.ontology.mapper.DomainMapper;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import com.bemodel.ontology.mapper.OntologyProposalMapper;
import com.bemodel.ontology.mapper.ProposalRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 本体提案生成器（P1a，分层自治 L2：AI 提议、人裁决）。
 * 管线：miss 池选样（CONCEPT 类 Top-N）→ 逐条 LLM 候选卡 + 近义预审（一次调用，
 * 全量概念清单在提示词里，新建/挂靠二选一）→ 同判定归并聚类 → 提案队列（PENDING）。
 * 裁决复用 miss 采纳机制：NEW_CONCEPT 走 adopt（只建 DRAFT，AI 不发布），
 * ATTACH_TERM 走 adoptAsTerm（挂方言术语）。LLM 不可用时诚实留痕不编提案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OntologyProposalService extends ServiceImpl<OntologyProposalMapper, OntologyProposal> {

    @Value("${bemodel.domain-context:工业供应链管理}")
    private String domainContext = "工业供应链管理";

    private final OntologyMissMapper missMapper;
    private final ProposalRunMapper runMapper;
    private final MissService missService;
    private final ConceptService conceptService;
    private final DomainMapper domainMapper;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    /** 每次生成最多处理多少条 miss（= 每次 LLM 调用上限） */
    @Value("${bemodel.proposal.top-n:10}")
    private int topN;

    /**
     * 生成一轮提案：选样 → LLM 候选卡 → 归并 → 落队列 + 运行记录。
     * 全失败时运行记录写明原因（LLM 不可用不编提案），信号保留在 miss 池等下个周期。
     * synchronized（对抗评审）：选样防重是 check-then-act，并发触发（手动×2、手动撞 cron）
     * 会为同一批 miss 生成重复 PENDING 提案；单实例部署下方法级互斥足够，
     * 串行化后第二次 run 的选样已能看到第一次落库的 PENDING 提案，不再重复。
     */
    public synchronized Map<String, Object> run() {
        List<OntologyMiss> candidates = selectCandidateMisses();
        Map<String, Group> groups = new LinkedHashMap<>();
        int llmFailed = 0;
        for (OntologyMiss miss : candidates) {
            Map<String, Object> card = suggest(miss);
            if (card == null) {
                llmFailed++;
                continue;
            }
            String action = str(card.get("action"));
            String key = action + ":" + ("ATTACH_TERM".equals(action)
                    ? str(card.get("targetConceptCode")) : str(card.get("code")));
            Group g = groups.get(key);
            if (g == null) {
                // Group 构造器已收录首条 miss，此处不能再 add（会重复计数）
                groups.put(key, new Group(action,
                        "ATTACH_TERM".equals(action) ? str(card.get("targetConceptCode")) : null, miss, card));
            } else {
                g.add(miss);
            }
        }
        ProposalRun run = new ProposalRun();
        run.setScanned(candidates.size());
        run.setGeneratedCnt(groups.size());
        run.setLlmFailed(llmFailed);
        if (candidates.isEmpty()) {
            run.setMessage("无待处理的概念类缺口信号");
        } else if (llmFailed == candidates.size()) {
            run.setMessage("LLM 不可用，本次未生成提案（信号保留在 miss 池，下个周期重试）");
        }
        runMapper.insert(run);
        for (Group g : groups.values()) {
            insertProposal(g, run.getId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", candidates.size());
        result.put("generated", groups.size());
        result.put("llmFailed", llmFailed);
        result.put("runId", run.getId());
        return result;
    }

    /**
     * 选样（包级可见，测试直测选样规则）：待处理（isPending 单一口径在 MissService）的
     * CONCEPT 类 miss，排除已被任何 PENDING 提案引用的（含 miss_ids JSON 内非主信号，
     * 驳回的提案不挡路），按热度降序取 Top-N。
     */
    List<OntologyMiss> selectCandidateMisses() {
        Set<Long> blocked = new HashSet<>();
        for (OntologyProposal p : lambdaQuery().eq(OntologyProposal::getStatus, "PENDING").list()) {
            blocked.addAll(parseMissIds(p.getMissIds()));
        }
        return missMapper.selectList(new LambdaQueryWrapper<OntologyMiss>()
                        .eq(OntologyMiss::getKind, "CONCEPT")
                        .orderByDesc(OntologyMiss::getCount).orderByAsc(OntologyMiss::getId))
                .stream()
                .filter(missService::isPending)
                .filter(m -> !blocked.contains(m.getId()))
                .limit(topN)
                .toList();
    }

    /**
     * 单 miss → 候选卡（含近义预审与确定性后校验 warnings）。
     * LLM 不可用 / 返回无法解析 / 判定失效（编码名称空白、挂靠无目标）→ 返回 null 视同失败。
     * 后校验只加 warnings 不改判定——队列下游就是人，改写不如如实标注。
     */
    private Map<String, Object> suggest(OntologyMiss miss) {
        Optional<String> llm = deepSeekClient.chat("MISS_PROPOSAL",
                "你是" + domainContext + "本体治理助手，为词表外说法做近义预审并给出候选概念卡。只返回JSON，不要多余文字。",
                buildProposalPrompt(miss));
        if (llm.isEmpty()) {
            return null;
        }
        Map<String, Object> card = parseJsonCard(llm.get());
        if (card == null) {
            return null;
        }
        String action = str(card.get("action"));
        List<String> warnings = new ArrayList<>();
        if ("NEW_CONCEPT".equals(action)) {
            if (str(card.get("code")).isBlank() || str(card.get("name")).isBlank()) {
                return null;
            }
            if (conceptService.getByCode(str(card.get("code"))) != null) {
                warnings.add("建议编码已存在: " + str(card.get("code")) + "（考虑改判挂靠）");
            }
            String domain = str(card.get("domainCode"));
            Long domainCnt = domain.isBlank() ? 0L
                    : domainMapper.selectCount(new LambdaQueryWrapper<Domain>().eq(Domain::getCode, domain));
            if (domainCnt == null || domainCnt == 0) {
                warnings.add("建议业务域不存在或为空: " + domain);
            }
        } else if ("ATTACH_TERM".equals(action)) {
            if (str(card.get("targetConceptCode")).isBlank()) {
                return null;
            }
            if (conceptService.getByCode(str(card.get("targetConceptCode"))) == null) {
                warnings.add("挂靠目标不存在: " + str(card.get("targetConceptCode")));
            }
        } else {
            return null;
        }
        card.put("warnings", warnings);
        return card;
    }

    /**
     * 提示词：本体上下文段复用 MissService.buildOntologyContext（域清单 + 按域分组概念清单），
     * 近义预审内建在同一次调用——判定二选一：新建标准概念，或既有概念就是归宿（归并为方言术语）。
     */
    private String buildProposalPrompt(OntologyMiss miss) {
        StringBuilder sb = new StringBuilder();
        sb.append("词表外说法：\"").append(miss.getTerm()).append("\"（类型 ").append(miss.getKind())
                .append("，来源 ").append(miss.getSource())
                .append("，累计出现 ").append(miss.getCount()).append(" 次）\n\n");
        sb.append(missService.buildOntologyContext());
        sb.append("\n请先对照现有概念做近义预审，再二选一判定：\n");
        sb.append("1) action=NEW_CONCEPT：该说法值得新建标准概念，给出候选编码/名称/域/定义；\n");
        sb.append("2) action=ATTACH_TERM：已有概念就是该说法的归宿，targetConceptCode 填既有概念编码");
        sb.append("（含义是把该说法作为其方言术语/别名归并，不是新建）。\n");
        sb.append("只返回JSON对象：{\"action\":\"NEW_CONCEPT|ATTACH_TERM\",");
        sb.append("\"code\":\"仅NEW_CONCEPT：稳定业务编码（风格参考 Lot、Demand）\",");
        sb.append("\"name\":\"仅NEW_CONCEPT：中文名称\",");
        sb.append("\"domainCode\":\"仅NEW_CONCEPT：业务域编码\",");
        sb.append("\"definition\":\"仅NEW_CONCEPT：业务定义\",");
        sb.append("\"targetConceptCode\":\"仅ATTACH_TERM：挂靠的既有概念编码\",");
        sb.append("\"reason\":\"一句中文人话解释判定理由\",\"confidence\":0到1的小数}。只返回JSON。");
        return sb.toString();
    }

    /** 容忍 ```json 包裹与前后废话：截取首尾花括号后解析；失败返回 null 视为 LLM 失败 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonCard(String resp) {
        try {
            String json = resp;
            int start = resp.indexOf('{');
            int end = resp.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = resp.substring(start, end + 1);
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("提案候选卡解析失败（视同 LLM 失败）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 采纳 NEW_CONCEPT：首 miss 走 adopt()（全套校验并创建 DRAFT 概念，自动化不发布），
     * 组内其余 miss 只挂采纳标记（attachAdoption）到同一概念；整体事务——部分失败全回滚，
     * 不留「概念建了、一半 miss 还挂池中」的中间态。ATTACH_TERM：逐 miss adoptAsTerm
     * （同 term+concept 幂等，术语行可被 revoke 精确回收）。
     */
    @Transactional
    public OntologyProposal adopt(Long id) {
        OntologyProposal p = requireProposal(id);
        // 裁决占位（对抗评审）：WHERE status='PENDING' 的条件更新做守卫，行锁把并发裁决串行化，
        // 后到者 affected=0——原「读 status 内存判 + 尾部 updateById 盲写」存在 adopt×reject 竞态
        // （REJECTED 掩盖已采纳事实/残留驳回理由脏行）与双 adopt 撞唯一键裸 500 的窗口。
        // 占位在事务内：组内校验/miss 动作失败时随事务回滚，PENDING 释放，他人可重新裁决。
        boolean claimed = lambdaUpdate()
                .eq(OntologyProposal::getId, id)
                .eq(OntologyProposal::getStatus, "PENDING")
                .set(OntologyProposal::getStatus, "ADOPTED")
                .set(OntologyProposal::getDecidedBy, CurrentUser.username())
                .set(OntologyProposal::getDecidedAt, LocalDateTime.now())
                .update();
        if (!claimed) {
            throw new BizException("该提案已裁决（" + requireProposal(id).getStatus() + "），不可重复操作");
        }
        List<Long> missIds = parseMissIds(p.getMissIds());
        // 组内信号前置校验（对抗评审）：选样守卫是单向的（提案挡 miss 再入队），反向不成立——
        // PENDING 期间组内 miss 可被人工先行采纳/忽略，此时采纳必失败且提案永久卡死。
        // 结构化列出已处置信号给清晰出路；校验在占位更新之后同事务，失败整体回滚。
        List<String> gone = new ArrayList<>();
        for (OntologyMiss m : missMapper.selectBatchIds(missIds)) {
            if (!missService.isPending(m)) {
                gone.add(m.getTerm() + (Integer.valueOf(1).equals(m.getDismissed())
                        ? "（已忽略）" : "（已采纳为 " + m.getAdoptedConceptCode() + "）"));
            }
        }
        if (!gone.isEmpty()) {
            throw new BizException("组内信号已被人工处置，提案不再可采纳: " + String.join("、", gone)
                    + "。请驳回本提案，信号去向见 miss 看板。");
        }
        if ("NEW_CONCEPT".equals(p.getAction())) {
            Map<String, Object> card = parseJsonCard(p.getSuggestionJson());
            if (card == null) {
                throw new BizException("候选卡数据缺失或损坏，无法采纳");
            }
            String code = str(card.get("code"));
            String name = str(card.get("name"));
            String domainCode = str(card.get("domainCode"));
            String definition = str(card.get("definition"));
            if (code.isBlank() || name.isBlank()) {
                throw new BizException("候选卡缺少编码或名称，无法采纳为概念");
            }
            missService.adopt(p.getPrimaryMissId(), code, name, domainCode, definition);
            for (Long mid : missIds) {
                if (!mid.equals(p.getPrimaryMissId())) {
                    missService.attachAdoption(mid, code);
                }
            }
        } else if ("ATTACH_TERM".equals(p.getAction())) {
            String target = str(p.getTargetConceptCode());
            if (target.isBlank()) {
                throw new BizException("提案缺少挂靠目标概念，无法采纳");
            }
            for (Long mid : missIds) {
                missService.adoptAsTerm(mid, target);
            }
        } else {
            throw new BizException("未知提案判定: " + p.getAction());
        }
        return getById(id);
    }

    /** 驳回：只翻提案状态，miss 不打任何标记——下次 run 该信号可再入队（驳回的是提案不是信号）。
     * 守卫同 adopt：条件更新占位，并发裁决后到者 affected=0 */
    public OntologyProposal reject(Long id, String reason) {
        OntologyProposal p = requireProposal(id);
        if (reason != null && reason.length() > 255) {
            reason = reason.substring(0, 255);
        }
        boolean claimed = lambdaUpdate()
                .eq(OntologyProposal::getId, id)
                .eq(OntologyProposal::getStatus, "PENDING")
                .set(OntologyProposal::getStatus, "REJECTED")
                .set(OntologyProposal::getDecidedBy, CurrentUser.username())
                .set(OntologyProposal::getDecidedAt, LocalDateTime.now())
                .set(OntologyProposal::getRejectReason, reason)
                .update();
        if (!claimed) {
            throw new BizException("该提案已裁决（" + requireProposal(id).getStatus() + "），不可重复操作");
        }
        return getById(id);
    }

    /** 提案队列分页：PENDING 在前，已裁决按 decided_at 倒序（对位澄清任务列表排序） */
    public PageResult<OntologyProposal> page(String status, int pageNum, int pageSize) {
        LambdaQueryWrapper<OntologyProposal> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            qw.eq(OntologyProposal::getStatus, status.trim().toUpperCase());
        }
        long total = count(qw);
        qw.last("ORDER BY FIELD(status, 'PENDING', 'ADOPTED', 'REJECTED'), decided_at DESC, id DESC LIMIT "
                + pageSize + " OFFSET " + (long) (pageNum - 1) * pageSize);
        return PageResult.of(list(qw), total, pageNum, pageSize);
    }

    private OntologyProposal requireProposal(Long id) {
        OntologyProposal p = getById(id);
        if (p == null) {
            throw new BizException("提案不存在: id=" + id);
        }
        return p;
    }

    private List<Long> parseMissIds(String json) {
        try {
            List<Long> ids = new ArrayList<>();
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isArray()) {
                node.forEach(n -> ids.add(n.asLong()));
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 同判定归并的聚合：missIds 全组、primary=count 最高（平局取先到）、card 取组内首张 */
    private static final class Group {
        final String action;
        final String target;
        final List<Long> missIds = new ArrayList<>();
        final Map<String, Object> card;
        OntologyMiss primary;
        long signalSum;

        Group(String action, String target, OntologyMiss first, Map<String, Object> card) {
            this.action = action;
            this.target = target;
            this.card = card;
            this.primary = first;
            this.signalSum = first.getCount() == null ? 0 : first.getCount();
            this.missIds.add(first.getId());
        }

        void add(OntologyMiss m) {
            missIds.add(m.getId());
            signalSum += m.getCount() == null ? 0 : m.getCount();
            if (m.getCount() != null && (primary.getCount() == null || m.getCount() > primary.getCount())) {
                primary = m;
            }
        }
    }

    /** 落一张提案卡：归并多信号时在候选卡 warnings 里注明归并数 */
    private void insertProposal(Group g, Long runId) {
        if (g.missIds.size() > 1) {
            Object w = g.card.get("warnings");
            if (w instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<String> warnings = (List<String>) list;
                warnings.add("已归并 " + g.missIds.size() + " 条同判定信号");
            }
        }
        OntologyProposal p = new OntologyProposal();
        p.setPrimaryMissId(g.primary.getId());
        try {
            p.setMissIds(objectMapper.writeValueAsString(g.missIds));
        } catch (Exception e) {
            throw new BizException("miss_ids 序列化失败: " + e.getMessage());
        }
        p.setTerm(g.primary.getTerm());
        p.setSignalCount((int) g.signalSum);
        p.setAction(g.action);
        p.setTargetConceptCode(g.target);
        try {
            p.setSuggestionJson(objectMapper.writeValueAsString(g.card));
        } catch (Exception e) {
            throw new BizException("候选卡序列化失败: " + e.getMessage());
        }
        p.setStatus("PENDING");
        p.setRunId(runId);
        save(p);
    }
}
