-- =============================================================
-- V44: 对账分歧的认领与裁决(P0-2)
-- 手工版分歧清单每行都有「Owner/状态」两列,第一片(V43)只落了差额,
-- 这里把 状态 流转变成对象:待认领 → 认领(谁在办) → 裁决(以哪侧为准)。
-- 跑组再次跑出非零差额时状态自动打回待认领——分歧没消,工单不算完。
-- =============================================================

ALTER TABLE bm_reconcile_group
    ADD COLUMN dispute_status VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        COMMENT '分歧状态:OPEN待认领/CLAIMED对账中/RESOLVED已裁决',
    ADD COLUMN dispute_owner VARCHAR(64) NULL
        COMMENT '认领人(谁在牵头对这笔分歧)',
    ADD COLUMN verdict VARCHAR(512) NULL
        COMMENT '裁决结论:以哪侧口径为准(一句话,给报表用)',
    ADD COLUMN verdict_note VARCHAR(512) NULL
        COMMENT '裁决依据/备注(对账会上怎么定的)',
    ADD COLUMN verdict_at DATETIME NULL COMMENT '裁决时间';
