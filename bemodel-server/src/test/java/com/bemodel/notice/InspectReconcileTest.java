package com.bemodel.notice;

import com.bemodel.notice.mapper.AlertNoticeMapper;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.entity.ReconcileGroup;
import com.bemodel.ontology.mapper.ReconcileRunMapper;
import com.bemodel.ontology.service.MetricService;
import com.bemodel.ontology.service.ReconcileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对账组巡检:scheduled=1 才被跑 / 非零差额幂等告警。
 * 共享库注意:dev 库里 PILOT_SEP_DISCHARGE 也 scheduled=1,断言只锁自种组;
 * PILOT 组被顺带巡检属预期(真数据真告警),不清理不assert。
 */
@SpringBootTest
class InspectReconcileTest {

    @Autowired
    private InspectService inspectService;
    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private MetricService metricService;
    @Autowired
    private ReconcileRunMapper reconcileRunMapper;
    @Autowired
    private AlertNoticeMapper alertNoticeMapper;

    private static final String G = "INSPECT_RECON_G";
    private static final String M1 = "INSPECT_RECON_M1";
    private static final String M2 = "INSPECT_RECON_M2";

    @AfterEach
    void clean() {
        var g = reconcileService.getByCode(G);
        if (g != null) {
            reconcileService.removeById(g.getId());
        }
        reconcileRunMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.ontology.entity.ReconcileRun>()
                        .eq(com.bemodel.ontology.entity.ReconcileRun::getGroupCode, G));
        deleteMetric(M1);
        deleteMetric(M2);
        alertNoticeMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.notice.AlertNotice>()
                        .eq(com.bemodel.notice.AlertNotice::getMetricCode, "RECON:" + G));
    }

    @Test
    void scheduledGroupShouldRunAndAlarmIdempotently() {
        saveMetric(M1, "SELECT 5");
        saveMetric(M2, "SELECT 9");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        g.setScheduled(1);
        reconcileService.create(g);

        Map<String, Object> r = inspectService.runReconcileScheduled();
        assertTrue(((Number) r.get("ran")).longValue() >= 1, "至少本组被跑");
        assertEquals(1, countRuns(), "自种组应恰好落一条运行");

        long unread = countUnreadNotice();
        assertEquals(1, unread, "差额 4(非零)应产生一条未读告警");

        inspectService.runReconcileScheduled();
        assertEquals(1, countUnreadNotice(), "重复巡检不刷屏(未读告警幂等)");
        assertEquals(2, countRuns(), "每次巡检都落运行历史");
    }

    @Test
    void unscheduledGroupShouldNotRun() {
        saveMetric(M1, "SELECT 5");
        saveMetric(M2, "SELECT 9");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        g.setScheduled(0);
        reconcileService.create(g);

        inspectService.runReconcileScheduled();
        assertEquals(0, countRuns(), "scheduled=0 的组不被巡检碰");
        assertEquals(0, countUnreadNotice(), "不产生告警");
    }

    private int countRuns() {
        Long n = reconcileRunMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.ontology.entity.ReconcileRun>()
                        .eq(com.bemodel.ontology.entity.ReconcileRun::getGroupCode, G));
        return n == null ? 0 : n.intValue();
    }

    private long countUnreadNotice() {
        Long n = alertNoticeMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.notice.AlertNotice>()
                        .eq(com.bemodel.notice.AlertNotice::getMetricCode, "RECON:" + G)
                        .eq(com.bemodel.notice.AlertNotice::getStatus, "未读"));
        return n == null ? 0 : n;
    }

    private ReconcileGroup group() {
        ReconcileGroup g = new ReconcileGroup();
        g.setGroupCode(G);
        g.setName("巡检测试组");
        g.setDefinition("自建自清");
        g.setOwner("测试");
        return g;
    }

    private void saveMetric(String code, String probeSql) {
        Metric m = new Metric();
        m.setMetricCode(code);
        m.setName(code);
        m.setDefinition("测试用");
        m.setOwner("测试");
        m.setDsCode("DS_HIS");
        m.setProbeSql(probeSql);
        metricService.save(m);
    }

    private void deleteMetric(String code) {
        var m = metricService.getByCode(code);
        if (m != null) {
            metricService.removeById(m.getId());
        }
    }
}
