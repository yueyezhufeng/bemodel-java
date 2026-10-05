package com.bemodel.ontology;

import com.bemodel.common.BizException;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.service.MetricService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 口径卡编辑白名单:metricCode/lastVal 不可经 PUT 改;formula 置 null 应清空(显式 set 直写)。
 */
@SpringBootTest
class MetricEditTest {

    @Autowired
    private MetricService metricService;

    private static final String CODE = "METRIC_EDIT_M1";

    @AfterEach
    void clean() {
        deleteByCode(CODE);
        deleteByCode(CODE + "_A");
        deleteByCode(CODE + "_B");
    }

    private void deleteByCode(String code) {
        var m = metricService.getByCode(code);
        if (m != null) {
            metricService.removeById(m.getId());
        }
    }

    private Long seed() {
        Metric m = new Metric();
        m.setMetricCode(CODE);
        m.setName("编辑测试指标");
        m.setDefinition("旧定义");
        m.setFormula("旧公式");
        m.setOwner("测试");
        m.setDsCode("DS_HIS");
        m.setProbeSql("SELECT 1");
        m.setWarnThreshold(10);
        metricService.save(m);
        return m.getId();
    }

    @Test
    void updateShouldWriteWhitelistOnlyAndClearNulls() {
        Long id = seed();
        Metric body = new Metric();
        body.setId(id);
        body.setName("新名字");
        body.setFormula(null);
        body.setDefinition("新定义");
        body.setOwner("新负责人");
        body.setWarnThreshold(99);
        body.setMetricCode("SMUGGLED_CODE");
        body.setLastVal(8888);
        body.setLastEvalAt(LocalDateTime.parse("2099-01-01T00:00:00"));
        metricService.updateContent(body);

        Metric after = metricService.getByCode(CODE);
        assertEquals("新名字", after.getName());
        assertNull(after.getFormula(), "formula 置 null 应清空(显式 set 直写)");
        assertEquals("新定义", after.getDefinition());
        assertEquals("新负责人", after.getOwner());
        assertEquals(Integer.valueOf(99), after.getWarnThreshold());
        assertEquals(CODE, after.getMetricCode(), "metricCode 不可经 PUT 改");
        assertNull(after.getLastVal(), "lastVal(巡检产物)不可经 PUT 改");
    }

    @Test
    void updateShouldRejectMissingOrUnknownId() {
        Metric noId = new Metric();
        noId.setName("x");
        assertThrows(BizException.class, () -> metricService.updateContent(noId));

        Metric ghost = new Metric();
        ghost.setId(999999999L);
        ghost.setName("x");
        assertThrows(BizException.class, () -> metricService.updateContent(ghost));
    }

    @Test
    void updateShouldRejectBlankName() {
        Long id = seed();
        Metric body = new Metric();
        body.setId(id);
        body.setName("   ");
        BizException ex = assertThrows(BizException.class, () -> metricService.updateContent(body));
        assertTrue(ex.getMessage().contains("指标名必填"), "空名不得静默写库");
        assertEquals("编辑测试指标", metricService.getByCode(CODE).getName(), "原名未被破坏");
    }

    @Test
    void listProbedShouldFollowInsertionOrder() {
        Metric a = probed(CODE + "_A");
        Metric b = probed(CODE + "_B");
        metricService.save(a);
        metricService.save(b);
        List<Metric> probed = metricService.listProbed();
        int ia = index(probed, a.getMetricCode());
        int ib = index(probed, b.getMetricCode());
        assertTrue(ia >= 0 && ib >= 0, "两张自种探针卡都应在池");
        assertTrue(ia < ib, "探针池按入库先后稳定排序(cap-5 预筛确定性)");
    }

    private Metric probed(String code) {
        Metric m = new Metric();
        m.setMetricCode(code);
        m.setName(code);
        m.setDefinition("排序测试");
        m.setOwner("测试");
        m.setDsCode("DS_HIS");
        m.setProbeSql("SELECT 1");
        return m;
    }

    private int index(List<Metric> probed, String code) {
        for (int i = 0; i < probed.size(); i++) {
            if (code.equals(probed.get(i).getMetricCode())) {
                return i;
            }
        }
        return -1;
    }
}
