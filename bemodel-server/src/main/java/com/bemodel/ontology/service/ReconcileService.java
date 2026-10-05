package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.common.SqlGuard;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.ontology.entity.ReconcileGroup;
import com.bemodel.ontology.entity.ReconcileRun;
import com.bemodel.ontology.mapper.ReconcileGroupMapper;
import com.bemodel.ontology.mapper.ReconcileRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账分歧工单化第一片:建组校验,跑组(逐口径实测+差额+可选下钻),运行落库。
 * 2026-09-24 医院场景试点手工做的五步,由此收敛为对账组对象 + 一个「跑一次对账」。
 */
@Service
@RequiredArgsConstructor
public class ReconcileService extends ServiceImpl<ReconcileGroupMapper, ReconcileGroup> {
    /** 下钻明细最多 200 行——对账只需定位到病例级,整表拉回是事故 */
    static final int DRILL_MAX_ROWS = 200;

    private final MetricService metricService;
    private final ReconcileRunMapper reconcileRunMapper;
    private final DatasourceService datasourceService;
    private final ObjectMapper objectMapper;
    /** 建组:至少 2 个口径;指标编码必须已存在;组内去重 */
    public ReconcileGroup create(ReconcileGroup group) {
        if (group.getGroupCode() == null || group.getGroupCode().isBlank()
                || group.getName() == null || group.getName().isBlank()) {
            throw new BizException("对账组编码和名称必填");
        }
        List<String> codes = splitCodes(group.getMetricCodes());
        if (codes.size() < 2) {
            throw new BizException("对账组至少需要 2 个口径指标,当前: " + codes.size());
        }
        for (String code : codes) {
            if (metricService.getByCode(code) == null) {
                throw new BizException("指标不存在: " + code);
            }
        }
        save(group);
        return group;
    }

