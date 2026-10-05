-- 知识双轨（借鉴 5）：问答证据链补文档锚点——DOC_QA 作答引用的片段 docId:seq 逗号分隔。
-- 对齐 matched_concepts 先例（V33）；存量行无文档引用，不回填。
-- 注：MySQL 列定义语序要求 COMMENT 在 FIRST/AFTER 之前（brief 原稿两子句顺序相反，实跑报语法错，仅调序不改语义）。
ALTER TABLE bm_qa_trace ADD COLUMN matched_docs VARCHAR(500)
    COMMENT '命中文档片段 docId:seq 逗号分隔（可空）' AFTER matched_concepts;
