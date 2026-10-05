package com.bemodel.ontology.controller;

import com.bemodel.common.Result;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.service.MetricService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metric")
@RequiredArgsConstructor
public class MetricController {

    private final MetricService metricService;

    @GetMapping("/list")
    public Result<List<Metric>> list() {
        return Result.ok(metricService.list());
    }

    @PostMapping
    public Result<Metric> create(@RequestBody Metric metric) {
        metricService.save(metric);
        return Result.ok(metric);
    }

    @PutMapping
    public Result<Metric> update(@RequestBody Metric metric) {
        metricService.updateContent(metric);
        return Result.ok(metricService.getById(metric.getId()));
    }

    @PostMapping("/evaluate/{metricCode}")
    public Result<Map<String, Object>> evaluate(@PathVariable String metricCode) {
        return Result.ok(metricService.evaluate(metricCode));
    }

    @PostMapping("/evaluate-all")
    public Result<List<Map<String, Object>>> evaluateAll() {
        return Result.ok(metricService.evaluateAll());
    }
}