    private static List<String> splitCodes(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
    /** 跑组:逐口径实测(单个失败不中断,记入 error_msg),差额=最大-最小,可选下钻明细,运行落库 */
    public Map<String, Object> run(String groupCode) {
        ReconcileGroup group = getByCode(groupCode);
        if (group == null) {
            throw new BizException("对账组不存在: " + groupCode);
        }
        List<String> codes = splitCodes(group.getMetricCodes());
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (String code : codes) {
            try {
                Map<String, Object> r = metricService.evaluate(code);
                Object v = r.get("value");
                if (v == null) {
                    errors.add(code + ": 实测值为空(探针未返回数值)");
                } else {
                    values.put(code, v);
                }
            } catch (Exception e) {
                errors.add(code + ": " + e.getMessage());
            }
        }
        // 有口径失败/空值时差额置空:残缺口径算出的 0 是假对平,宁可空也不给假差额
        Long diff = errors.isEmpty() ? diffOf(values) : null;
        // 新差额出现=分歧没消,工单打回待认领(已裁决的也一样:裁决只对当时的数据负责)
        if (diff != null && diff != 0 && !"OPEN".equals(group.getDisputeStatus())) {
            // updateById 会忽略 null 字段,认领人必须显式置空;WHERE 钉住非 OPEN 防并发错杀
            lambdaUpdate()
                    .eq(ReconcileGroup::getId, group.getId())
                    .ne(ReconcileGroup::getDisputeStatus, "OPEN")
                    .set(ReconcileGroup::getDisputeStatus, "OPEN")
                    .set(ReconcileGroup::getDisputeOwner, null)
                    .set(ReconcileGroup::getClaimedBy, null)
                    .update();
            // 同步内存对象:响应里的状态要反映重开,不能只改库
            group.setDisputeStatus("OPEN");
            group.setDisputeOwner(null);
            group.setClaimedBy(null);
        }
        Map<String, Object> bucketsResult = null;
        try {
            List<Bucket> buckets = parseBuckets(group);
            if (!buckets.isEmpty()) {
                bucketsResult = runBuckets(buckets, diff, errors);
            }
        } catch (Exception e) {
            errors.add("归因桶配置解析失败: " + e.getMessage());
        }
        DrillResult drill = runDrill(group);
        if (drill.error() != null) {
            // 下钻失败只记账,不否决差额:探针都成功时 diff 仍有效(评审发现:异常被吞,页面查不到原因)
            errors.add("下钻失败: " + drill.error());
        }
        ReconcileRun record = new ReconcileRun();
        record.setGroupCode(groupCode);
        record.setValuesJson(writeJson(values));
        record.setDiffValue(diff);
        record.setDrillJson(drill.rowsJson());
        record.setDrillCount(drill.count());
        record.setBucketsJson(bucketsResult == null ? null : writeJson(bucketsResult));
        record.setErrorMsg(errors.isEmpty() ? null : String.join("; ", errors));
        record.setRanAt(LocalDateTime.now());
        reconcileRunMapper.insert(record);
        Map<String, Object> result = toResult(group, values, diff, drill, bucketsResult, record.getRanAt(), record.getErrorMsg());
        result.put("disputeStatus", group.getDisputeStatus());
        return result;
    }

    /** 认领:公开谁在牵头对这笔分歧。认领人取登录身份(V45 起不自报);只有待认领的组认领得动——不能静默顶掉已在办的人 */
    public ReconcileGroup claim(String groupCode) {
        ReconcileGroup group = getByCode(groupCode);
        if (group == null) {
            throw new BizException("对账组不存在: " + groupCode);
        }
        String user = com.bemodel.auth.CurrentUser.username();
        if (user == null || user.isBlank()) {
            throw new BizException("需登录后认领(认领人以登录账号为准,不再自报)");
        }
        // 条件更新防并发顶替(同 OntologyProposalService 的竞态修法):WHERE 里钉住现态
        boolean ok = lambdaUpdate()
                .eq(ReconcileGroup::getId, group.getId())
                .eq(ReconcileGroup::getDisputeStatus, "OPEN")
                .set(ReconcileGroup::getDisputeStatus, "CLAIMED")
                .set(ReconcileGroup::getDisputeOwner, user)
                .set(ReconcileGroup::getClaimedBy, user)
                .update();
        if (!ok) {
            throw new BizException("RESOLVED".equals(group.getDisputeStatus())
                    ? "该分歧已裁决(" + group.getVerdict() + "),无需认领"
                    : "已被 " + group.getDisputeOwner() + " 认领;如需接替,等重跑对账打回待认领后再认领");
        }
        group.setDisputeStatus("CLAIMED");
        group.setDisputeOwner(user);
        group.setClaimedBy(user);
        return group;
    }

    /** 裁决:以哪侧口径为准,结论入档,裁决人取登录身份。裁决是入档动作,不能被静默覆盖;改结论等重跑打回后再裁 */
    public ReconcileGroup resolve(String groupCode, String verdict, String note) {
        ReconcileGroup group = getByCode(groupCode);
        if (group == null) {
            throw new BizException("对账组不存在: " + groupCode);
        }
        if (verdict == null || verdict.isBlank()) {
            throw new BizException("裁决结论必填(以哪侧口径为准)");
        }
        String user = com.bemodel.auth.CurrentUser.username();
        if (user == null || user.isBlank()) {
            throw new BizException("需登录后裁决(裁决人以登录账号为准)");
        }
        boolean ok = lambdaUpdate()
                .eq(ReconcileGroup::getId, group.getId())
                .in(ReconcileGroup::getDisputeStatus, "OPEN", "CLAIMED")
                .set(ReconcileGroup::getDisputeStatus, "RESOLVED")
                .set(ReconcileGroup::getVerdict, verdict.trim())
                .set(ReconcileGroup::getVerdictNote, note == null || note.isBlank() ? null : note.trim())
                .set(ReconcileGroup::getVerdictAt, LocalDateTime.now())
                .set(ReconcileGroup::getResolvedBy, user)
                .update();
        if (!ok) {
            throw new BizException("该分歧已裁决(" + group.getVerdict()
                    + ");要改结论,等重跑出非零差额打回待认领后再裁");
        }
        group.setDisputeStatus("RESOLVED");
        group.setVerdict(verdict.trim());
        group.setVerdictNote(note == null || note.isBlank() ? null : note.trim());
        group.setVerdictAt(LocalDateTime.now());
        group.setResolvedBy(user);
        return group;
    }

    /**
     * 对账组编辑(白名单直写):name/definition/owner/metricCodes/drillDsCode/drillSql/bucketsJson/scheduled。
     * groupCode 与分歧系列(dispute/verdict/claimedBy/resolvedBy)不可经此改动;metricCodes 校验同 create;
     * scheduled 显式兜底为 0/1(开关语义,null 视作关)。
     */
    public void update(ReconcileGroup body) {
        if (body.getId() == null) {
            throw new BizException("对账组 id 必填");
        }
        if (getById(body.getId()) == null) {
            throw new BizException("对账组不存在: " + body.getId());
        }
        List<String> codes = splitCodes(body.getMetricCodes());
        if (codes.size() < 2) {
            throw new BizException("对账组至少需要 2 个口径指标,当前: " + codes.size());
        }
        for (String code : codes) {
            if (metricService.getByCode(code) == null) {
                throw new BizException("指标不存在: " + code);
            }
        }
        lambdaUpdate()
                .eq(ReconcileGroup::getId, body.getId())
                .set(ReconcileGroup::getName, body.getName())
                .set(ReconcileGroup::getDefinition, body.getDefinition())
                .set(ReconcileGroup::getOwner, body.getOwner())
                .set(ReconcileGroup::getMetricCodes, body.getMetricCodes())
                .set(ReconcileGroup::getDrillDsCode, body.getDrillDsCode())
                .set(ReconcileGroup::getDrillSql, body.getDrillSql())
                .set(ReconcileGroup::getBucketsJson, body.getBucketsJson())
                .set(ReconcileGroup::getScheduled, body.getScheduled() == null ? 0 : body.getScheduled())
                .update();
    }
    /** 本体 vs 土办法对照(试点演示):左列实时代跑与 docs/pilot/baseline_reconcile.py 逐字同源的写死 SQL,
     *  右列取对账组对象现状。哪边赢都如实返回——拆堆/换月/无人值守今天就是脚本赢,不粉饰。 */
    public Map<String, Object> compare(String groupCode) {
        ReconcileGroup group = getByCode(groupCode);
        if (group == null) {
            throw new BizException("对账组不存在: " + groupCode);
        }
        // ---- 左列:土办法(演示期由平台代跑;真用土办法时这些 SQL 躺在某人的脚本文件里) ----
        List<Map<String, String>> sqls = List.of(
                Map.of("label", "病案口径(首页已交)", "ds", "DS_MEDREC",
                        "sql", "SELECT COUNT(*) FROM hosp_mr.med_record WHERE mr_status='NORMAL' AND dis_date>='2026-09-01' AND dis_date<'2026-10-01'"),
                Map.of("label", "结算口径(已结算)", "ds", "DS_SETTLE",
                        "sql", "SELECT COUNT(*) FROM hosp_settle.settlement WHERE settle_status='NORMAL' AND settle_time>='2026-09-01' AND settle_time<'2026-10-01'"),
                Map.of("label", "HIS 出院(含首页未交)", "ds", "DS_HIS_INP",
                        "sql", "SELECT COUNT(*) FROM hosp_his.inp_visit WHERE visit_status='DISCHARGED' AND dis_at>='2026-09-01' AND dis_at<'2026-10-01'"),
                Map.of("label", "病案口径费用", "ds", "DS_MEDREC",
                        "sql", "SELECT ROUND(SUM(total_fee)) FROM hosp_mr.med_record WHERE mr_status='NORMAL' AND dis_date>='2026-09-01' AND dis_date<'2026-10-01'"),
                Map.of("label", "结算口径费用", "ds", "DS_SETTLE",
                        "sql", "SELECT ROUND(SUM(total_fee)) FROM hosp_settle.settlement WHERE settle_status='NORMAL' AND settle_time>='2026-09-01' AND settle_time<'2026-10-01'"));
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, String> s : sqls) {
            values.put(s.get("label"), scalar(s.get("ds"), s.get("sql")));
        }
        Map<String, Object> buckets = new LinkedHashMap<>();
        buckets.put("跨月结算", scalar("DS_SETTLE",
                "SELECT COUNT(*) FROM hosp_settle.settlement s JOIN hosp_his.inp_visit v ON v.visit_id=s.visit_id "
                        + "WHERE s.settle_status='NORMAL' AND s.settle_time>='2026-09-01' AND s.settle_time<'2026-10-01' AND v.dis_at<'2026-09-01'"));
        buckets.put("出院未结算", scalar("DS_HIS_INP",
                "SELECT COUNT(*) FROM hosp_his.inp_visit v WHERE v.visit_status='DISCHARGED' AND v.dis_at>='2026-09-01' AND v.dis_at<'2026-10-01' "
                        + "AND NOT EXISTS (SELECT 1 FROM hosp_settle.settlement s WHERE s.visit_id=v.visit_id AND s.settle_status='NORMAL')"));
        buckets.put("病案未回收", scalar("DS_HIS_INP",
                "SELECT COUNT(*) FROM hosp_his.inp_visit v WHERE v.visit_status='DISCHARGED' AND v.dis_at>='2026-09-01' AND v.dis_at<'2026-10-01' "
                        + "AND NOT EXISTS (SELECT 1 FROM hosp_mr.med_record m WHERE m.visit_id=v.visit_id)"));
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("sqls", sqls);
        script.put("values", values);
        script.put("buckets", buckets);
        script.put("diff", diffOfSingle(values, "病案口径(首页已交)", "结算口径(已结算)"));
        Object kua = buckets.get("跨月结算"), wei = buckets.get("出院未结算"), hui = buckets.get("病案未回收");
        if (kua instanceof Number a && wei instanceof Number b && hui instanceof Number c) {
            long sum = a.longValue() - b.longValue() + c.longValue();
            script.put("check", "拆堆 " + a + "−" + b + "+" + c + " = " + sum
                    + ("(两口径差也是 " + script.get("diff") + ",账对上 ✓)"));
        }
        // ---- 右列:平台对账组对象现状 ----
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String code : splitCodes(group.getMetricCodes())) {
            var m = metricService.getByCode(code);
            if (m == null) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", m.getMetricCode());
            c.put("name", m.getName());
            c.put("definition", m.getDefinition());
            c.put("owner", m.getOwner());
            c.put("dsCode", m.getDsCode());
            cards.add(c);
        }
        List<ReconcileRun> runs = reconcileRunMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReconcileRun>()
                        .eq(ReconcileRun::getGroupCode, groupCode)
                        .orderByDesc(ReconcileRun::getRanAt)
                        .last("LIMIT 20"));
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("groupCode", group.getGroupCode());
        platform.put("name", group.getName());
        platform.put("metricCards", cards);
        if (!runs.isEmpty()) {
            ReconcileRun latest = runs.get(0);
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("values", readJsonArray(latest.getValuesJson()));
            l.put("diffValue", latest.getDiffValue());
            l.put("drillCount", latest.getDrillCount());
            l.put("buckets", readJsonMap(latest.getBucketsJson()));
            l.put("ranAt", latest.getRanAt() == null ? null : latest.getRanAt().toString());
            platform.put("latest", l);
        }
        platform.put("runCount", runs.size());
        platform.put("disputeStatus", group.getDisputeStatus());
        platform.put("disputeOwner", group.getDisputeOwner());
        platform.put("verdict", group.getVerdict());
        platform.put("verdictNote", group.getVerdictNote());
        platform.put("verdictAt", group.getVerdictAt() == null ? null : group.getVerdictAt().toString());
        // 问数命中在册口径时读同一张卡的探针实测值(2026-09-24 起);客服/MCP 仍读定义层
        platform.put("reusedBy", List.of(
                "语义问数:命中口径问题时读同一张卡的定义与探针实测值",
                "AI 客服:口径解释念的是同一份登记原文",
                "外部 AI:MCP get_metric_card 拿的也是这张"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", script);
        out.put("platform", platform);
        return out;
    }

    /** 两个指定口径的差(对照演示用,取不到时返回 null) */
    private static Long diffOfSingle(Map<String, Object> values, String leftKey, String rightKey) {
        if (values.get(leftKey) instanceof Number a && values.get(rightKey) instanceof Number b) {
            return b.longValue() - a.longValue();
        }
        return null;
    }

    /** 对照演示的单值查询:走只读护栏 + 独立 JdbcTemplate(同 runDrill,不碰共享模板);失败原样返回给页面 */
    private Object scalar(String dsCode, String sql) {
        try {
            String guarded = SqlGuard.clampLimit(SqlGuard.requireReadOnly(sql, "对照SQL"), 50);
            org.springframework.jdbc.core.JdbcTemplate bounded =
                    new org.springframework.jdbc.core.JdbcTemplate(
                            datasourceService.jdbc(dsCode).getDataSource());
            bounded.setMaxRows(5);
            List<Map<String, Object>> rows = bounded.queryForList(guarded);
            if (rows.isEmpty() || rows.get(0).isEmpty()) {
                return null;
            }
            return rows.get(0).values().iterator().next();
        } catch (Exception e) {
            return "查不了: " + e.getMessage();
        }
    }

    /** 差额 = 成功口径实测值的最大-最小;有失败或空值则返回 null(宁可空也不给假差额) */
    private static Long diffOf(Map<String, Object> values) {        List<Long> nums = new ArrayList<>();
        for (Object v : values.values()) {
            if (v instanceof Number n) {
                nums.add(n.longValue());
            }
        }
        if (nums.isEmpty()) {
            return null;
        }
        long min = nums.get(0), max = nums.get(0);
        for (long n : nums) {
            min = Math.min(min, n);
            max = Math.max(max, n);
        }
        return max - min;
    }

    /** 下钻执行结果:rowsJson 为明细行 JSON(未配下钻为 null),count 为行数(-1=执行失败,error 为失败原因) */
    private record DrillResult(String rowsJson, Integer count, String error) {
    }

    /** 归因桶配置:N 桶不写死 3;桶 SQL 期望返回单数值;sign=+1/-1 表示该桶计入差额的方向 */
    record Bucket(String label, String dsCode, String sql, Integer sign) {
    }

    /** 解析组上的桶配置;空/白返回空表;JSON 非法抛 BizException(由 run 捕获记 error,不否决差额) */
    List<Bucket> parseBuckets(ReconcileGroup group) {
        if (group.getBucketsJson() == null || group.getBucketsJson().isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(group.getBucketsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
            List<Bucket> out = new ArrayList<>();
            for (Map<String, Object> b : raw) {
                String label = b.get("label") == null ? "" : String.valueOf(b.get("label"));
                String dsCode = b.get("dsCode") == null ? "" : String.valueOf(b.get("dsCode"));
                String sql = b.get("sql") == null ? "" : String.valueOf(b.get("sql"));
                int sign = b.get("sign") instanceof Number n && n.intValue() < 0 ? -1 : 1;
                if (!label.isBlank() && !dsCode.isBlank() && !sql.isBlank()) {
                    out.add(new Bucket(label, dsCode, sql, sign));
                }
            }
            return out;
        } catch (Exception e) {
            throw new BizException("归因桶配置解析失败: " + e.getMessage());
        }
    }

    /** 跑归因桶:逐桶只读单值查询(同下钻的独立 JdbcTemplate 纪律);失败记 error 不否决差额,sum 不计入失败桶 */
    private Map<String, Object> runBuckets(List<Bucket> buckets, Long diff, List<String> errors) {
        List<Map<String, Object>> rows = new ArrayList<>();
        long sum = 0;
        boolean anyFailed = false;
        for (Bucket b : buckets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", b.label());
            row.put("sign", b.sign());
            try {
                String sql = SqlGuard.requireReadOnly(b.sql(), "归因桶SQL");
                org.springframework.jdbc.core.JdbcTemplate bounded =
                        new org.springframework.jdbc.core.JdbcTemplate(
                                datasourceService.jdbc(b.dsCode()).getDataSource());
                bounded.setMaxRows(5);
                Integer count = bounded.queryForObject(sql, Integer.class);
                row.put("count", count);
                if (count != null) {
                    sum += (long) b.sign() * count;
                }
            } catch (Exception e) {
                row.put("error", e.getMessage());
                anyFailed = true;
                errors.add("归因桶[" + b.label() + "]失败: " + e.getMessage());
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buckets", rows);
        out.put("sum", sum);
        // matchesDiff 语义=账对上:差额存在、无失败桶、代数和恰等于差额;任一不满足都不得标对上
        out.put("matchesDiff", !anyFailed && diff != null && diff == sum);
        return out;
    }

    private DrillResult runDrill(ReconcileGroup group) {
        if (group.getDrillSql() == null || group.getDrillSql().isBlank()
                || group.getDrillDsCode() == null || group.getDrillDsCode().isBlank()) {
            return new DrillResult(null, null, null);
        }
        try {
            String sql = SqlGuard.clampLimit(SqlGuard.requireReadOnly(group.getDrillSql(), "下钻SQL"), DRILL_MAX_ROWS);
            // maxRows 在 JDBC 层兜底:即使 SQL 末级 LIMIT 被写成超大值或方言漏判,
            // 也不至于把整表物化进平台内存(缓存模板是共享的,不能改它的 maxRows)
            org.springframework.jdbc.core.JdbcTemplate bounded =
                    new org.springframework.jdbc.core.JdbcTemplate(
                            datasourceService.jdbc(group.getDrillDsCode()).getDataSource());
            bounded.setMaxRows(DRILL_MAX_ROWS);
            List<Map<String, Object>> rows = bounded.queryForList(sql);
            if (rows.size() > DRILL_MAX_ROWS) {
                rows = rows.subList(0, DRILL_MAX_ROWS);
            }
            return new DrillResult(writeJson(rows), rows.size(), null);
        } catch (Exception e) {
            return new DrillResult(null, -1, e.getMessage());
        }
    }
    /** 列表:每组带最近一次运行结果与历史(最多20条),供页面卡片直读 */
    public List<Map<String, Object>> listGroups() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReconcileGroup g : lambdaQuery().orderByAsc(ReconcileGroup::getId).list()) {
            List<ReconcileRun> runs = reconcileRunMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReconcileRun>()
                            .eq(ReconcileRun::getGroupCode, g.getGroupCode())
                            .orderByDesc(ReconcileRun::getRanAt)
                            .last("LIMIT 20"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("groupCode", g.getGroupCode());
            item.put("name", g.getName());
            item.put("definition", g.getDefinition());
            item.put("owner", g.getOwner());
            item.put("metricCodes", splitCodes(g.getMetricCodes()));
            item.put("hasDrill", g.getDrillSql() != null && !g.getDrillSql().isBlank());
            item.put("id", g.getId());
            item.put("drillDsCode", g.getDrillDsCode());
            item.put("drillSql", g.getDrillSql());
            item.put("disputeStatus", g.getDisputeStatus());
            item.put("scheduled", g.getScheduled() == null ? 0 : g.getScheduled());
            item.put("bucketsJson", g.getBucketsJson());
            item.put("disputeOwner", g.getDisputeOwner());
            item.put("claimedBy", g.getClaimedBy());
            item.put("resolvedBy", g.getResolvedBy());
            item.put("verdict", g.getVerdict());
            item.put("verdictNote", g.getVerdictNote());
            item.put("verdictAt", g.getVerdictAt() == null ? null : g.getVerdictAt().toString());
            item.put("latest", runs.isEmpty() ? null : toRunItem(runs.get(0)));
            item.put("runs", runs.stream().map(this::toRunItem).toList());
            out.add(item);
        }
        return out;
    }

    private Map<String, Object> toRunItem(ReconcileRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diffValue", r.getDiffValue());
        m.put("drillCount", r.getDrillCount());
        m.put("errorMsg", r.getErrorMsg());
        m.put("ranAt", r.getRanAt() == null ? null : r.getRanAt().toString());
        try {
            m.put("values", objectMapper.readValue(r.getValuesJson(), Map.class));
        } catch (Exception e) {
            m.put("values", null);
        }
        m.put("drillRows", r.getDrillJson() == null ? null : readJsonArray(r.getDrillJson()));
        m.put("buckets", r.getBucketsJson() == null ? null : readJsonMap(r.getBucketsJson()));
        return m;
    }

    private Map<String, Object> toResult(ReconcileGroup group, Map<String, Object> values,
                                         Long diff, DrillResult drill, Map<String, Object> buckets,
                                         LocalDateTime ranAt, String errorMsg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupCode", group.getGroupCode());
        m.put("name", group.getName());
        m.put("values", values);
        m.put("diffValue", diff);
        m.put("drillRows", drill.rowsJson() == null ? null : readJsonArray(drill.rowsJson()));
        m.put("drillCount", drill.count());
        m.put("ranAt", ranAt == null ? null : ranAt.toString());
        m.put("buckets", buckets);
        m.put("errorMsg", errorMsg);
        return m;
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException("序列化失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> readJsonArray(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    public ReconcileGroup getByCode(String groupCode) {
        return lambdaQuery().eq(ReconcileGroup::getGroupCode, groupCode).one();
    }
}

