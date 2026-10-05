package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.common.SqlGuard;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.mapper.MetricMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MetricService extends ServiceImpl<MetricMapper, Metric> {

    private final DatasourceService datasourceService;

    /** 实测指标：到绑定数据源执行探针SQL，回写最近值并判定告警 */
    public Map<String, Object> evaluate(String metricCode) {
        Metric metric = getByCode(metricCode);
        if (metric == null) {
            throw new BizException("指标不存在: " + metricCode);
        }
        if (!StringUtils.hasText(metric.getProbeSql()) || !StringUtils.hasText(metric.getDsCode())) {
            throw new BizException("指标未绑定实测探针: " + metricCode);
        }
        String sql = SqlGuard.requireReadOnly(metric.getProbeSql(), "探针SQL");
        Integer value = datasourceService.jdbc(metric.getDsCode())
                .queryForObject(sql, Integer.class);

        metric.setLastVal(value);
        metric.setLastEvalAt(LocalDateTime.now());
        updateById(metric);

        boolean alarm = metric.getWarnThreshold() != null
                && value != null && value > metric.getWarnThreshold();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricCode", metric.getMetricCode());
        result.put("name", metric.getName());
        result.put("value", value);
        result.put("warnThreshold", metric.getWarnThreshold());
        result.put("alarm", alarm);
        result.put("evaluatedAt", metric.getLastEvalAt());
        return result;
    }

    /** 全量实测（监控巡检） */
    public List<Map<String, Object>> evaluateAll() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Metric m : lambdaQuery().isNotNull(Metric::getProbeSql).list()) {
            try {
                results.add(evaluate(m.getMetricCode()));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("metricCode", m.getMetricCode());
                err.put("name", m.getName());
                err.put("alarm", true);
                err.put("error", e.getMessage());
                results.add(err);
            }
        }
        return results;
    }

    public Metric getByCode(String metricCode) {
        return lambdaQuery().eq(Metric::getMetricCode, metricCode).one();
    }

    /** 已绑探针的口径卡(问数候选):数据源与探针 SQL 均非空才可实测;按入库先后稳定排序(cap-5 预筛确定性) */
    public List<Metric> listProbed() {
        return lambdaQuery().orderByAsc(Metric::getId).list().stream()
                .filter(m -> m.getProbeSql() != null && !m.getProbeSql().isBlank()
                        && m.getDsCode() != null && !m.getDsCode().isBlank())
                .toList();
    }

    /**
     * 口径卡内容编辑(白名单直写,照 ConceptService.updateContent 纪律):
     * metricCode/lastVal/lastEvalAt 等巡检产物不可经此改动;
     * null 显式清空——lambdaUpdate set() 直写,避开 MyBatis-Plus NOT_NULL 更新策略跳过 null 的坑。
     */
    public void updateContent(Metric body) {
        if (body.getId() == null) {
            throw new BizException("指标 id 必填");
        }
        if (getById(body.getId()) == null) {
            throw new BizException("指标不存在: " + body.getId());
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw new BizException("指标名必填（口径卡的名是对外称呼，不能置空）");
        }
        lambdaUpdate()
                .eq(Metric::getId, body.getId())
                .set(Metric::getName, body.getName())
                .set(Metric::getDefinition, body.getDefinition())
                .set(Metric::getFormula, body.getFormula())
                .set(Metric::getOwner, body.getOwner())
                .set(Metric::getConceptCode, body.getConceptCode())
                .set(Metric::getDsCode, body.getDsCode())
                .set(Metric::getProbeSql, body.getProbeSql())
                .set(Metric::getWarnThreshold, body.getWarnThreshold())
                .update();
    }
}
