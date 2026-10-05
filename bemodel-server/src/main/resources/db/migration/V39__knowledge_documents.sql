-- =============================================================
-- V39: 知识双轨（借鉴 5）——文档轨载体。
--      文档管经验，本体管对象计算：制度文档解析出的纯文本落 content_text
--      （二进制原件不落库、不做下载原件），切分后的片段供混合检索与引用。
--      FULLTEXT ngram 是仓内首例：MySQL8 InnoDB 默认支持 ngram，无需额外配置。
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_document (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    title        VARCHAR(200) NOT NULL COMMENT '文档标题',
    doc_type     VARCHAR(16)  NOT NULL COMMENT 'MD/PDF/DOCX/TXT',
    source       VARCHAR(16)  DEFAULT 'UPLOAD' COMMENT 'UPLOAD/SEED',
    status       VARCHAR(16)  DEFAULT 'DRAFT' COMMENT 'DRAFT/REVIEW/PUBLISHED/DEPRECATED',
    version      INT DEFAULT 1 COMMENT '版本号（同标题重传时递增，旧版置 DEPRECATED）',
    owner        VARCHAR(64) COMMENT '文档负责人',
    uploaded_by  VARCHAR(64) COMMENT '上传人',
    file_size    INT COMMENT '原文件字节数',
    content_hash VARCHAR(64) COMMENT 'SHA-256 内容指纹（重复上传识别）',
    content_text LONGTEXT COMMENT '解析出的纯文本留存（供重切分）',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_doc_status (status),
    KEY idx_doc_hash (content_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='制度文档（知识轨非结构化载体）';

CREATE TABLE IF NOT EXISTS bm_doc_chunk (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id  BIGINT NOT NULL COMMENT '所属文档 id',
    seq          INT NOT NULL COMMENT '片段序号（文档内从 0 起）',
    heading      VARCHAR(500) COMMENT '所属标题路径（如「缴费管理/退费流程」）',
    content      TEXT NOT NULL COMMENT '片段正文（目标约 500 字，相邻重叠 50 字）',
    embedding    JSON NULL COMMENT '向量化结果 JSON 数组（空=未向量化，向量路对该片段关闭）',
    concept_code VARCHAR(64) NULL COMMENT '挂靠概念 code（与本体可空互挂）',
    KEY idx_chunk_doc (document_id),
    FULLTEXT KEY ft_chunk_content (content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文档片段（检索与引用的最小单元）';
