package com.bemodel.cs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.bemodel.cs.mapper.CsFeedbackMapper;
import com.bemodel.cs.mapper.QaTraceMapper;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.knowledge.entity.Knowledge;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.service.LinkService;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.service.MissService;
import com.bemodel.rca.entity.RcaCase;
import com.bemodel.rca.entity.RcaReport;
import com.bemodel.rca.mapper.RcaCaseMapper;
import com.bemodel.rca.mapper.RcaReportMapper;
import com.bemodel.rca.service.RcaEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 智慧客服：客服打开工单时平台自动完成诊断（复用 RCA 探针引擎），
 * 产出结论/证据/处置建议/客户话术；支持一键处置（生成退费申请 + 处置单），
 * 形成「客诉 → 自动诊断 → 处置 → 办结」的客服工作闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsService {

    private final LinkService linkService;
    private final RcaEngine rcaEngine;
    private final RcaCaseMapper caseMapper;
    private final RcaReportMapper reportMapper;
    private final DatasourceService datasourceService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;
    private final com.bemodel.flow.FlowService flowService;
    private final com.bemodel.search.SearchService searchService;
    private final SemanticQaService semanticQaService;
    private final SemanticSceneProperties sceneProps;
    private final CsFeedbackMapper csFeedbackMapper;
    private final com.bemodel.ontology.mapper.ConceptMapper conceptMapper;
    private final com.bemodel.ontology.mapper.MetricMapper metricMapper;
    private final ReconciliationService reconciliationService;
    private final ClarifyService clarifyService;
    private final com.bemodel.knowledge.KnowledgeSearchService knowledgeSearchService;
    private final MissService missService;
    private final QaTraceMapper qaTraceMapper;
    private final com.bemodel.knowledge.KnowledgeService knowledgeService;

    /**
     * 工单智能诊断：已有完成的诊断直接复用，否则自动执行（客服无感，打开即得结论）。
     * 返回：诊断过程 + 报告 + 处置建议 + 给客户的回复话术。
     */
    public Map<String, Object> diagnosis(Long ticketId) {
        LinkNode ticket = ticket(ticketId);
        RcaCase rcaCase = caseMapper.selectOne(new LambdaQueryWrapper<RcaCase>()
                .eq(RcaCase::getTicketRef, ticket.getRefNo()).orderByDesc(RcaCase::getId).last("LIMIT 1"));
        boolean fresh = false;
        if (rcaCase == null || !"DONE".equals(rcaCase.getStatus()) || legacyStepNames(rcaCase.getId())) {
            // 旧版案例的步骤名是「探针Pn」技术措辞，自动重跑为业务语言版本
            rcaCase = rcaEngine.start(ticket.getRefNo());
            fresh = true;
        }

        Map<String, Object> detail = rcaEngine.caseDetail(rcaCase.getId());
        RcaReport report = (RcaReport) detail.get("report");

        Map<String, Object> result = new LinkedHashMap<>(detail);
        result.put("ticket", ticket);
        result.put("fresh", fresh);
        result.put("suggestions", parseJson(report == null ? null : report.getSuggestionsJson()));
        result.put("impact", parseJson(report == null ? null : report.getImpactJson()));
        result.put("customerReply", customerReply(ticket, rcaCase, report));
        return result;
    }

    /**
     * 一键处置：按诊断结论生成退费申请（demo_charge.refund_apply，申请中），
     * 登记处置单（link_node.DISPOSAL），工单办结。
     */
    @Transactional
    public Map<String, Object> refundAction(Long ticketId, String operator) {
        LinkNode ticket = ticket(ticketId);
        if ("已处置".equals(ticket.getStatus())) {
            throw new BizException("工单已处置，无需重复操作");
        }

        // 影响面费用（与 RCA 探针 P4 同一口径：医嘱已取消但费用未退）
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        List<Map<String, Object>> affected = his.queryForList(
                "SELECT f.fee_id, f.inhos_no, f.item_name, f.amount FROM fee_detail f " +
                        "JOIN medical_order o ON f.order_id = o.order_id " +
                        "WHERE o.order_status = '2' AND f.fee_status = '1'");

        JdbcTemplate charge = datasourceService.jdbc("DS_CHARGE");
        BigDecimal total = BigDecimal.ZERO;
        List<String> refundIds = new ArrayList<>();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        for (Map<String, Object> fee : affected) {
            String refundId = "RA" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + String.format("%04d", refundIds.size() + 1);
            charge.update("INSERT INTO refund_apply(refund_id,inhos_no,fee_id,amount,reason,apply_time,status) VALUES(?,?,?,?,?,?,?)",
                    refundId, fee.get("inhos_no"), fee.get("fee_id"), fee.get("amount"),
                    "客诉「撤销后仍收费」批量退费（工单 " + ticket.getRefNo() + "，经办 " + operator + "）",
                    now, "0");
            refundIds.add(refundId);
            total = total.add((BigDecimal) fee.get("amount"));
        }

        // 登记处置单（链路节点：处置进入概念的全生命周期视图）
        LinkNode disposal = new LinkNode();
        disposal.setNodeType("DISPOSAL");
        disposal.setRefNo("DP-" + ticket.getRefNo());
        disposal.setTitle("批量退费处置：" + refundIds.size() + " 笔，合计 ¥" + total);
        disposal.setConceptCode(ticket.getConceptCode());
        disposal.setStatus("已完成");
        disposal.setOccurredAt(LocalDateTime.now());
        disposal.setPayload("{\"ticketRef\":\"" + ticket.getRefNo() + "\",\"refundIds\":"
                + refundIds.size() + ",\"totalAmount\":" + total + ",\"operator\":\"" + operator + "\"}");
        linkService.save(disposal);

        // 工单办结
        ticket.setStatus("已处置");
        linkService.updateById(ticket);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundCount", refundIds.size());
        result.put("totalAmount", total);
        result.put("refundIds", refundIds);
        result.put("disposalRef", disposal.getRefNo());
        return result;
    }

    /** 给客户看的回复话术：LLM 生成（带知识库整改依据；无 Key 降级为带真实诊断结论的模板，不编根因） */
    private String customerReply(LinkNode ticket, RcaCase rcaCase, RcaReport report) {
        String rootCause = report == null ? rcaCase.getConclusion() : report.getRootCause();
        // 出处引用（spec §7.3）：回复文本末尾引用依据条目 code；检索不到就不提条文
        List<Knowledge> basis = rcaCase == null ? List.of()
                : knowledgeService.basisEntries(rcaCase.getConceptCode());
        String citation = basis.isEmpty() ? ""
                : "\n\n（整改依据条目：" + String.join("、", basis.stream().map(Knowledge::getCode).toList()) + "）";
        if (deepSeekClient.enabled()) {
            String basisText = basis.isEmpty() ? "" : knowledgeService.basisText(basis);

            String user = "你是医院客服主管。根据以下客诉工单与平台诊断结论，写一段给客户的中文回复"
                    + "（150字内，先致歉，再说清原因与整改措施，口语化，不要技术术语）。\n"
                    + "工单：" + ticket.getTitle() + "\n诊断结论：" + rootCause
                    + (basisText.isBlank() ? "" : "\n整改措施依据（整改部分务必对应，依据里没有的条文不要编）：\n" + basisText);
            Optional<String> reply = deepSeekClient.chat("CS_REPLY",
                    "你是医院客服主管，回复要专业、诚恳、简短。", user);
            if (reply.isPresent()) {
                return reply.get() + citation;
            }
        }
        String honest = rootCause == null || rootCause.isBlank()
                ? "具体原因正在人工复核"
                : "经排查：" + rootCause;
        return "您好，非常抱歉给您带来了困扰。您反馈的「" + ticket.getTitle() + "」我们已收到并核实。" + honest
                + "。我们将按核查结果处理相关费用并跟进反馈，感谢您的监督与理解。" + citation;
    }

    /** 包私有供测试：降级诚实化直测 */
    String customerReplyForTest(LinkNode ticket, RcaCase rcaCase, RcaReport report) {
        return customerReply(ticket, rcaCase, report);
    }

    private boolean legacyStepNames(Long caseId) {
        RcaReport report = reportMapper.selectOne(
                new LambdaQueryWrapper<RcaReport>().eq(RcaReport::getCaseId, caseId));
        if (report != null && report.getEvidenceJson() != null && report.getEvidenceJson().contains("\"P1 ")) {
            return true;
        }
        return rcaEngine.caseDetail(caseId).get("steps") instanceof List<?> steps
                && steps.stream().anyMatch(s -> String.valueOf(
                        ((com.bemodel.rca.entity.RcaStep) s).getStepName()).startsWith("探针P"));
    }

    private LinkNode ticket(Long ticketId) {
        LinkNode ticket = linkService.getById(ticketId);
        if (ticket == null || !"TICKET".equals(ticket.getNodeType())) {
            throw new BizException("客服工单不存在: " + ticketId);
        }
        return ticket;
    }

    // ==================== 问一问：免培训的自然语言入口 ====================

    /** 提问场景：CS=AI客服（答「这笔业务该怎么办」）；ANALYTICS=智能问数（答「数字是多少/口径是什么」） */
    public static final String SCENE_CS = "CS";
    public static final String SCENE_ANALYTICS = "ANALYTICS";

    static String normalizeScene(String scene) {
        return SCENE_ANALYTICS.equalsIgnoreCase(scene == null ? "" : scene.trim())
                ? SCENE_ANALYTICS : SCENE_CS;
    }

    /** 兼容旧调用（工单诊断反馈回路/历史测试）：缺省按客服场景 */
    public Map<String, Object> ask(String question) {
        return ask(question, SCENE_CS);
    }

    /**
     * 场景化问答：同一语义引擎按提问场景走不同路由偏好与兜底形态——
     * CS=客服：8 类专属处置意图 + 语义层，兜底能力菜单（转介问数）；
     * ANALYTICS=问数：口径进 Glossary、业务事实一律进语义层（Ontology2SQL），
     * 兜底场景菜单（转介客服）。LLM 只做意图归类，数字一律来自真实查询。
     */
    public Map<String, Object> ask(String question, String scene) {
        return ask(question, scene, null);
    }

    /** resumeTask 非空 = 澄清任务续跑重入：A 型歧义时推进轮次/封顶，而不是新建任务 */
    private Map<String, Object> ask(String question, String scene, ClarifyTask resumeTask) {
        String q = question == null ? "" : question.trim();
        boolean analytics = SCENE_ANALYTICS.equals(normalizeScene(scene));
        String intent = null;
        String router = "NONE";
        // 问数场景：显式口径触发词先试指标口径卡（口径与探针SQL在指标库，语义层答不了公式类问题）。
        // 仅名称直命中的指标真出卡时才提前返回（router=RULE，未命中不记缺口）；
        // 概念/术语文本命中继续原路由——数据型问法（如「缴费总额怎么算」）仍可被 LLM 改判进语义层查真实数据
        if (analytics && matches(q, "口径", "怎么算", "什么是", "什么叫", "定义")) {
            Map<String, Object> g = glossaryAnswer(q, false, true);
            if (g != null && "METRIC".equals(g.get("card"))) {
                g.putIfAbsent("router", "RULE");
                if (resumeTask != null) {
                    g.put("clarifyAnswered", true);
                }
                return g;
            }
        }
        if (deepSeekClient.enabled()) {
            intent = routeByLlm(q, analytics);
            if (intent != null) {
                router = "LLM";
            }
        }
        if (intent == null) {
            intent = routeByKeyword(q, analytics);
            if (intent != null) {
                router = "RULE";
            }
        }
        // 语义路径单列：答不了时按缺口分型处理——A 型歧义建澄清任务（不记 miss），
        // V/P 型照旧回流增长回路；missRecorded/clarifyTask 均为后端显式字段，前端按存在性渲染
        if ("SEMANTIC_QUERY".equals(intent)) {
            SemanticQaService.Outcome outcome = semanticQaService.answer(q, analytics);
            if (outcome.result() != null) {
                outcome.result().putIfAbsent("router", router); // 语义查询处理器自带 router=SEMANTIC
                if (resumeTask != null) {
                    outcome.result().put("clarifyAnswered", true);
                }
                return outcome.result();
            }
            Map<String, Object> menu = analytics ? analyticsMenu(q) : capabilityMenu(q);
            menu.put("router", router);
            // A 型歧义：建任务或续跑推进（轮次封顶 → GAVE_UP 回流），全程不记 miss
            if ("AMBIGUITY".equals(outcome.gapType())) {
                ClarifyTask task = resumeTask == null
                        ? clarifyService.createTask(normalizeScene(scene), q, outcome.reason(), "AMBIGUITY")
                        : null;
                if (task != null) {
                    menu.put("clarifyTask", clarifyService.view(task));
                    return menu;
                }
                if (clarifyService.enterNextRound(resumeTask, outcome.reason())) {
                    menu.put("clarifyTask", clarifyService.view(resumeTask));
                } else {
                    // 封顶（rounds=2）：任务置 GAVE_UP，问题此时才回流增长回路（miss 附任务对话证据）
                    clarifyService.giveUp(resumeTask);
                    menu.put("missRecorded", true);
                    menu.put("clarifyGaveUp", true);
                    menu.put("answer", menu.get("answer")
                            + "两轮澄清仍未收敛，这个问题已连同一轮澄清证据记录为本体完善提案（本体页-扩展提案可见）。");
                }
                return menu;
            }
            if (outcome.recordedMiss()) {
                menu.put("missRecorded", true);
                menu.put("answer", menu.get("answer")
                        + "这个问题已记录为本体完善提案（本体页-扩展提案可见）。");
                if (resumeTask != null) {
                    // 续跑途中缺口转定性为 V 型（词表缺口）：任务收口 GAVE_UP，miss_id 回填（miss 已由标准路径记录）
                    clarifyService.giveUp(resumeTask);
                    menu.put("clarifyGaveUp", true);
                }
                return menu;
            }
            if (resumeTask != null) {
                // 续跑中 LLM 计划失败（非缺口）：任务保持 PENDING，用户可重试补充，不断链不伪装
                menu.put("clarifyRetry", true);
            }
            return menu;
        }
        Map<String, Object> r = intent == null ? null : dispatch(intent, q);
        if (r != null) {
            r.putIfAbsent("router", router);
            if (resumeTask != null) {
                r.put("clarifyAnswered", true);
            }
            return r;
        }
        // 未命中 → 场景化兜底菜单（内含向另一场景带原问题的转介入口），显式 REFERRAL 标记
        Map<String, Object> menu = analytics ? analyticsMenu(q) : capabilityMenu(q);
        menu.put("router", intent != null ? router : "REFERRAL");
        return menu;
    }

    /**
     * 澄清续跑（D2b）：q' = 原问题 +（补充）重入同一路由——resume pointer 有意做到最薄，
     * 不引入步骤状态机。补充只作为查询约束注入提问，不成为事实来源；
     * 结果三态：真实答案→RESOLVED（带 clarifyResolved）；二轮追问卡；封顶/转定性→GAVE_UP 回流。
     */
    public Map<String, Object> clarifyAnswer(Long id, String supplement) {
        ClarifyTask task = clarifyService.acceptSupplement(id, supplement);
        // 结构化拼接，不做自由改写：原问题（补充约束）重入问数路由
        Map<String, Object> r = ask(task.getOriginQuestion() + "（" + task.getSupplement() + "）",
                SCENE_ANALYTICS, task);
        r.put("clarifyTaskId", task.getId());
        if (r.containsKey("clarifyTask") || Boolean.TRUE.equals(r.get("clarifyGaveUp"))
                || Boolean.TRUE.equals(r.get("clarifyRetry"))) {
            return r;
        }
        if (Boolean.TRUE.equals(r.get("clarifyAnswered"))) {
            // 真实答案由 ask() 显式标记（口径卡/语义结果/意图答出），不做「失败标记缺席」的反向推断：
            // 兜底菜单不是答案，任务保持 PENDING，可换措辞再补（否则菜单会被误标「已答出」）
            clarifyService.resolve(task);
            r.put("clarifyResolved", true);
            return r;
        }
        // 兜底菜单：补充已记录但本轮未命中答案，任务保持 PENDING 不误标 RESOLVED
        r.put("clarifyRetry", true);
        return r;
    }

    private String routeByKeyword(String q, boolean analytics) {
        if (analytics) {
            // 问数场景：口径定义留 Glossary，其余业务词一律进语义层（本体已映射这些表，可真实查询）
            if (matches(q, "口径", "怎么算", "什么是", "什么叫", "定义")) {
                return "GLOSSARY";
            }
            if (matches(q, "取消", "撤销", "未退", "多收", "退费", "收费", "分开发药", "分次发药", "多次发药",
                    "拆零", "分批", "退药", "退掉", "退货", "发药", "拿药", "取药", "买药", "药费", "缴费",
                    "耗材", "物资", "申领", "库存", "医生", "护士", "药师", "技师", "谁", "科室", "人员",
                    "多少", "哪些", "几条", "统计", "记录", "名单", "合计", "总额", "排名")) {
                return "SEMANTIC_QUERY";
            }
            return null;
        }
        if (matches(q, "取消", "撤销", "未退", "多收", "退费", "收费")) {
            return "FEE";
        }
        if (matches(q, "口径", "怎么算", "什么是", "什么叫", "定义")) {
            return "GLOSSARY";
        }
        if (matches(q, "分开发药", "分次发药", "多次发药", "拆零", "分批")) {
            return "DISPENSE_SPLIT";
        }
        if (matches(q, "退药", "退掉", "退货")) {
            return "DISPENSE_RETURN";
        }
        if (matches(q, "发药", "拿药", "取药", "买药", "药费", "缴费")) {
            return "DISPENSE_PAY";
        }
        if (matches(q, "耗材", "物资", "申领", "库存")) {
            return "MATERIAL";
        }
        if (matches(q, "医生", "护士", "药师", "技师", "谁", "科室", "人员")) {
            return "STAFF";
        }
        if (matches(q, "规定", "制度", "政策", "规章", "第几条", "条文", "流程")) {
            return "DOC_QA";
        }
        return null;
    }

    private String routeByLlm(String q, boolean analytics) {
        Optional<String> r = deepSeekClient.chat("CS_ROUTE",
                "你是医院信息平台的意图分类器，只输出标签本身，不要任何解释。",
                analytics ? analyticsRoutePrompt(q) : routePrompt(q));
        if (r.isEmpty()) {
            return null;
        }
        String label = r.get().trim().toUpperCase().replaceAll("[^A-Z_]", "");
        if (analytics) {
            // 问数场景只认两类标签，客服处置意图不可达
            return "GLOSSARY".equals(label) || "SEMANTIC_QUERY".equals(label) ? label : null;
        }
        return switch (label) {
            case "FEE", "DISPENSE_PAY", "DISPENSE_SPLIT", "DISPENSE_RETURN", "MATERIAL", "STAFF", "GLOSSARY",
                 "SEMANTIC_QUERY", "DOC_QA" -> label;
            default -> null;
        };
    }

    /** 路由提示词：标签说明 + 错例反馈回路（最近 10 条 correct=0 的评议附加在尾部）。包私有供测试 */
    String routePrompt(String q) {
        StringBuilder sb = new StringBuilder(
                "把下面的用户问题分到最合适的一类，只回答标签（一个词）：\n"
                        + "FEE=费用投诉工单（取消未退费/多收费的投诉排查与处置）；\n"
                        + "DISPENSE_PAY=缴费与发药的双向核对（没缴费能否发药、已缴费未发药滞留）；\n"
                        + "DISPENSE_SPLIT=一个医嘱能否拆成多次/分开发药的规则咨询；\n"
                        + "DISPENSE_RETURN=发药后的退药规则咨询（部分退药、退药退费联动）；\n"
                        + "MATERIAL=耗材申领流程咨询；\n"
                        + "STAFF=人员归属（某人是谁/哪个科室/什么职称）；\n"
                        + "GLOSSARY=指标口径与名词定义（怎么算/什么是/叫什么）；\n"
                        + "SEMANTIC_QUERY=对业务事实的开放查询（数量/明细/统计/状态/库存/金额/名单），"
                        + "以及缴费发药域之外的「能不能/可不可以/是否允许」类业务规则问题"
                        + "（如合并结算、跨科室发药等），平台可按本体映射直接查业务库或按本体结构推理回答；\n"
                        + "DOC_QA=规章制度/流程规定/政策依据类问题（某项制度怎么规定、第几条怎么写、"
                        + "有什么要求），平台检索知识库制度文档作答并引用出处；\n"
                        + "OTHER=以上都不是。\n"
                        + "注意：FEE/DISPENSE_PAY/DISPENSE_SPLIT/DISPENSE_RETURN/MATERIAL/STAFF/GLOSSARY/DOC_QA 是专属能力，"
                        + "只在问题问规则、流程、投诉、口径定义时选；只要问题是「查一个业务事实」"
                        + "（多少数量、哪些记录、库存还有多少、金额合计、某个状态），一律选 SEMANTIC_QUERY。\n"
                        + "辨析：DISPENSE_PAY 处理「缴费与发药两个方向的对账」（含已缴费未发药滞留）；"
                        + "涉及缴费/发药/退药的「正常吗/可以吗」也归对应 DISPENSE_* 类，不进 SEMANTIC_QUERY；"
                        + "结算方式/合并结算等缴费发药域外的规则问题才选 SEMANTIC_QUERY。\n"
                        + "辨析：问「制度怎么规定」选 DOC_QA（如探视制度、退费规定）；问「业务事实上能不能/是否允许」"
                        + "才选 SEMANTIC_QUERY；\n"
                        + "示例：「已缴费未发药正常吗」→ DISPENSE_PAY；「多个患者的处方可以一起结算吗」→ SEMANTIC_QUERY；"
                        + "「探视制度有什么规定」→ DOC_QA。\n");
        List<CsFeedback> mistakes = csFeedbackMapper.selectList(new LambdaQueryWrapper<CsFeedback>()
                .eq(CsFeedback::getCorrect, 0).orderByDesc(CsFeedback::getId).last("LIMIT 10"));
        if (!mistakes.isEmpty()) {
            sb.append("以下是人工评议员标注的历史误分类（前车之鉴，类似问题不要再错）：\n");
            for (CsFeedback f : mistakes) {
                sb.append("- 问「").append(f.getQuestion()).append("」误归 ").append(f.getIntent());
                if (f.getComment() != null && !f.getComment().isBlank()) {
                    sb.append("；正确应为/备注：").append(f.getComment());
                }
                sb.append('\n');
            }
        }
        sb.append("问题：").append(q);
        return sb.toString();
    }

    /** 问数场景路由提示词：只留口径与语义查询两类，客服处置意图不出现（包私有供测试） */
    String analyticsRoutePrompt(String q) {
        return "把下面的用户问题分到最合适的一类，只回答标签（一个词）：\n"
                + "GLOSSARY=指标口径与名词定义（怎么算/什么是/叫什么/口径定义）；\n"
                + "SEMANTIC_QUERY=对业务事实的开放查询（数量/明细/统计/状态/库存/金额/名单/人员/时间），"
                + "以及业务规则类「能不能/可不可以/是否允许」问题，平台按本体映射查业务库或按本体结构推理回答。\n"
                + "注意：本场景是数据分析问数，客诉排查、工单处置类服务咨询不属于本场景；"
                + "任何带业务词的问题都优先考虑 SEMANTIC_QUERY。\n"
                + "问题：" + q;
    }

    /** 路由反馈：记录一次问答归类评议（viewer 也可反馈） */
    public CsFeedback saveFeedback(String question, String intent, String router,
                                   Integer correct, String comment) {
        if (question == null || question.isBlank()) {
            throw new BizException("question 不能为空");
        }
        CsFeedback f = new CsFeedback();
        f.setQuestion(question.trim());
        f.setIntent(intent);
        f.setRouter(router);
        f.setCorrect(Integer.valueOf(1).equals(correct) ? 1 : 0);
        f.setComment(comment);
        csFeedbackMapper.insert(f);
        return f;
    }

    /** 反馈分页（管理查看） */
    public PageResult<CsFeedback> feedbackPage(int pageNum, int pageSize) {
        long total = csFeedbackMapper.selectCount(null);
        List<CsFeedback> list = csFeedbackMapper.selectList(new LambdaQueryWrapper<CsFeedback>()
                .orderByDesc(CsFeedback::getId)
                .last("LIMIT " + pageSize + " OFFSET " + (pageNum - 1) * pageSize));
        return PageResult.of(list, total, pageNum, pageSize);
    }

    private Map<String, Object> dispatch(String intent, String q) {
        return switch (intent) {
            case "FEE" -> feeAnswer(q);
            case "DISPENSE_PAY" -> dispensePayAnswer(q);
            case "DISPENSE_SPLIT" -> dispenseSplitAnswer(q);
            case "DISPENSE_RETURN" -> dispenseReturnAnswer(q);
            case "MATERIAL" -> materialAnswer(q);
            case "STAFF" -> staffAnswer(q);
            case "GLOSSARY" -> glossaryAnswer(q);
            case "DOC_QA" -> docAnswer(q);
            default -> null;
        };
    }

    private Map<String, Object> baseResult(String q) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", q);
        return result;
    }

    /** 硬编码迁入的统一取句口：条目不可用时给诚实兜底句（不静默变 null 拼接） */
    private String ruleSentence(String code) {
        String content = knowledgeService.entryContent(code);
        return content == null ? "（该场景处置依据整理中，请人工核查）" : content;
    }

    /** 意图：取消未退费 / 多收费 / 客诉排查 */
    private Map<String, Object> feeAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        Map<String, Object> impact = his.queryForMap(
                "SELECT COUNT(DISTINCT f.inhos_no) AS patient_cnt, COUNT(*) AS fee_cnt, " +
                        "IFNULL(SUM(f.amount),0) AS total_amount FROM fee_detail f " +
                        "JOIN medical_order o ON f.order_id = o.order_id " +
                        "WHERE o.order_status = '2' AND f.fee_status = '1'");
        List<LinkNode> tickets = linkService.lambdaQuery()
                .eq(LinkNode::getNodeType, "TICKET").eq(LinkNode::getStatus, "待处理").list();
        result.put("intent", "取消未退费排查");
        result.put("answer", String.format(
                        "当前全院共有 %s 名患者、%s 笔「医嘱已取消但费用未退」，合计 ¥%s。",
                        impact.get("patient_cnt"), impact.get("fee_cnt"), impact.get("total_amount"))
                + ruleSentence("CS_RULE_FEE_ROOT")
                + String.format("现有 %s 张待处理工单，点开即可看 AI 的完整排查过程并一键退费。", tickets.size()));
        result.put("evidence", List.of(
                Map.of("label", "影响患者", "value", impact.get("patient_cnt") + " 人"),
                Map.of("label", "未退费用", "value", impact.get("fee_cnt") + " 笔"),
                Map.of("label", "涉及金额", "value", "¥" + impact.get("total_amount")),
                Map.of("label", "待处理工单", "value", tickets.size() + " 张")));
        result.put("links", tickets.isEmpty()
                ? List.of(Map.of("label", "去链路追溯看费用明细概念", "route", "/link?concept=FEE_DETAIL"))
                : List.of(Map.of("label", "打开待处理工单", "route", "/cs?ticketId=" + tickets.get(0).getId()),
                        Map.of("label", "去链路追溯看费用明细概念", "route", "/link?concept=FEE_DETAIL")));
        return result;
    }

    /** 意图：缴费与发药双向核对（D2 语义化：分档+归因+差异登记）—— 三库实时核对 */
    private Map<String, Object> dispensePayAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        Map<String, Object> recon = reconciliationService.reconcileDispensePay();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) recon.get("tiers");
        int abnormal = (Integer) recon.get("abnormalCount");
        result.put("intent", "缴费发药双向核对（语义档）");
        result.put("answer", "流程规则："
                + ruleSentence("CS_RULE_FEE_DISPENSE_ORDER")
                + String.format("跨三库实测：%d 个差异档，异常合计 %d 项，已逐条登记可查。",
                tiers.size(), abnormal));
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Map<String, Object> t : tiers) {
            evidence.add(Map.of(
                    "label", severityLabel((String) t.get("severity")) + "·" + tierLabel((String) t.get("stateCode")),
                    "value", t.get("count") + " / 共 " + t.get("total") + "｜" + t.get("suggestedAction")));
        }
        evidence.add(Map.of("label", "数据来源", "value",
                "DS_PHARMACY.dispense_record × DS_CHARGE.pay_record × DS_HIS.medical_order×fee_detail，"
                        + "差异已登记 runId=" + recon.get("runId")));
        result.put("evidence", evidence);
        result.put("tiers", tiers);
        result.put("runId", recon.get("runId"));
        result.put("links", List.of(
                Map.of("label", "查本批次差异登记", "route", "/cs?recon=" + recon.get("runId")),
                Map.of("label", "去流程演示页看住院闭环", "route", "/flow"),
                Map.of("label", "去本体页看发药记录概念", "route", "/ontology?concept=DISPENSE")));
        return result;
    }

    private static String severityLabel(String severity) {
        return switch (severity) {
            case "VIOLATION" -> "违规";
            case "WATCH" -> "待核查";
            default -> "正常差异";
        };
    }

    private static String tierLabel(String stateCode) {
        return switch (stateCode) {
            case "VIOLATION_PREPAY_BYPASS" -> "先药后费";
            case "RETURNED_PENDING_REFUND" -> "已退药待退费";
            case "STUCK_BACKLOG" -> "真滞留";
            case "IN_FLIGHT" -> "在途待发";
            case "CANCELLED_AFTER_PAY" -> "付费后取消";
            case "NIGHTLY_CANCEL" -> "凌晨批量取消";
            default -> stateCode;
        };
    }

    /** 意图：一个医嘱能否拆成多次/分开发药 —— 医嘱×发药 1:N 关系的实时核对 */
    private Map<String, Object> dispenseSplitAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        List<Map<String, Object>> split = pharmacy.queryForList(
                "SELECT order_id, COUNT(*) AS cnt FROM dispense_record WHERE status IN ('0','1') "
                        + "GROUP BY order_id HAVING COUNT(*) > 1");
        Long totalOrders = pharmacy.queryForObject(
                "SELECT COUNT(DISTINCT order_id) FROM dispense_record WHERE status IN ('0','1')", Long.class);
        Long totalDispensed = pharmacy.queryForObject(
                "SELECT COUNT(*) FROM dispense_record WHERE status = '1'", Long.class);
        result.put("intent", "分次发药核对");
        result.put("answer", "可以。"
                + ruleSentence("CS_RULE_DISPENSE_SPLIT")
                + String.format("实时核对药房库：当前 %d 条医嘱共产生 %d 笔发药，其中 %d 条医嘱存在多次发药%s。",
                totalOrders, totalDispensed, split.size(),
                split.isEmpty() ? "——目前全部是一单一发，未发生分次" : ""));
        result.put("evidence", List.of(
                Map.of("label", "有发药的医嘱", "value", totalOrders + " 条"),
                Map.of("label", "发药总笔数", "value", totalDispensed + " 笔"),
                Map.of("label", "多次发药医嘱", "value", split.size() + " 条"),
                Map.of("label", "数据来源", "value", "DS_PHARMACY.dispense_record 按医嘱分组实时统计")));
        result.put("links", List.of(
                Map.of("label", "去本体页看「医嘱—发药」关系", "route", "/ontology?concept=DISPENSE"),
                Map.of("label", "去流程演示页看发药环节", "route", "/flow")));
        return result;
    }

    /** 意图：发药后能否（部分）退药 —— 退药×退费联动的实时核对 */
    private Map<String, Object> dispenseReturnAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        List<Map<String, Object>> returned = pharmacy.queryForList(
                "SELECT dispense_id, order_id, item_name FROM dispense_record WHERE status = '2'");
        Long refundedFee = 0L;
        if (!returned.isEmpty()) {
            String in = returned.stream()
                    .map(r -> "'" + String.valueOf(r.get("order_id")).replace("'", "") + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            refundedFee = his.queryForObject(
                    "SELECT COUNT(*) FROM fee_detail WHERE order_id IN (" + in + ") AND fee_status = '2'", Long.class);
        }
        result.put("intent", "退药核对");
        result.put("answer", ruleSentence("CS_RULE_REFUND_PAIR")
                + String.format("实时核对：当前已退药 %d 笔，对应费用已退费 %d 笔%s。",
                returned.size(), refundedFee,
                returned.size() == refundedFee ? "，退药退费全部联动一致" : "，存在退药未退费的裂缝，需核查"));
        result.put("evidence", List.of(
                Map.of("label", "已退药笔数", "value", returned.size() + " 笔"),
                Map.of("label", "费用已退费", "value", refundedFee + " 笔"),
                Map.of("label", "数据来源", "value", "DS_PHARMACY.dispense_record × DS_HIS.fee_detail 按医嘱号核对")));
        result.put("links", List.of(
                Map.of("label", "去本体页看发药记录概念", "route", "/ontology?concept=DISPENSE"),
                Map.of("label", "去流程演示页看闭环", "route", "/flow")));
        return result;
    }

    /** 意图：耗材库存 / 申领 */
    private Map<String, Object> materialAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        JdbcTemplate material = datasourceService.jdbc("DS_MATERIAL");
        List<Map<String, Object>> stocks = material.queryForList(
                "SELECT material_name, quantity, unit FROM material_stock ORDER BY quantity");
        Long pending = material.queryForObject(
                "SELECT COUNT(*) FROM material_apply WHERE status = '待发'", Long.class);
        StringBuilder sb = new StringBuilder("耗材库存当前（库存=Σ入库-Σ出库，账实相符）：");
        for (Map<String, Object> s : stocks) {
            sb.append(s.get("material_name")).append(" ").append(s.get("quantity"))
                    .append(s.get("unit")).append("；");
        }
        sb.append("另有 ").append(pending).append(" 张科室申领单待发放。");
        result.put("intent", "耗材库存查询");
        result.put("answer", sb.toString());
        result.put("evidence", stocks.stream().map(s -> Map.of(
                "label", String.valueOf(s.get("material_name")),
                "value", s.get("quantity") + " " + s.get("unit"))).toList());
        result.put("links", List.of(Map.of("label", "去实例浏览看耗材库存/申领/发放", "route", "/ontology")));
        return result;
    }

    /** 意图：找人 / 人员归属（问的人不在主数据中时返回 null，继续走后续路由） */
    private Map<String, Object> staffAnswer(String q) {
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        List<Map<String, Object>> staffs = his.queryForList(
                "SELECT staff_name, role, title, dept_code FROM staff");
        Map<String, Object> hit = staffs.stream()
                .filter(s -> q.contains(String.valueOf(s.get("staff_name"))))
                .findFirst().orElse(null);
        if (hit == null) {
            return null;
        }
        Map<String, Object> result = baseResult(q);
        Map<String, Object> detail = flowService.staffDetail(String.valueOf(hit.get("staff_name")));
        @SuppressWarnings("unchecked")
        Map<String, Object> dept = (Map<String, Object>) detail.get("dept");
        @SuppressWarnings("unchecked")
        Map<String, Object> footprint = (Map<String, Object>) detail.get("footprint");
        result.put("intent", "人员归属查询");
        result.put("answer", hit.get("staff_name") + "：" + hit.get("role") + " · " + hit.get("title")
                + "，隶属" + dept.get("dept_name") + "（" + dept.get("category") + "）。业务足迹：开立医嘱 "
                + footprint.get("开立医嘱") + " 条、执行确认 " + footprint.get("执行确认") + " 次、处方审核 "
                + footprint.get("处方审核") + " 次、调剂发药 " + footprint.get("调剂发药") + " 次。");
        result.put("evidence", List.of(
                Map.of("label", "科室", "value", dept.get("dept_name") + "（" + dept.get("category") + "）"),
                Map.of("label", "职称", "value", String.valueOf(hit.get("title"))),
                Map.of("label", "数据来源", "value", "人员主数据 DS_HIS.staff（流程页人名可点击下钻）")));
        result.put("links", List.of(Map.of("label", "去流程演示页看人名下钻", "route", "/flow")));
        return result;
    }

    /** 意图：口径/定义/怎么算（无命中时返回 null，继续走后续路由）；dispatch 路径不出卡，形态与历史版本一致 */
    private Map<String, Object> glossaryAnswer(String q) {
        return glossaryAnswer(q, true, false);
    }

    /**
     * @param recordMiss 零命中时是否记词表外缺口（问数前置试探为 false：还会继续进语义层，不该记成本体缺口）
     * @param allowCard  是否允许名称直命中的指标出口径卡（仅问数场景前置试探为 true）
     */
    private Map<String, Object> glossaryAnswer(String q, boolean recordMiss, boolean allowCard) {
        String stripped = q.replaceAll("(怎么算|什么是|什么叫|的口径|口径|定义)", "").trim();
        Map<String, Object> search = searchService.search(stripped, recordMiss);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) search.get("hits");
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        // 口径定义挂在指标库上：名称直命中的指标出口径卡，多个命中取名称最长者（匹配信息量最大）；
        // 仅定义顺带命中的指标不做卡（避免答非所问），退回术语/概念文本回答
        Map<String, Object> top = hits.get(0);
        boolean metricCard = false;
        for (Map<String, Object> h : hits) {
            if (!"指标".equals(h.get("type")) || !nameHits(stripped, String.valueOf(h.get("name")))) {
                continue;
            }
            if (!metricCard || String.valueOf(h.get("name")).length() > String.valueOf(top.get("name")).length()) {
                top = h;
                metricCard = true;
            }
        }
        if (metricCard && !allowCard) {
            top = hits.get(0); // 非问数场景保持历史文本形态，不附带卡片字段
            metricCard = false;
        }
        Map<String, Object> result = baseResult(q);
        result.put("intent", "口径查询");
        String llmAnswer = Boolean.TRUE.equals(search.get("llmUsed")) ? String.valueOf(search.get("answer")) : "";
        result.put("answer", "「" + top.get("title") + "」" + top.get("content")
                + (llmAnswer.isEmpty() ? "" : "\n\n" + llmAnswer));
        result.put("evidence", List.of(Map.of("label", "命中" + top.get("type"), "value", String.valueOf(top.get("title")))));
        if (metricCard) {
            // 口径卡：结构化字段全部取自 bm_metric 真实数据，卡上探针 SQL 与指标巡检执行同源
            attachMetricCard(result, String.valueOf(top.get("metricCode")));
            if ("METRIC".equals(result.get("card"))) {
                result.put("answerLlm", llmAnswer); // 前端渲染口径卡时用它替代与卡片重复的结构化文本
            }
        } else {
            result.put("links", List.of(Map.of("label", "去统一口径页查看全部术语", "route", "/glossary")));
        }
        return result;
    }

    /** 意图：制度/流程/政策依据类问题——知识库文档检索作答，零命中诚实答并记知识缺口（非词表缺口） */
    private Map<String, Object> docAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        List<Map<String, Object>> chunks = knowledgeSearchService.search(q, 5);
        if (chunks.isEmpty()) {
            missService.recordMiss(q, "KNOWLEDGE", "CS_ASK");
            result.put("intent", "制度依据查询");
            result.put("answer", "知识库暂无相关制度依据。这个问题已记录到知识库完善池，"
                    + "上传相关制度文档并发布后即可回答。");
            result.put("links", List.of(Map.of(
                    "label", "去知识库上传相关制度文档",
                    "route", "/knowledge")));
            return result;
        }
        StringBuilder ctx = new StringBuilder(
                "请严格基于以下制度片段回答用户问题，引用《文档标题》与章节出处；片段没有的内容不要编造。\n");
        ctx.append("用户问题：").append(q).append('\n');
        List<String> anchors = new ArrayList<>();
        for (Map<String, Object> c : chunks) {
            ctx.append("-《").append(c.get("docTitle")).append("》").append(c.get("heading"))
                    .append("：").append(c.get("content")).append('\n');
            anchors.add(c.get("documentId") + ":" + c.get("seq"));
        }
        String answer;
        String source = "TEMPLATE";
        if (deepSeekClient.enabled()) {
            Optional<String> reply = deepSeekClient.chat("CS_DOC_ANSWER",
                    "你是医院制度问答助手，严格基于给定制度片段回答，不编造片段之外的条文。", ctx.toString());
            if (reply.isPresent()) {
                answer = reply.get();
                source = "LLM";
            } else {
                answer = topChunkAnswer(chunks);
            }
        } else {
            answer = topChunkAnswer(chunks);
        }
        result.put("intent", "制度依据查询");
        result.put("answer", answer);
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Map<String, Object> c : chunks) {
            evidence.add(Map.of(
                    "label", "《" + c.get("docTitle") + "》" + c.get("heading"),
                    "value", String.valueOf(c.get("content"))));
        }
        result.put("evidence", evidence);
        result.put("links", List.of(Map.of(
                "label", "去知识库查看制度文档",
                "route", "/knowledge")));
        persistDocTrace(q, answer, source, anchors);
        return result;
    }

    /** 无 Key 降级：给首条片段原文+出处（宁给原文不给编造） */
    private String topChunkAnswer(List<Map<String, Object>> chunks) {
        Map<String, Object> top = chunks.get(0);
        return "根据《" + top.get("docTitle") + "》" + top.get("heading") + "：" + top.get("content");
    }

    /** 文档问答证据落库（scene=CS，matched_docs=docId:seq 逗号锚点；静默降级不断链） */
    private void persistDocTrace(String q, String answer, String source, List<String> anchors) {
        try {
            QaTrace trace = new QaTrace();
            trace.setTraceId("QA-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + "-" + UUID.randomUUID().toString().substring(0, 6));
            trace.setScene("CS");
            trace.setQuestion(q);
            trace.setMatchedDocs(String.join(",", anchors));
            trace.setAnswer(answer);
            trace.setAnswerSource(source);
            qaTraceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("文档问答证据落库失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 名称直命中：指标名是问题的子串（或反向），与 SearchService#nameMatch 同口径 */
    private boolean nameHits(String query, String name) {
        return name != null && !name.isEmpty() && !query.isEmpty()
                && (name.contains(query) || query.contains(name));
    }

    /** 附带口径卡：按 metricCode 回查指标库；查不到时 card 字段不置，前端按普通文本回答渲染（不做字符串嗅探） */
    private void attachMetricCard(Map<String, Object> result, String metricCode) {
        if (metricCode == null || metricCode.isEmpty()) {
            result.put("links", List.of(Map.of("label", "去统一口径页查看全部术语", "route", "/glossary")));
            return;
        }
        com.bemodel.ontology.entity.Metric m = metricMapper.selectOne(
                new LambdaQueryWrapper<com.bemodel.ontology.entity.Metric>()
                        .eq(com.bemodel.ontology.entity.Metric::getMetricCode, metricCode));
        if (m == null) {
            result.put("links", List.of(Map.of("label", "去统一口径页查看全部术语", "route", "/glossary")));
            return;
        }
        // 双演示库收口：场景外数据源的指标不出卡（与问数同一个语义世界），退回统一口径页通用链接
        if (!sceneProps.inScene(m.getDsCode())) {
            result.put("links", List.of(Map.of("label", "去统一口径页查看全部术语", "route", "/glossary")));
            return;
        }
        // 与指标库页 hasProbe 同口径：探针 SQL 与数据源齐备才可执行检测，卡上文案不得夸大能力
        boolean hasProbe = m.getProbeSql() != null && !m.getProbeSql().isBlank()
                && m.getDsCode() != null && !m.getDsCode().isBlank();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("metricCode", m.getMetricCode());
        card.put("name", m.getName());
        card.put("definition", m.getDefinition());
        card.put("formula", m.getFormula());
        card.put("probeSql", m.getProbeSql());
        card.put("dsCode", m.getDsCode());
        card.put("hasProbe", hasProbe);
        card.put("owner", m.getOwner());
        card.put("warnThreshold", m.getWarnThreshold());
        card.put("lastVal", m.getLastVal());
        if (m.getLastEvalAt() != null) {
            card.put("lastEvalAt", m.getLastEvalAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        }
        result.put("card", "METRIC");
        result.put("metric", card);
        result.put("links", List.of(Map.of(
                "label", hasProbe ? "去统一口径页看该指标（可执行检测）" : "去统一口径页查看该指标",
                "route", "/glossary?metric=" + urlEncode(m.getMetricCode()))));
    }

    /** 兜底：能力菜单 */
    private Map<String, Object> capabilityMenu(String q) {
        Map<String, Object> result = baseResult(q);
        result.put("intent", "能力引导");
        result.put("answer", "我目前能查证这几类问题，也可以直接问我业务数据（如「内科有多少住院患者」）：");
        result.put("evidence", List.of(
                Map.of("label", "开放查询", "value", "「头孢克肟还有多少库存？」「昨天的缴费总额是多少？」→ 本体语义层直接查业务库"),
                Map.of("label", "费用投诉", "value", "「检验取消了怎么还收费？」→ 全院取消未退费排查 + 一键退费工单"),
                Map.of("label", "缴费发药", "value", "「没缴费可以发药吗？」→ 发药×缴费跨库实时核对"),
                Map.of("label", "发药方式", "value", "「一个医嘱可以分开发药吗？」→ 医嘱×发药 1:N 实时统计"),
                Map.of("label", "退药核对", "value", "「发药后可以部分退药吗？」→ 退药×退费联动核对"),
                Map.of("label", "耗材库存", "value", "「一次性输液器还有多少库存？」→ 库存=Σ入-Σ出实时账"),
                Map.of("label", "人员归属", "value", "「王芳是谁？」→ 科室/职称/业务足迹"),
                Map.of("label", "指标口径", "value", "「出院人数怎么算？」→ 标准定义与负责人"),
                Map.of("label", "知识文档", "value", "「探视制度有什么规定？」→ 制度文档检索作答，引用出处")));
        result.put("links", List.of(Map.of(
                "label", "找数据？去智能问数继续提问",
                "route", "/ask?q=" + urlEncode(q))));
        return result;
    }

    /** 问数场景兜底：诚实说明语义层边界，附真实已发布概念数与向 AI 客服的场景转介（不写本体缺口） */
    private Map<String, Object> analyticsMenu(String q) {
        long published = conceptMapper.selectCount(new LambdaQueryWrapper<com.bemodel.ontology.entity.Concept>()
                .eq(com.bemodel.ontology.entity.Concept::getStatus, "PUBLISHED"));
        Map<String, Object> result = baseResult(q);
        result.put("intent", "场景引导");
        result.put("answer", "本页面向数据问题：把提问解析为「概念→关系→物理表」的查询计划，SQL 经白名单校验后在业务库实时执行。"
                + "这个问题暂时没有命中语义层能力——可以换个数据问法，或转 AI 客服处理业务咨询。"
                + "当前发布版本体覆盖 " + published + " 个概念；语义层答不了的问题会自动回流概念缺口页，按提问热度生长。");
        result.put("evidence", List.of(
                Map.of("label", "已发布概念", "value", published + " 个（本体管理-发布版）"),
                Map.of("label", "典型问法", "value", "「最近10条缴费记录」「出院人数怎么算」")));
        result.put("links", List.of(Map.of(
                "label", "业务咨询/投诉处置？去 AI 客服",
                "route", "/cs?q=" + urlEncode(q))));
        return result;
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean matches(String q, String... keywords) {
        for (String k : keywords) {
            if (q.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode parseJson(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
