package com.bemodel.ontology.controller;

import com.bemodel.common.Result;
import com.bemodel.ontology.entity.ReconcileGroup;
import com.bemodel.ontology.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reconcile")
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileService reconcileService;

    @PostMapping
    public Result<ReconcileGroup> create(@RequestBody ReconcileGroup group) {
        return Result.ok(reconcileService.create(group));
    }

    /** 编辑:白名单直写,groupCode/分歧系列不可改 */
    @PutMapping
    public Result<Void> update(@RequestBody ReconcileGroup body) {
        reconcileService.update(body);
        return Result.ok(null);
    }

    /** 列表:每组带最近一次运行结果与历史(最多20条) */
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(reconcileService.listGroups());
    }

    /** 跑组:逐口径实测+差额+可选下钻,运行落库 */
    @PostMapping("/run/{groupCode}")
    public Result<Map<String, Object>> run(@PathVariable String groupCode) {
        return Result.ok(reconcileService.run(groupCode));
    }

    /** 直连数据库 vs 本体平台对照(试点演示):两边都是实时跑出来的 */
    @GetMapping("/compare/{groupCode}")
    public Result<Map<String, Object>> compare(@PathVariable String groupCode) {
        return Result.ok(reconcileService.compare(groupCode));
    }

    /** 认领:公开谁在牵头对这笔分歧(取登录身份,不自报) */
    @PostMapping("/claim/{groupCode}")
    public Result<ReconcileGroup> claim(@PathVariable String groupCode) {
        return Result.ok(reconcileService.claim(groupCode));
    }

    /** 裁决:以哪侧口径为准,结论入档 */
    @PostMapping("/resolve/{groupCode}")
    public Result<ReconcileGroup> resolve(@PathVariable String groupCode,
                                          @RequestBody Map<String, String> body) {
        return Result.ok(reconcileService.resolve(groupCode, body.get("verdict"), body.get("note")));
    }
}
