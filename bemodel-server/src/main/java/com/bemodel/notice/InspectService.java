package com.bemodel.notice;

import com.bemodel.notice.mapper.InspectRunMapper;
import com.bemodel.ontology.entity.ReconcileGroup;
import com.bemodel.ontology.service.MetricService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标巡检：全量实测（纯探针 SQL，不依赖 LLM）→ 越限生成平台内告警（幂等）→ 落执行记录。
 * 定时调度见 InspectScheduler；POST /api/inspect/run 手动触发同一逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectService {

    private final MetricService metricService;
    private final NoticeService noticeService;
    private final InspectRunMapper inspectRunMapper;
    private final com.bemodel.ontology.service.ReconcileService reconcileService;

    public Map<String, Object> runAll() {
        List<Map<String, Object>> results = metricService.evaluateAll();
        int alarmed = 0;
        int noticesCreated = 0;
        for (Map<String, Object> r : results) {
            if (!Boolean.TRUE.equals(r.get("alarm"))) {
                continue;
            }
            alarmed++;
            String code = String.valueOf(r.get("metricCode"));
            String name = String.valueOf(r.get("name"));
            Object error = r.get("error");
            String message = error != null
                    ? String.format("指标「%s」探针执行失败: %s", name, error)
                    : String.format("指标「%s」实测 %s 超阈值 %s", name, r.get("value"), r.get("warnThreshold"));
            boolean created = noticeService.createIfAbsent(code, name,
                    (Integer) r.get("value"), (Integer) r.get("warnThreshold"), message);
            if (created) {
                noticesCreated++;
            }
        }
        InspectRun run = new InspectRun();
        run.setEvaluated(results.size());
        run.setAlarmed(alarmed);
        run.setNoticesCreated(noticesCreated);
        inspectRunMapper.insert(run);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluated", results.size());
        result.put("alarmed", alarmed);
        result.put("noticesCreated", noticesCreated);
        result.put("runId", run.getId());
        result.put("results", results);
        return result;
    }

    /**
     * 对账组巡检:scheduled=1 的组逐组跑(复用 run 的假对平守卫/重跑打回全套语义);
     * bm_reconcile_run 即留痕,不建新表。非零差额或口径算不出 → 幂等告警(键 RECON:组编码)。
     */
    public Map<String, Object> runReconcileScheduled() {
        List<ReconcileGroup> groups = reconcileService.lambdaQuery()
                .eq(ReconcileGroup::getScheduled, 1).list();
        int ran = 0;
        int alarmed = 0;
        int noticesCreated = 0;
        for (ReconcileGroup g : groups) {
            Map<String, Object> r;
            try {
                r = reconcileService.run(g.getGroupCode());
            } catch (Exception e) {
                log.warn("对账组巡检失败({}): {}", g.getGroupCode(), e.getMessage());
                continue;
            }
            ran++;
            Object diff = r.get("diffValue");
            String err = (String) r.get("errorMsg");
            boolean diffNonZero = diff instanceof Number n && n.longValue() != 0;
            boolean broken = err != null && !err.isBlank();
            if (!diffNonZero && !broken) {
                continue;
            }
            alarmed++;
            Integer value = diff instanceof Number n ? (int) n.longValue() : null;
            String message = diffNonZero
                    ? String.format("对账组「%s」口径差额 %s(≠0),已打回待认领", g.getName(), value)
                    : String.format("对账组「%s」存在失败项: %s", g.getName(), err);
            if (noticeService.createIfAbsent("RECON:" + g.getGroupCode(), g.getName(), value, 0, message)) {
                noticesCreated++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groupsTotal", groups.size());
        result.put("ran", ran);
        result.put("alarmed", alarmed);
        result.put("noticesCreated", noticesCreated);
        return result;
    }
}
