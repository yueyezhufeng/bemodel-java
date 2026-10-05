package com.bemodel.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.auth.CurrentUser;
import com.bemodel.knowledge.entity.Document;
import com.bemodel.knowledge.entity.DocChunk;
import com.bemodel.knowledge.entity.Knowledge;
import com.bemodel.knowledge.mapper.DocumentMapper;
import com.bemodel.knowledge.mapper.DocChunkMapper;
import com.bemodel.knowledge.mapper.KnowledgeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识轨服务：文档摄取（解析→切分→向量化尝试→落库）与知识条目治理。
 * 生命周期与发布门禁对齐概念域范式（CurrentUser 校验 + 评审员防自审）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final Set<String> STATUS = Set.of("DRAFT", "REVIEW", "PUBLISHED", "DEPRECATED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("REVIEW", "DEPRECATED"),
            "REVIEW", Set.of("PUBLISHED", "DRAFT", "DEPRECATED"),
            "PUBLISHED", Set.of("DEPRECATED"),
            "DEPRECATED", Set.of("DRAFT"));
    /** 单批送向量化的片段数：一份制度文档几十片，分批避免单请求过大 */
    private static final int EMBED_BATCH = 16;

    private final DocumentMapper documentMapper;
    private final DocChunkMapper docChunkMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final TikaDocParser parser;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeProperties props;
    private final ObjectMapper objectMapper;

    /** 上传摄取：解析→hash 去重→（同标题=新版本）→切分→尝试向量化→落 DRAFT */
    public Document upload(MultipartFile file, String owner) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文档");
        }
        String docType = parser.docTypeOf(file.getOriginalFilename());
        String text;
        try {
            text = parser.parse(file.getInputStream());
        } catch (BizException e) {
            // 解析守卫抛出的具体原因（如损坏/纯扫描件）直接透传给用户，不再被兜底消息掩埋
            log.warn("文档解析失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new BizException("读取上传文件失败，请重试");
        }
        String hash = sha256(text);
        Long aliveSameHash = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getContentHash, hash)
                .ne(Document::getStatus, "DEPRECATED"));
        if (aliveSameHash > 0) {
            throw new BizException("相同内容的文档已存在（含未发布版本），请勿重复上传");
        }
        Document doc = new Document();
        doc.setTitle(stripSuffix(file.getOriginalFilename()));
        doc.setDocType(docType);
        doc.setSource("UPLOAD");
        doc.setStatus("DRAFT");
        doc.setVersion(1);
        doc.setOwner(owner == null || owner.isBlank() ? CurrentUser.username() : owner);
        doc.setUploadedBy(CurrentUser.username());
        doc.setFileSize((int) file.getSize());
        doc.setContentHash(hash);
        doc.setContentText(text);
        Long sameTitle = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getTitle, doc.getTitle())
                .ne(Document::getStatus, "DEPRECATED"));
        if (sameTitle > 0) {
            Integer maxVersion = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                            .eq(Document::getTitle, doc.getTitle()))
                    .stream().map(Document::getVersion).max(Integer::compareTo).orElse(1);
            doc.setVersion(maxVersion + 1);
        }
        documentMapper.insert(doc);
        persistChunks(doc, text);
        return doc;
    }

    /** 解析文本→切分落片段→尝试向量化（失败留空，文档照常走审核） */
    private void persistChunks(Document doc, String text) {
        List<Chunker.Chunk> chunks = Chunker.split(text,
                props.getChunk().getSize(), props.getChunk().getOverlap());
        if (chunks.isEmpty()) {
            throw new BizException("文档解析后没有可用正文，请检查文件内容");
        }
        List<DocChunk> rows = new ArrayList<>(chunks.size());
        for (Chunker.Chunk c : chunks) {
            DocChunk row = new DocChunk();
            row.setDocumentId(doc.getId());
            row.setSeq(c.seq());
            row.setHeading(c.heading());
            row.setContent(c.content());
            rows.add(row);
            docChunkMapper.insert(row);
        }
        embedChunks(doc, rows);
    }

    /** 分批向量化并把结果写回片段（embedding JSON 数组字符串）；失败留空不抛 */
    private void embedChunks(Document doc, List<DocChunk> rows) {
        if (!embeddingClient.enabled()) {
            return;
        }
        for (int i = 0; i < rows.size(); i += EMBED_BATCH) {
            List<DocChunk> batch = rows.subList(i, Math.min(i + EMBED_BATCH, rows.size()));
            List<double[]> vectors = embeddingClient.embed(
                    batch.stream().map(DocChunk::getContent).toList(), "EMBED_INGEST");
            if (vectors.size() != batch.size()) {
                log.info("文档「{}」第 {} 批片段未完成向量化（文档照常走审核，该部分检索走纯关键词）",
                        doc.getTitle(), i / EMBED_BATCH + 1);
                continue;
            }
            for (int j = 0; j < batch.size(); j++) {
                try {
                    batch.get(j).setEmbedding(objectMapper.writeValueAsString(vectors.get(j)));
                    docChunkMapper.updateById(batch.get(j));
                } catch (Exception e) {
                    log.warn("片段向量写入失败（该片段向量路关闭）: {}", e.getMessage());
                }
            }
        }
    }
    /** 文档生命周期：状态机 + 发布门禁（评审员/管理员，防自审——评审员不得发布本人上传的文档） */
    @Transactional
    public Document transition(Long documentId, String target) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BizException("文档不存在: " + documentId);
        }
        if (!STATUS.contains(target)) {
            throw new BizException("非法状态: " + target);
        }
        Set<String> allowed = TRANSITIONS.getOrDefault(doc.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new BizException("不允许从 " + doc.getStatus() + " 流转到 " + target);
        }
        if ("PUBLISHED".equals(target)) {
            if (!CurrentUser.hasAnyRole("REVIEWER", "ADMIN")) {
                throw new BizException("文档发布需评审员（REVIEWER）或管理员（ADMIN）审批");
            }
            String person = doc.getUploadedBy() != null ? doc.getUploadedBy() : doc.getOwner();
            if (!CurrentUser.hasAnyRole("ADMIN")
                    && person != null && person.equalsIgnoreCase(CurrentUser.username())) {
                throw new BizException("评审员不得发布本人上传的文档（" + doc.getTitle() + "），"
                        + "请交由其他评审员审核");
            }
            // 新版本发布：同标题旧 PUBLISHED 一并置 DEPRECATED（对外只有一份现行版）
            documentMapper.selectList(new LambdaQueryWrapper<Document>()
                            .eq(Document::getTitle, doc.getTitle())
                            .eq(Document::getStatus, "PUBLISHED")
                            .ne(Document::getId, doc.getId()))
                    .forEach(old -> {
                        old.setStatus("DEPRECATED");
                        documentMapper.updateById(old);
                    });
        } else if ("DEPRECATED".equals(target) && "PUBLISHED".equals(doc.getStatus())) {
            // 废弃分层（与概念域 H2 对齐）：已发布文档的停用是评审决定（检索/引用只吃 PUBLISHED），
            // 须评审员或管理员；未发布文档的废弃不设门禁——草稿从未对外，废弃即撤销
            if (!CurrentUser.hasAnyRole("REVIEWER", "ADMIN")) {
                throw new BizException("停用已发布文档需评审员（REVIEWER）或管理员（ADMIN）审批");
            }
        }
        doc.setStatus(target);
        documentMapper.updateById(doc);
        return doc;
    }

    /** 物理删除仅限从未发布过的 DRAFT/REVIEW；发布过的文档只能停用（DEPRECATED），不静默消失 */
    @Transactional
    public void delete(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            return;
        }
        if ("PUBLISHED".equals(doc.getStatus()) || "DEPRECATED".equals(doc.getStatus())) {
            throw new BizException("已发布过的文档不可删除，只能停用（DEPRECATED）");
        }
        docChunkMapper.delete(new LambdaQueryWrapper<DocChunk>().eq(DocChunk::getDocumentId, documentId));
        documentMapper.deleteById(documentId);
    }

    /** 文档列表（附片段数）+ 单文档片段（发布前切分预览共用） */
    public List<Map<String, Object>> listDocuments() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .orderByDesc(Document::getUpdatedAt))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("title", d.getTitle());
            row.put("docType", d.getDocType());
            row.put("status", d.getStatus());
            row.put("version", d.getVersion());
            row.put("owner", d.getOwner());
            row.put("uploadedBy", d.getUploadedBy());
            row.put("fileSize", d.getFileSize());
            row.put("chunkCount", docChunkMapper.selectCount(new LambdaQueryWrapper<DocChunk>()
                    .eq(DocChunk::getDocumentId, d.getId())));
            row.put("updatedAt", d.getUpdatedAt());
            out.add(row);
        }
        return out;
    }

    public List<DocChunk> chunksOf(Long documentId) {
        return docChunkMapper.selectList(new LambdaQueryWrapper<DocChunk>()
                .eq(DocChunk::getDocumentId, documentId)
                .orderByAsc(DocChunk::getSeq));
    }

    // ---------- 知识条目治理 ----------

    public Knowledge createEntry(Knowledge entry) {
        if (entry.getCode() == null || entry.getCode().isBlank()
                || entry.getTitle() == null || entry.getTitle().isBlank()
                || entry.getContent() == null || entry.getContent().isBlank()) {
            throw new BizException("条目编码、标题与正文均必填");
        }
        Long dup = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getCode, entry.getCode()));
        if (dup > 0) {
            throw new BizException("条目编码已存在: " + entry.getCode());
        }
        entry.setId(null);
        entry.setSource(entry.getSource() == null || entry.getSource().isBlank() ? "ADMIN" : entry.getSource());
        entry.setStatus("DRAFT");
        entry.setVersion(1);
        entry.setOwner(entry.getOwner() == null || entry.getOwner().isBlank() ? CurrentUser.username() : entry.getOwner());
        knowledgeMapper.insert(entry);
        return entry;
    }

    /** 条目编辑：已发布条目一旦修改自动回到 REVIEW（「改一条走审批」，不允许静默改对外文案） */
    public Knowledge updateEntry(Knowledge entry) {
        Knowledge db = knowledgeMapper.selectById(entry.getId());
        if (db == null) {
            throw new BizException("条目不存在");
        }
        db.setTitle(entry.getTitle() == null ? db.getTitle() : entry.getTitle());
        db.setContent(entry.getContent() == null ? db.getContent() : entry.getContent());
        db.setAppliesTo(entry.getAppliesTo());
        db.setConceptCode(entry.getConceptCode());
        if ("PUBLISHED".equals(db.getStatus())) {
            db.setStatus("REVIEW");
        }
        knowledgeMapper.updateById(db);
        return db;
    }

    /** 条目生命周期：与文档同一张状态机；发布门禁同款（防自审） */
    public Knowledge transitionEntry(Long entryId, String target) {
        Knowledge entry = knowledgeMapper.selectById(entryId);
        if (entry == null) {
            throw new BizException("条目不存在");
        }
        if (!STATUS.contains(target)) {
            throw new BizException("非法状态: " + target);
        }
        Set<String> allowed = TRANSITIONS.getOrDefault(entry.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new BizException("不允许从 " + entry.getStatus() + " 流转到 " + target);
        }
        if ("PUBLISHED".equals(target)) {
            if (!CurrentUser.hasAnyRole("REVIEWER", "ADMIN")) {
                throw new BizException("条目发布需评审员（REVIEWER）或管理员（ADMIN）审批");
            }
            if (!CurrentUser.hasAnyRole("ADMIN")
                    && entry.getOwner() != null && entry.getOwner().equalsIgnoreCase(CurrentUser.username())) {
                throw new BizException("评审员不得发布本人负责的条目（" + entry.getCode() + "），请交由其他评审员审核");
            }
            entry.setVersion(entry.getVersion() + 1);
        } else if ("DEPRECATED".equals(target) && "PUBLISHED".equals(entry.getStatus())) {
            // 与文档/概念域同款：已发布条目的停用是评审决定（客服引用只读 PUBLISHED 条目）
            if (!CurrentUser.hasAnyRole("REVIEWER", "ADMIN")) {
                throw new BizException("停用已发布条目需评审员（REVIEWER）或管理员（ADMIN）审批");
            }
        }
        entry.setStatus(target);
        knowledgeMapper.updateById(entry);
        return entry;
    }

    public List<Knowledge> listEntries() {
        return knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .orderByAsc(Knowledge::getCode));
    }

    /** 按 code 读 PUBLISHED 条目正文；查无/未发布返回 null（调用方决定回退文案）——硬编码迁入的统一读取口 */
    public String entryContent(String code) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getCode, code));
        return k == null || !"PUBLISHED".equals(k.getStatus()) || k.getContent() == null
                ? null : k.getContent();
    }

    /** 客服话术依据位命中条目：按概念码匹配 PUBLISHED 条目（applies_to 逗号标签精确包含），code 升序。
     *  basisFor/basisCodesFor 同源单查——调用方一次取数同时拼文本与引用码，避免同条件两条 SELECT */
    public List<Knowledge> basisEntries(String conceptCode) {
        if (conceptCode == null || conceptCode.isBlank()) {
            return List.of();
        }
        return knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getStatus, "PUBLISHED")
                .like(Knowledge::getAppliesTo, conceptCode)
                .orderByAsc(Knowledge::getCode));
    }

    /** 依据位文本：按概念码单查并拼「标题：正文」文本（KnowledgeServiceTest 契约沿用） */
    public String basisFor(String conceptCode) {
        return basisText(basisEntries(conceptCode));
    }

    /** 依据位文本(List 重载)：预取条目直接格式化——调用方一次取数同时拼引用码与依据文本 */
    public String basisText(List<Knowledge> basis) {
        StringBuilder sb = new StringBuilder();
        for (Knowledge k : basis) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(k.getTitle()).append("：").append(k.getContent());
        }
        return sb.toString();
    }

    /** 依据位引用码：命中条目的 code 列表（回复文本出处引用用，无命中→空列表） */
    public List<String> basisCodesFor(String conceptCode) {
        return basisEntries(conceptCode).stream().map(Knowledge::getCode).toList();
    }

    // ---------- 助手 ----------

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException("内容指纹计算失败");
        }
    }

    private String stripSuffix(String filename) {
        String name = filename == null ? "未命名文档" : filename;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
