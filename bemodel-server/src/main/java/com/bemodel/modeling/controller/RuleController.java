package com.bemodel.modeling.controller;

import com.bemodel.common.Result;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.service.RuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rule")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @GetMapping("/list")
    public Result<List<Rule>> list(@RequestParam(required = false) String conceptCode) {
        return Result.ok(ruleService.list(conceptCode));
    }

    @PostMapping
    public Result<Rule> create(@RequestBody Rule rule) {
        return Result.ok(ruleService.create(rule));
    }

    @PutMapping
    public Result<Rule> update(@RequestBody Rule rule) {
        RuleService.requireContract(rule);
        ruleService.updateById(rule);
        return Result.ok(rule);
    }

    @PostMapping("/transition/{ruleCode}")
    public Result<Rule> transition(@PathVariable String ruleCode, @RequestParam String target) {
        return Result.ok(ruleService.transition(ruleCode, target));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ruleService.removeById(id);
        return Result.ok();
    }
}
