package com.bemodel.knowledge;

import com.bemodel.common.Result;
import com.bemodel.knowledge.entity.Knowledge;
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

/** 知识条目接口：列表/新增/编辑/审批流转（编辑已发布自动回待审；角色门禁在 SecurityConfig URL 层） */
@RestController
@RequestMapping("/api/knowledge/entries")
@RequiredArgsConstructor
public class KnowledgeEntryController {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public Result<List<Knowledge>> list() {
        return Result.ok(knowledgeService.listEntries());
    }

    @PostMapping
    public Result<Knowledge> create(@RequestBody Knowledge entry) {
        return Result.ok(knowledgeService.createEntry(entry));
    }

    @PutMapping("/{id}")
    public Result<Knowledge> update(@PathVariable Long id, @RequestBody Knowledge entry) {
        entry.setId(id);
        return Result.ok(knowledgeService.updateEntry(entry));
    }

    @PostMapping("/{id}/transition")
    public Result<Knowledge> transition(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(knowledgeService.transitionEntry(id, body.get("target")));
    }
}
