package com.bemodel.cs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.cs.mapper.ReconDiffMapper;
import com.bemodel.cs.ReconDiff;
import com.bemodel.datasource.service.DatasourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 跨库核对语义化（D2）：缴费×发药双向核对，结论从二值升级为语义档 + 归因 + 差异登记。
 *
 * 语义档（severity）：
 * - VIOLATION 先药后费：已发药且患者无任何有效缴费——真违规；
 * - WATCH     已退药待退费：退药已发生而费用未退——中间态（业务未完成，非违规）；
 * - WATCH     真滞留：已计费已缴费且医嘱已执行却无发药记录；
 * - NORMAL    在途待发：已缴费但医嘱未执行（仍在调剂流程内）——正常差异，显式登记防止误判；
 * - WATCH     付费后取消（非凌晨）：需人工核对退费；
 * - NORMAL    凌晨批量取消：自然取消归因（未缴费超时批量取消窗口），不进异常清单。
 *
 * 差异逐条落 bm_recon_diff（run_id 批次），文案层只做分档计数与归因表达，数字全部来自真实查询。
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final DatasourceService datasourceService;
    private final ReconDiffMapper reconDiffMapper;
    private final com.bemodel.knowledge.KnowledgeService knowledgeService;

    /** 凌晨批量取消窗口（未缴费超时自动取消的定时任务时段） */
    private static final int NIGHTLY_CANCEL_END_HOUR = 6;

    public record Tier(String stateCode, String severity, String attribution, String suggestedAction) {
    }

    /** 各语义档的归因说明与建议动作（专家预定义，LLM 不参与判定） */
    private static final Map<String, Tier> TIERS = Map.of(
            "VIOLATION_PREPAY_BYPASS", new Tier("VIOLATION_PREPAY_BYPASS", "VIOLATION",
                    "已发药但患者无任何有效缴费记录，先药后费违反流程公理", "逐笔追缴并核查发药窗口放行原因"),
            "RETURNED_PENDING_REFUND", new Tier("RETURNED_PENDING_REFUND", "WATCH",
                    "已退药但对应费用未退费——业务完成≠财务到账的中间态", "触发退费流程，核对退费时效"),
            "STUCK_BACKLOG", new Tier("STUCK_BACKLOG", "WATCH",
                    "已计费已缴费且医嘱已执行，但药房无发药记录——真实滞留", "催办药房发药或发起退费"),
            "IN_FLIGHT", new Tier("IN_FLIGHT", "NORMAL",
                    "已缴费但医嘱未执行——在途待发，属正常差异", "无需处理，显式登记防止误判为滞留"),
            "CANCELLED_AFTER_PAY", new Tier("CANCELLED_AFTER_PAY", "WATCH",
                    "缴费后医嘱被取消（非凌晨批量窗口）", "人工核对退费是否完成"),
            "NIGHTLY_CANCEL", new Tier("NIGHTLY_CANCEL", "NORMAL",
                    "凌晨批量取消窗口内的自然取消（未缴费超时自动取消）", "归因消除，不进异常清单"));

    /** 归因句从知识库条目读（RECON_ATTR_<stateCode>，「改一条走审批」）；条目不可用回退内嵌原文，判定不受影响 */
    private Tier resolveTier(String stateCode) {
        Tier def = TIERS.get(stateCode);
        String attribution = knowledgeService.entryContent("RECON_ATTR_" + stateCode);
        return attribution == null ? def
                : new Tier(def.stateCode(), def.severity(), attribution, def.suggestedAction());
    }

    public Map<String, Object> reconcileDispensePay() {
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        JdbcTemplate charge = datasourceService.jdbc("DS_CHARGE");
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");

        String runId = "RECON-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 6);

        // 保留期：只留近 7 天批次的差异登记（每次核对顺带清理，防 bm_recon_diff 无界增长）
        reconDiffMapper.delete(new LambdaQueryWrapper<ReconDiff>()
                .lt(ReconDiff::getCreatedAt, LocalDateTime.now().minusDays(7)));

        Set<String> paid = new HashSet<>(charge.queryForList(
                "SELECT DISTINCT inhos_no FROM pay_record WHERE pay_type IN ('1','2','4')", String.class));

        // 方向A：发药 × 缴费
        List<Map<String, Object>> dispensed = pharmacy.queryForList(
                "SELECT dispense_id, patient_no, item_name FROM dispense_record WHERE status = '1'");
        List<Map<String, Object>> prepaidBypass = dispensed.stream()
                .filter(d -> !paid.contains(String.valueOf(d.get("patient_no")))).toList();

        List<Map<String, Object>> returned = pharmacy.queryForList(
                "SELECT dispense_id, order_id, patient_no, item_name FROM dispense_record WHERE status = '2'");
        Set<String> refundedOrderIds = new HashSet<>(his.queryForList(
                "SELECT order_id FROM fee_detail WHERE fee_status = '2'", String.class));
        List<Map<String, Object>> returnedPendingRefund = returned.stream()
                .filter(r -> r.get("order_id") != null
                        && !refundedOrderIds.contains(String.valueOf(r.get("order_id")))).toList();

        // 方向B：缴费 × 发药（含归因消除：在途 / 凌晨批量取消 / 付费后取消）
        List<Map<String, Object>> chargedDrugOrders = his.queryForList(
                "SELECT DISTINCT o.order_id, o.inhos_no, o.item_name, o.order_status, o.cancel_time "
                        + "FROM medical_order o JOIN fee_detail f ON f.order_id = o.order_id "
                        + "WHERE o.order_type = '药品' AND o.order_status IN ('0','1','2')");
        Set<String> delivered = new HashSet<>(pharmacy.queryForList(
                "SELECT DISTINCT order_id FROM dispense_record WHERE status IN ('0','1')", String.class));

        // 归因消除（B 向）：退费已完成 = 业务闭环（与方向 A 同一退费口径），不再误报「需核对退费」；
        // A 向已计「已退药待退费」的医嘱 B 向不再重复计入——一笔业务只进一档，异常合计不双计
        Set<String> pendingRefundOrderIds = returnedPendingRefund.stream()
                .map(r -> String.valueOf(r.get("order_id"))).collect(Collectors.toSet());

        List<Map<String, Object>> stuck = new ArrayList<>();
        List<Map<String, Object>> inFlight = new ArrayList<>();
        List<Map<String, Object>> nightlyCancel = new ArrayList<>();
        List<Map<String, Object>> cancelledAfterPay = new ArrayList<>();
        for (Map<String, Object> o : chargedDrugOrders) {
            String orderId = String.valueOf(o.get("order_id"));
            if (!paid.contains(String.valueOf(o.get("inhos_no")))
                    || delivered.contains(orderId)) {
                continue;
            }
            if (refundedOrderIds.contains(orderId) || pendingRefundOrderIds.contains(orderId)) {
                continue;
            }
            switch (String.valueOf(o.get("order_status"))) {
                case "1" -> stuck.add(o);
                case "0" -> inFlight.add(o);
                case "2" -> {
                    if (isNightlyCancel(o.get("cancel_time"))) {
                        nightlyCancel.add(o);
                    } else {
                        cancelledAfterPay.add(o);
                    }
                }
                default -> {
                }
            }
        }

        // 差异登记：逐条落库（失败三表模式——差异是可查数据对象，不是一次性文案）
        List<Map<String, Object>> registryRows = new ArrayList<>();
        // 归因句改读知识条目：先解析成局部变量，persistDiffs 与 tierView 共用同一解析结果
        Tier tPrepayBypass = resolveTier("VIOLATION_PREPAY_BYPASS");
        Tier tReturnedPending = resolveTier("RETURNED_PENDING_REFUND");
        Tier tStuck = resolveTier("STUCK_BACKLOG");
        Tier tInFlight = resolveTier("IN_FLIGHT");
        Tier tCancelledAfterPay = resolveTier("CANCELLED_AFTER_PAY");
        Tier tNightlyCancel = resolveTier("NIGHTLY_CANCEL");
        registryRows.addAll(persistDiffs(runId, "A", tPrepayBypass, prepaidBypass,
                "patient_no", "dispense_id"));
        registryRows.addAll(persistDiffs(runId, "A", tReturnedPending, returnedPendingRefund,
                "patient_no", "order_id"));
        registryRows.addAll(persistDiffs(runId, "B", tStuck, stuck, "inhos_no", "order_id"));
        registryRows.addAll(persistDiffs(runId, "B", tInFlight, inFlight, "inhos_no", "order_id"));
        registryRows.addAll(persistDiffs(runId, "B", tCancelledAfterPay, cancelledAfterPay,
                "inhos_no", "order_id"));
        registryRows.addAll(persistDiffs(runId, "B", tNightlyCancel, nightlyCancel, "inhos_no", "order_id"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("tiers", List.of(
                tierView("A", tPrepayBypass, prepaidBypass.size(), dispensed.size()),
                tierView("A", tReturnedPending, returnedPendingRefund.size(), returned.size()),
                tierView("B", tStuck, stuck.size(), chargedDrugOrders.size()),
                tierView("B", tInFlight, inFlight.size(), chargedDrugOrders.size()),
                tierView("B", tCancelledAfterPay, cancelledAfterPay.size(), chargedDrugOrders.size()),
                tierView("B", tNightlyCancel, nightlyCancel.size(), chargedDrugOrders.size())));
        result.put("registered", registryRows.size());
        result.put("abnormalCount", prepaidBypass.size() + returnedPendingRefund.size()
                + stuck.size() + cancelledAfterPay.size());
        return result;
    }

    /** 按批次查登记差异（GET /api/recon/diffs） */
    public List<ReconDiff> diffsOf(String runId) {
        return reconDiffMapper.selectList(new LambdaQueryWrapper<ReconDiff>()
                .eq(ReconDiff::getRunId, runId)
                .orderByAsc(ReconDiff::getId));
    }

    private boolean isNightlyCancel(Object cancelTime) {
        if (cancelTime == null) {
            return false;
        }
        try {
            String s = String.valueOf(cancelTime).replace(' ', 'T');
            int dot = s.indexOf('.');
            if (dot >= 0) {
                s = s.substring(0, dot); // 只截小数秒（.03/.05 形态也安全），不做全局替换
            }
            LocalDateTime t = LocalDateTime.parse(s);
            return t.getHour() < NIGHTLY_CANCEL_END_HOUR;
        } catch (Exception e) {
            return false;
        }
    }

    private List<Map<String, Object>> persistDiffs(String runId, String direction, Tier tier,
                                                   List<Map<String, Object>> rows, String patientKey, String orderKey) {
        List<Map<String, Object>> registered = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            ReconDiff diff = new ReconDiff();
            diff.setRunId(runId);
            diff.setDirection(direction);
            diff.setStateCode(tier.stateCode());
            diff.setSeverity(tier.severity());
            diff.setAttribution(tier.attribution());
            diff.setSuggestedAction(tier.suggestedAction());
            Object patient = r.get(patientKey);
            diff.setPatientNo(patient == null ? null : String.valueOf(patient)); // 业务键保留，可下钻
            Object order = r.get(orderKey);
            diff.setOrderId(order == null ? null : String.valueOf(order));
            Object item = r.get("item_name");
            diff.setItemName(item == null ? null : String.valueOf(item));
            reconDiffMapper.insert(diff);
            registered.add(Map.of("state", tier.stateCode(), "patientNo",
                    patient == null ? "" : String.valueOf(patient), "orderId",
                    diff.getOrderId() == null ? "" : diff.getOrderId()));
        }
        return registered;
    }

    private Map<String, Object> tierView(String direction, Tier tier, int count, int total) {
        return Map.of(
                "direction", direction,
                "stateCode", tier.stateCode(),
                "severity", tier.severity(),
                "attribution", tier.attribution(),
                "suggestedAction", tier.suggestedAction(),
                "count", count,
                "total", total);
    }
}
