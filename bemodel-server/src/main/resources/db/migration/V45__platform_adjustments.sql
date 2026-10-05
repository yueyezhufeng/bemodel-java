-- 平台调整批次(2026-09-24):问数计划模式留痕 / 对账组归因桶+调度+身份落库 / 运行桶结果
ALTER TABLE bm_qa_trace ADD COLUMN plan_mode VARCHAR(16) NULL COMMENT '计划模式 QUERY/METRIC(旧行 NULL 视作 QUERY)';

ALTER TABLE bm_reconcile_group
    ADD COLUMN buckets_json TEXT NULL COMMENT '归因桶配置 JSON:[{label,dsCode,sql,sign}]',
    ADD COLUMN scheduled TINYINT NOT NULL DEFAULT 0 COMMENT '是否挂每日巡检(0/1)',
    ADD COLUMN claimed_by VARCHAR(64) NULL COMMENT '认领人登录名(旧行 NULL=自报时代,展示回退 dispute_owner)',
    ADD COLUMN resolved_by VARCHAR(64) NULL COMMENT '裁决人登录名';

ALTER TABLE bm_reconcile_run ADD COLUMN buckets_json TEXT NULL COMMENT '归因桶结果 JSON:{buckets:[{label,sign,count,error}],sum,matchesDiff}';

-- 试点组预置三桶+开巡检:桶 SQL 与 ReconcileService.compare()(:199-208)/docs/pilot/baseline_reconcile.py 同源,一字不改。
-- UPDATE 型种子:全新环境组不存在则空过,建组后参照本 JSON 手工配置(操作手册已补记)。
UPDATE bm_reconcile_group SET scheduled = 1, buckets_json = '[{"label":"跨月结算","dsCode":"DS_SETTLE","sign":1,"sql":"SELECT COUNT(*) FROM hosp_settle.settlement s JOIN hosp_his.inp_visit v ON v.visit_id=s.visit_id WHERE s.settle_status=''NORMAL'' AND s.settle_time>=''2026-09-01'' AND s.settle_time<''2026-10-01'' AND v.dis_at<''2026-09-01''"},{"label":"出院未结算","dsCode":"DS_HIS_INP","sign":-1,"sql":"SELECT COUNT(*) FROM hosp_his.inp_visit v WHERE v.visit_status=''DISCHARGED'' AND v.dis_at>=''2026-09-01'' AND v.dis_at<''2026-10-01'' AND NOT EXISTS (SELECT 1 FROM hosp_settle.settlement s WHERE s.visit_id=v.visit_id AND s.settle_status=''NORMAL'')"},{"label":"病案未回收","dsCode":"DS_HIS_INP","sign":1,"sql":"SELECT COUNT(*) FROM hosp_his.inp_visit v WHERE v.visit_status=''DISCHARGED'' AND v.dis_at>=''2026-09-01'' AND v.dis_at<''2026-10-01'' AND NOT EXISTS (SELECT 1 FROM hosp_mr.med_record m WHERE m.visit_id=v.visit_id)"}]'
WHERE group_code = 'PILOT_SEP_DISCHARGE';
