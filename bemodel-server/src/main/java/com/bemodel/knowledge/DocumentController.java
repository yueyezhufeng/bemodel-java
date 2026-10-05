package com.bemodel.knowledge;

import com.bemodel.common.Result;
import com.bemodel.knowledge.entity.Document;
import com.bemodel.knowledge.entity.DocChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 知识库文档接口：上传/列表/切分预览/审核流转/删除（角色门禁在 SecurityConfig URL 层） */
@RestController
@RequestMapping("/api/knowledge/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final KnowledgeService knowledgeService;

    @PostMapping
    public Result<Document> upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "owner", required = false) String owner) {
        return Result.ok(knowledgeService.upload(file, owner));
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(knowledgeService.listDocuments());
    }

    @GetMapping("/{id}/chunks")
    public Result<List<DocChunk>> chunks(@PathVariable Long id) {
        return Result.ok(knowledgeService.chunksOf(id));
    }

    /** body 形如 {"target":"PUBLISHED"}，与概念域 transition 契约一致 */
    @PostMapping("/{id}/transition")
    public Result<Document> transition(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(knowledgeService.transition(id, body.get("target")));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return Result.ok(null);
    }
}
