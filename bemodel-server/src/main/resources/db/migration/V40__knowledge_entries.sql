-- =============================================================
-- V40: 知识双轨——结构化经验条目（专家经验文案出代码、落成可治理资产）。
--      「改一句发一版」变「改一条走审批」：条目走 DRAFT→REVIEW→PUBLISHED
--      生命周期，客服话术与核对归因按 code 读取。
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_knowledge (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    code         VARCHAR(64)  NOT NULL UNIQUE COMMENT '条目编码（全局唯一）',
    title        VARCHAR(200) NOT NULL COMMENT '条目标题',
    content      TEXT NOT NULL COMMENT '条目正文（专家经验文案）',
    applies_to   VARCHAR(200) COMMENT '适用场景标签（逗号分隔概念码，如 FEE_DETAIL,DISPENSE）',
    concept_code VARCHAR(64) NULL COMMENT '挂靠概念 code（可空互挂）',
    source       VARCHAR(16)  DEFAULT 'SEED' COMMENT 'SEED/ADMIN',
    status       VARCHAR(16)  DEFAULT 'DRAFT' COMMENT 'DRAFT/REVIEW/PUBLISHED/DEPRECATED',
    version      INT DEFAULT 1,
    owner        VARCHAR(64) COMMENT '条目负责人',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识条目（结构化专家经验）';
