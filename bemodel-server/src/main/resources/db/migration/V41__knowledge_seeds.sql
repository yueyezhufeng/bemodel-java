-- =============================================================
-- V41: 知识双轨——首批知识条目种子（10 条，直接 PUBLISHED）。
--      六条核对归因（RECON_ATTR_*，正文逐字取自 ReconciliationService.TIERS 归因句，
--      建议动作与 severity 是技术判定结构，留在代码不迁）+ 四条客服规则
--      （CS_RULE_*，正文逐字取自 CsService 四处静态规则句）。
--      applies_to 存概念码标签（逗号分隔），供客服话术依据位按 rcaCase.conceptCode 匹配。
-- =============================================================

INSERT INTO bm_knowledge (code, title, content, applies_to, source, status, version, owner) VALUES
('RECON_ATTR_VIOLATION_PREPAY_BYPASS', '缴费发药核对·先药后费违规归因',
 '已发药但患者无任何有效缴费记录，先药后费违反流程公理', 'DISPENSE_PAY', 'SEED', 'PUBLISHED', 1, '质控科 何静'),
('RECON_ATTR_RETURNED_PENDING_REFUND', '缴费发药核对·退药未退费归因',
 '已退药但对应费用未退费——业务完成≠财务到账的中间态', 'DISPENSE_RETURN', 'SEED', 'PUBLISHED', 1, '质控科 何静'),
('RECON_ATTR_STUCK_BACKLOG', '缴费发药核对·真实滞留归因',
 '已计费已缴费且医嘱已执行，但药房无发药记录——真实滞留', 'DISPENSE_PAY', 'SEED', 'PUBLISHED', 1, '质控科 何静'),
('RECON_ATTR_IN_FLIGHT', '缴费发药核对·在途待发归因',
 '已缴费但医嘱未执行——在途待发，属正常差异', 'DISPENSE_PAY', 'SEED', 'PUBLISHED', 1, '质控科 何静'),
('RECON_ATTR_CANCELLED_AFTER_PAY', '缴费发药核对·缴费后取消归因',
 '缴费后医嘱被取消（非凌晨批量窗口）', 'FEE_DETAIL,DISPENSE_PAY', 'SEED', 'PUBLISHED', 1, '质控科 何静'),
('RECON_ATTR_NIGHTLY_CANCEL', '缴费发药核对·凌晨批量取消归因',
 '凌晨批量取消窗口内的自然取消（未缴费超时自动取消）', 'DISPENSE_PAY', 'SEED', 'PUBLISHED', 1, '质控科 何静'),
('CS_RULE_FEE_ROOT', '费用投诉排查·根因说明',
 '根因是 LIS v5.2 升级后撤销码 X→C，HIS 计费适配器未同步。', 'FEE_DETAIL', 'SEED', 'PUBLISHED', 1, '客服组 周颖'),
('CS_RULE_FEE_DISPENSE_ORDER', '发药规则·缴费与发药先后',
 '缴费是发药的前置环节，先药后费属违规；反方向按归因分档：「在途待发」与「凌晨批量取消」是正常差异（显式登记防误判），「真滞留」「付费后取消」需核查，退药未退费是待完成中间态。', 'DISPENSE_PAY', 'SEED', 'PUBLISHED', 1, '客服组 周颖'),
('CS_RULE_DISPENSE_SPLIT', '发药规则·医嘱分次发药',
 '本体上「医嘱—调剂发药→发药记录」是 1:N 关系，一张药品医嘱允许拆成多次调剂/发药（拆零、分批发药都是合法场景）。', 'DISPENSE_SPLIT', 'SEED', 'PUBLISHED', 1, '客服组 周颖'),
('CS_RULE_REFUND_PAIR', '发药规则·退药退费成对',
 '可以退药（含部分退药），闭环上退药是发药的逆环节：药房把发药记录置为已退药，收费侧同步退费，两步必须成对。', 'DISPENSE_RETURN', 'SEED', 'PUBLISHED', 1, '客服组 周颖');
