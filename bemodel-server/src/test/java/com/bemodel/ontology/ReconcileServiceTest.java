package com.bemodel.ontology;

import com.bemodel.common.BizException;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.entity.ReconcileGroup;
import com.bemodel.ontology.service.MetricService;
import com.bemodel.ontology.service.ReconcileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对账组第一片验证:建组校验 / 跑组算差并落历史 / 下钻明细 / 只读护栏。
 * 探针用 SELECT 常量,不依赖业务库表结构;数据自建自清,不碰种子数据。
 */
@SpringBootTest
class ReconcileServiceTest {

    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private MetricService metricService;
    @Autowired
    private com.bemodel.ontology.mapper.ReconcileRunMapper reconcileRunMapper;

    private static final String G = "RECONCILE_TEST_GROUP";
    private static final String M1 = "RECONCILE_TEST_M1";
    private static final String M2 = "RECONCILE_TEST_M2";

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        deleteGroup();
        deleteMetric(M1);
        deleteMetric(M2);
    }

    @Test
    void createShouldRejectSingleCaliber() {
        saveMetric(M1, "SELECT 1");
        ReconcileGroup g = group();
        g.setMetricCodes(M1);
        BizException ex = assertThrows(BizException.class, () -> reconcileService.create(g));
        assertTrue(ex.getMessage().contains("至少需要 2 个口径"), "单口径建组必须被拒");
    }

    @Test
    void createShouldRejectUnknownMetric() {
        saveMetric(M1, "SELECT 1");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + ",NO_SUCH_METRIC_XYZ");
        BizException ex = assertThrows(BizException.class, () -> reconcileService.create(g));
        assertTrue(ex.getMessage().contains("指标不存在"), "不存在的指标必须被拒");
    }

    @Test
    void runShouldComputeDiffAndPersistHistory() {
        saveMetric(M1, "SELECT 1");
        saveMetric(M2, "SELECT 2");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        reconcileService.create(g);

        Map<String, Object> r = reconcileService.run(G);
        assertEquals(1L, ((Number) r.get("diffValue")).longValue(), "SELECT 1 vs SELECT 2 差额应为1");
        assertEquals(2, ((Map<?, ?>) r.get("values")).size());

        reconcileService.run(G);
        List<Map<String, Object>> groups = reconcileService.listGroups();
        Map<String, Object> mine = groups.stream()
                .filter(x -> G.equals(x.get("groupCode"))).findFirst().orElseThrow();
        assertEquals(2, ((List<?>) mine.get("runs")).size(), "两次运行应落两条历史");
        assertNotNull(((Map<?, ?>) mine.get("latest")), "最近一次运行应随列表返回");
    }

    @Test
    void runShouldReturnDrillRows() {
        saveMetric(M1, "SELECT 1");
        saveMetric(M2, "SELECT 2");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        g.setDrillDsCode("DS_HIS");
        g.setDrillSql("SELECT 1 AS 差异标记 FROM DUAL WHERE 1=0");
        reconcileService.create(g);

        Map<String, Object> r = reconcileService.run(G);
        assertEquals(1L, ((Number) r.get("diffValue")).longValue(), "SELECT 1 与 SELECT 2 差额应为1");
        assertEquals(0L, ((Number) r.get("drillCount")).longValue(), "恒假条件,明细应为0行");
    }

    @Test
    void drillFailureShouldRecordErrorButNotAffectDiff() {
        saveMetric(M1, "SELECT 1");
        saveMetric(M2, "SELECT 2");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        g.setDrillDsCode("DS_HIS");
        g.setDrillSql("SELECT * FROM no_such_table_xyz");
        reconcileService.create(g);

        Map<String, Object> r = reconcileService.run(G);
        assertEquals(1L, ((Number) r.get("diffValue")).longValue(), "下钻失败不该影响差额");
        assertEquals(-1, ((Number) r.get("drillCount")).intValue(), "下钻失败明细应为-1");
        assertTrue(String.valueOf(r.get("errorMsg")).contains("下钻失败"), "下钻失败原因必须进 errorMsg");
    }

    @Test
    void runShouldExecuteBucketsAndMatchDiff() {
        saveMetric(M1, "SELECT 10");
        saveMetric(M2, "SELECT 12");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        g.setBucketsJson("[{\"label\":\"桶A\",\"dsCode\":\"DS_HIS\",\"sql\":\"SELECT 3\",\"sign\":1},"
                + "{\"label\":\"桶B\",\"dsCode\":\"DS_HIS\",\"sql\":\"SELECT 1\",\"sign\":-1}]");
        reconcileService.create(g);

        Map<String, Object> r = reconcileService.run(G);
        assertEquals(2L, ((Number) r.get("diffValue")).longValue(), "10 vs 12 差额 2");
        Map<?, ?> buckets = (Map<?, ?>) r.get("buckets");
        assertNotNull(buckets, "配了桶的组应返回桶结果");
        assertEquals(2L, ((Number) buckets.get("sum")).longValue(), "3-1=2 与差额对上");
        assertEquals(Boolean.TRUE, buckets.get("matchesDiff"), "sum==diff 标账对上");

        Map<String, Object> mine = reconcileService.listGroups().stream()
                .filter(x -> G.equals(x.get("groupCode"))).findFirst().orElseThrow();
        assertNotNull(((Map<?, ?>) mine.get("latest")).get("buckets"), "桶结果应随历史落库可回看");
    }

    @Test
    void bucketFailureShouldRecordErrorButNotAffectDiff() {
        saveMetric(M1, "SELECT 1");
        saveMetric(M2, "SELECT 2");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        g.setBucketsJson("[{\"label\":\"坏桶\",\"dsCode\":\"DS_HIS\",\"sql\":\"SELECT * FROM no_such_table_bkt\",\"sign\":1},"
                + "{\"label\":\"好桶\",\"dsCode\":\"DS_HIS\",\"sql\":\"SELECT 1\",\"sign\":1}]");
        reconcileService.create(g);

        Map<String, Object> r = reconcileService.run(G);
        assertEquals(1L, ((Number) r.get("diffValue")).longValue(), "桶失败不该影响差额");
        assertTrue(String.valueOf(r.get("errorMsg")).contains("坏桶"), "桶失败原因必须进 errorMsg");
        Map<?, ?> buckets = (Map<?, ?>) r.get("buckets");
        List<?> rows = (List<?>) buckets.get("buckets");
        assertEquals(2, rows.size(), "失败桶也要占一行如实呈现");
        assertNotNull(((Map<?, ?>) rows.get(0)).get("error"), "失败桶行应带 error");
        assertEquals(Boolean.FALSE, buckets.get("matchesDiff"), "有失败桶时不得标账对上");
    }

    @Test
    void runWithoutBucketsShouldOmitBucketResult() {
        saveMetric(M1, "SELECT 1");
        saveMetric(M2, "SELECT 2");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        reconcileService.create(g);

        Map<String, Object> r = reconcileService.run(G);
        assertNull(r.get("buckets"), "无桶配置的组不出归因结果");
    }

    @Test
    void compareShouldReturnLiveBothSides() {
        saveMetric(M1, "SELECT 1");
        saveMetric(M2, "SELECT 2");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        reconcileService.create(g);
        reconcileService.run(G); // 让平台列有最近一次运行

        Map<String, Object> r = reconcileService.compare(G);
        Map<?, ?> script = (Map<?, ?>) r.get("script");
        Map<?, ?> values = (Map<?, ?>) script.get("values");
        assertNotNull(values, "直连列应实时代跑出值");
        assertTrue(values.size() >= 5, "应有 5 句写死 SQL 的实测值");
        // 自洽:三桶代数和 == 两口径差(不锁仿真数,只锁账能对上)
        if (script.get("diff") instanceof Number diff
                && ((Map<?, ?>) script.get("buckets")).get("跨月结算") instanceof Number kua
                && ((Map<?, ?>) script.get("buckets")).get("出院未结算") instanceof Number wei
                && ((Map<?, ?>) script.get("buckets")).get("病案未回收") instanceof Number hui) {
            assertEquals(diff.longValue(),
                    kua.longValue() - wei.longValue() + hui.longValue(), "三桶拆账必须和两口径差对上");
        } else {
            fail("直连列应算出数值差额与三桶: " + script.get("diff") + " / " + script.get("buckets"));
        }
        Map<?, ?> platform = (Map<?, ?>) r.get("platform");
        assertEquals(G, platform.get("groupCode"));
        assertEquals(2, ((List<?>) platform.get("metricCards")).size(), "平台列应带口径卡");
        assertNotNull(platform.get("latest"), "跑过一次后平台列应有最近运行");
        assertNotNull(platform.get("reusedBy"), "平台列应如实标注复用范围");
        assertNull(script.get("constants"), "constants 解说键已删");
        assertNull(script.get("leftover"), "leftover 解说键已删");
    }

    @Test
    void runShouldNullDiffWhenCaliberFails() {
        saveMetric(M1, "SELECT 7");
        saveMetric(M2, "DELETE FROM bm_metric"); // 护栏拒绝=失败口径,模拟数据源断掉
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        reconcileService.create(g);

        Map<String, Object> r = reconcileService.run(G);
        assertTrue(String.valueOf(r.get("errorMsg")).contains(M2), "失败口径应进 errorMsg");
        assertNull(r.get("diffValue"), "有口径失败时差额必须为空,不给残缺口径算出的假对平");
        Map<String, Object> mine = reconcileService.listGroups().stream()
                .filter(x -> G.equals(x.get("groupCode"))).findFirst().orElseThrow();
        assertNull(((Map<?, ?>) mine.get("latest")).get("diffValue"), "落库历史同样不给假差额");
    }

    @Test
    void disputeFlowShouldClaimResolveAndReopen() {
        saveMetric(M1, "SELECT 1");
        saveMetric(M2, "SELECT 3");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        g.setOwner("医保办");
        reconcileService.create(g);

        // 匿名认领必须被拒:认领人以登录账号为准,不再自报
        SecurityContextHolder.getContext().setAuthentication(null);
        BizException anon = assertThrows(BizException.class, () -> reconcileService.claim(G));
        assertTrue(anon.getMessage().contains("需登录"), "匿名认领必须被拒");

        loginAs("张三");
        reconcileService.claim(G);
        ReconcileGroup claimed = reconcileService.getByCode(G);
        assertEquals("CLAIMED", claimed.getDisputeStatus());
        assertEquals("张三", claimed.getDisputeOwner());
        assertEquals("张三", claimed.getClaimedBy(), "认领人应落登录账号");

        BizException twice = assertThrows(BizException.class, () -> {
            loginAs("李四");
            reconcileService.claim(G);
        });
        assertTrue(twice.getMessage().contains("已被 张三"), "已在办的对账不能被静默顶替");

        loginAs("主任");
        reconcileService.resolve(G, "以结算口径为准", "对账会 2026-09-24 拍板");
        ReconcileGroup resolved = reconcileService.getByCode(G);
        assertEquals("RESOLVED", resolved.getDisputeStatus());
        assertEquals("以结算口径为准", resolved.getVerdict());

        BizException again = assertThrows(BizException.class, () -> reconcileService.claim(G));
        assertTrue(again.getMessage().contains("已裁决"), "裁决后认领必须被拒");

        BizException recut = assertThrows(BizException.class,
                () -> reconcileService.resolve(G, "改判:以病案口径为准", null));
        assertTrue(recut.getMessage().contains("已裁决"), "已裁决的结论不能被静默改写,要改等重跑打回");

        // 再跑出非零差额 → 工单自动打回待认领;旧裁决结论保留,但不再代表当前状态
        reconcileService.run(G);
        ReconcileGroup reopened = reconcileService.getByCode(G);
        assertEquals("OPEN", reopened.getDisputeStatus());
        assertNull(reopened.getDisputeOwner(), "重开后认领人清空,等新一轮认领");
        assertEquals("以结算口径为准", reopened.getVerdict(), "旧裁决结论作为历史保留");
    }

    @Test
    void claimAndResolveShouldRecordLoggedInUser() {
        saveMetric(M1, "SELECT 1");
        saveMetric(M2, "SELECT 3");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        reconcileService.create(g);

        SecurityContextHolder.getContext().setAuthentication(null);
        BizException anonResolve = assertThrows(BizException.class,
                () -> reconcileService.resolve(G, "以结算口径为准", null));
        assertTrue(anonResolve.getMessage().contains("需登录"), "匿名裁决必须被拒");

        loginAs("zhangsan");
        reconcileService.claim(G);
        loginAs("lisi");
        reconcileService.resolve(G, "以结算口径为准", "对账会拍板");
        ReconcileGroup resolved = reconcileService.getByCode(G);
        assertEquals("zhangsan", resolved.getClaimedBy(), "认领人=认领时登录账号");
        assertEquals("lisi", resolved.getResolvedBy(), "裁决人=裁决时登录账号");
    }

    @Test
    void resolveShouldRejectBlankVerdict() {
        saveMetric(M1, "SELECT 1");
        saveMetric(M2, "SELECT 1");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        reconcileService.create(g);
        BizException ex = assertThrows(BizException.class,
                () -> reconcileService.resolve(G, " ", null));
        assertTrue(ex.getMessage().contains("裁决结论必填"), "空裁决结论必须被拒");
    }

    @Test
    void guardShouldRejectWriteOperation() {
        saveMetric(M1, "DELETE FROM bm_metric");
        BizException ex = assertThrows(BizException.class, () -> metricService.evaluate(M1));
        assertTrue(ex.getMessage().contains("只读查询"), "写操作探针必须被拒");
    }

    @Test
    void updateShouldWriteWhitelistAndKeepDisputeUntouched() {
        saveMetric(M1, "SELECT 5");
        saveMetric(M2, "SELECT 9");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        reconcileService.create(g);

        // 置非默认分歧态,验证编辑不碰分歧系列
        loginAs("张三");
        reconcileService.claim(G);

        ReconcileGroup body = new ReconcileGroup();
        body.setId(g.getId());
        body.setName("改名了");
        body.setMetricCodes(M1 + "," + M2);
        body.setScheduled(1);
        body.setBucketsJson("[{\"label\":\"桶A\",\"dsCode\":\"DS_HIS\",\"sql\":\"SELECT 1\",\"sign\":1}]");
        body.setGroupCode("SMUGGLED_CODE");
        body.setDisputeStatus("RESOLVED");
        body.setVerdict("偷渡的裁决");
        reconcileService.update(body);

        ReconcileGroup after = reconcileService.getByCode(G);
        assertEquals("改名了", after.getName());
        assertEquals(Integer.valueOf(1), after.getScheduled());
        assertTrue(after.getBucketsJson().contains("桶A"));
        assertEquals(G, after.getGroupCode(), "groupCode 不可经 PUT 改");
        assertEquals("CLAIMED", after.getDisputeStatus(), "分歧状态不可经 PUT 改");
        assertNull(after.getVerdict());
    }

    @Test
    void updateShouldRejectSingleCaliberAndUnknownMetric() {
        saveMetric(M1, "SELECT 5");
        saveMetric(M2, "SELECT 9");
        ReconcileGroup g = group();
        g.setMetricCodes(M1 + "," + M2);
        reconcileService.create(g);

        ReconcileGroup single = new ReconcileGroup();
        single.setId(g.getId());
        single.setMetricCodes(M1);
        assertThrows(BizException.class, () -> reconcileService.update(single),
                "单一口径没有对账对象");

        ReconcileGroup ghost = new ReconcileGroup();
        ghost.setId(g.getId());
        ghost.setMetricCodes(M1 + ",NO_SUCH_METRIC_X");
        assertThrows(BizException.class, () -> reconcileService.update(ghost),
                "口径指标必须逐个存在");
    }

    // ---- helpers ----

    private static void loginAs(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, java.util.Collections.emptyList()));
    }

    private ReconcileGroup group() {
        ReconcileGroup g = new ReconcileGroup();
        g.setGroupCode(G);
        g.setName("测试对账组");
        g.setDefinition("自建自清,与种子无关");
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

    private void deleteGroup() {
        var g = reconcileService.getByCode(G);
        if (g != null) {
            reconcileService.removeById(g.getId());
        }
        // 运行历史按 group_code 落库、组删不级联,必须显式清,否则断言吃到上次残留
        reconcileRunMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.ontology.entity.ReconcileRun>()
                        .eq(com.bemodel.ontology.entity.ReconcileRun::getGroupCode, G));
    }

    private void deleteMetric(String code) {
        var m = metricService.getByCode(code);
        if (m != null) {
            metricService.removeById(m.getId());
        }
    }
}
