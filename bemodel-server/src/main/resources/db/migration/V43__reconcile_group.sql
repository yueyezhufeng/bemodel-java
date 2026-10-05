-- =============================================================
-- V43: 对账分歧工单化(P0-1 第一片)
-- 同一业务指标的多口径绑成「对账组」,跑一次 = 逐口径实测 + 算差额 + 可选下钻明细,
-- 运行记录落库。2026-09-24 医院场景试点(病案口径 153 vs 结算口径 173)是手工版,
-- 本迁移把手工 markdown 清单变成平台对象。
-- =============================================================

CREATE TABLE bm_reconcile_group (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_code    VARCHAR(64)  NOT NULL COMMENT '对账组编码',
    name          VARCHAR(128) NOT NULL COMMENT '业务指标名(如:月出院人数)',
    definition    VARCHAR(512) NULL COMMENT '分歧背景说明(给对账会看)',
    metric_codes  VARCHAR(512) NOT NULL COMMENT '参与口径的指标编码,逗号分隔,至少2个',
    drill_ds_code VARCHAR(64)  NULL COMMENT '明细下钻执行的数据源编码(空=不下钻)',
    drill_sql     TEXT         NULL COMMENT '差额明细下钻SQL(可选,只读,返回明细行)',
    owner         VARCHAR(64)  NULL COMMENT '对账牵头科室',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_group_code (group_code)
) COMMENT='对账口径组(同一业务指标的多口径绑定)';

CREATE TABLE bm_reconcile_run (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_code  VARCHAR(64)  NOT NULL COMMENT '对账组编码',
    values_json VARCHAR(1024) NOT NULL COMMENT '各口径实测值 JSON,如 {"PILOT_DISCHARGE_MED":153}',
    diff_value  BIGINT       NULL COMMENT '口径间差额(最大-最小;有口径实测失败则为 NULL)',
    drill_json  TEXT         NULL COMMENT '下钻明细行 JSON(最多200行,NULL=未配下钻)',
    drill_count INT          NULL COMMENT '下钻明细行数(-1=下钻执行失败,NULL=未配下钻)',
    error_msg   VARCHAR(500) NULL COMMENT '失败口径信息(空=全部口径实测成功)',
    ran_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '运行时间',
    KEY idx_run_group (group_code)
) COMMENT='对账运行记录(每次跑组落一条)';
