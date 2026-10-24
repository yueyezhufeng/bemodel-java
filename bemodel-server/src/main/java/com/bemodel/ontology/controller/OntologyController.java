package com.bemodel.ontology.controller;

import com.bemodel.common.Result;
import com.bemodel.modeling.service.ReleaseService;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Disjoint;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.service.DisjointService;
import com.bemodel.ontology.service.MissService;
import com.bemodel.ontology.service.OntologyCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 本体治理：概念互斥公理（结构化）CRUD + 本体自检 + 本体增长回路（miss 采集池）。
 * 自检先于发布：POST /api/release/publish 内部复用同一检查。
 */
@RestController
@RequestMapping("/api/ontology")
@RequiredArgsConstructor
public class OntologyController {

    private final DisjointService disjointService;
    private final OntologyCheckService ontologyCheckService;
    private final MissService missService;
    private final ReleaseService releaseService;
    private final com.bemodel.ontology.service.RelationQueryService relationQueryService;

    // ---------- 概念互斥 ----------

    @GetMapping("/disjoint")
    public Result<List<Disjoint>> disjointList() {
        return Result.ok(disjointService.listPairs());
    }

    @PostMapping("/disjoint")
    public Result<Disjoint> disjointCreate(@RequestBody Map<String, String> body) {
        return Result.ok(disjointService.create(
                body.get("conceptACode"), body.get("conceptBCode"), body.get("definition")));
    }

    @DeleteMapping("/disjoint/{id}")
    public Result<Void> disjointDelete(@PathVariable Long id) {
        disjointService.removeById(id);
        return Result.ok();
    }

    // ---------- 本体自检 ----------

    @PostMapping("/check")
    public Result<Map<String, Object>> check() {
        List<OntologyCheckService.Defect> defects = ontologyCheckService.check();
        return Result.ok(Map.of(
                "defects", defects,
                "blockerCount", ontologyCheckService.blockers(defects).size(),
                "warnCount", ontologyCheckService.warns(defects).size()));
    }

    // ---------- 本体增长回路（miss 采集池） ----------

    @GetMapping("/misses")
    public Result<Map<String, Object>> missBoard() {
        return Result.ok(missService.board());
    }

    @PostMapping("/misses/{id}/dismiss")
    public Result<OntologyMiss> missDismiss(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, String> body) {
        return Result.ok(missService.dismiss(id, body == null ? null : body.get("reason")));
    }

    @PostMapping("/misses/{id}/undismiss")
    public Result<OntologyMiss> missUndismiss(@PathVariable Long id) {
        return Result.ok(missService.undismiss(id));
    }

    @PostMapping("/misses/{id}/adopt")
    public Result<Concept> missAdopt(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(missService.adopt(id,
                body.get("code"), body.get("name"), body.get("domainCode"), body.get("definition")));
    }

    @PostMapping("/misses/{id}/adopt-as-term")
    public Result<OntologyMiss> missAdoptAsTerm(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(missService.adoptAsTerm(id, body.get("conceptCode")));
    }

    @PostMapping("/misses/{id}/revoke")
    public Result<OntologyMiss> missRevoke(@PathVariable Long id) {
        return Result.ok(missService.revoke(id));
    }

    @PostMapping("/misses/{id}/classify")
    public Result<Map<String, Object>> missClassify(@PathVariable Long id) {
        return Result.ok(Map.of("suggestion", missService.classify(id)));
    }

    @GetMapping("/graph")
    public Result<Map<String, Object>> graph() {
        return Result.ok(relationQueryService.graph());
    }

    @GetMapping("/relation/preview")
    public Result<Map<String, Object>> relationPreview(@RequestParam String relation, @RequestParam String concept,
            @RequestParam(defaultValue = "closure") String mode, @RequestParam(defaultValue = "outgoing") String direction) {
        return Result.ok(relationQueryService.preview(relation, concept, mode, direction));
    }

    // ---------- 传递闭包（发布时物化，此处只读） ----------

    @GetMapping("/relation/closure")
    public Result<Map<String, Object>> relationClosure(@RequestParam String relation,
                                                       @RequestParam String concept) {
        return Result.ok(releaseService.closureOf(relation, concept));
    }
}
