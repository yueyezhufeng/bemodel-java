package com.bemodel.industrial;

import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/datasource/factory")
public class ConnectorFactoryController {
    private final ConnectorFactoryService service;
    public ConnectorFactoryController(ConnectorFactoryService service) { this.service = service; }
    @GetMapping("/templates") public Result<?> templates() { return Result.ok(service.templates()); }
    @GetMapping("/connectors") public Result<?> list() { return Result.ok(service.list()); }
    @PostMapping("/initialize") public Result<?> initialize() { return Result.ok(service.initialize()); }
    @PostMapping("/connectors") public Result<?> create(@RequestBody JsonNode body) { return Result.ok(service.create(body)); }
    @PutMapping("/connectors/{code}") public Result<?> save(@PathVariable String code, @RequestBody JsonNode body) { return Result.ok(service.save(code,body)); }
    @PostMapping("/connectors/{code}/validate") public Result<?> validate(@PathVariable String code, @RequestBody JsonNode body) { return Result.ok(service.validate(code,body)); }
    @PostMapping("/connectors/{code}/sample") public Result<?> sample(@PathVariable String code, @RequestBody JsonNode body) { return Result.ok(service.sample(code,body)); }
    @GetMapping("/connectors/{code}/logs") public Result<?> logs(@PathVariable String code) { return Result.ok(service.logs(code)); }
}
