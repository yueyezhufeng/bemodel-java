"""Build the versioned industrial ontology. No customer data or credentials."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parent
DOMAINS = [
    ("master", "主数据与工程", "主数据管理员", "MDM / PLM"),
    ("plan", "需求与计划", "销售 / PMC", "ERP / APS"),
    ("source", "采购与供应协同", "采购负责人", "SRM / ERP"),
    ("inbound", "到货与仓储", "仓库负责人", "WMS / TMS"),
    ("quality", "质量与来源放行", "质量负责人", "QMS / 证书平台"),
    ("make", "生产与批次追溯", "生产负责人", "MES"),
    ("deliver", "物流与客户交付", "物流负责人", "TMS / ERP"),
    ("finance", "财务与对账", "财务负责人", "ERP / 财务"),
    ("service", "退供与售后处置", "售后 / 质量", "CRM / QMS / WMS"),
    ("govern", "决策与模型治理", "业务审核人 / 模型管理员", "ForgePilot"),
]
# Actual source collections are explicitly distinguished from proposed enterprise adapters.
# Each attribute: code | label | type | unit | definition. No guessed ERP physical table names.
SPECS = [
 ("Party","业务主体","master","法人、客户、供方或认证方的业务身份；同名不自动合并。","parties","id",
  "id|主体ID|string||租户内稳定业务身份;name|名称|string||展示名称不作为合并键;role|业务角色|enum||buyer/supplier/certification_body"),
 ("Site","工厂与场地","master","组织下的物理生产、收货或认证场地。",None,"id",
  "id|场地ID|string||场地稳定身份;party_id|所属主体|string||所属法人;timezone|时区|string||业务时间解释;address|地址|string||只作定位依据"),
 ("Material","物料","master","由受控编码、规格、基本单位和版本定义的物料。",None,"id",
  "id|物料编码|string||跨系统编号须显式映射;name|物料名称|string||受控名称;specification|规格|string||牌号与技术要求;base_uom|基本单位|string||禁止不同单位直接求和;revision|规格版本|integer||版本变更保留历史"),
 ("UomConversion","单位换算","master","同一物料下有版本及有效期的单位换算；禁止默认吨与kg等同。",None,"id",
  "id|换算ID|string||物料与方向唯一;material_id|物料|string||换算适用物料;from_uom|源单位|string||输入单位;to_uom|目标单位|string||输出单位;numerator|分子|integer||精确有理数;denominator|分母|integer||必须大于零;valid_from|有效起点|datetime||带时区"),
 ("BomRevision","BOM版本","master","批准的产品物料清单版本；不覆盖历史投产依据。",None,"id",
  "id|BOM版本ID|string||物料及版本组合;product_id|产品|string||产品物料编码;revision|版本|string||工程受控版本;status|状态|enum||draft/approved/retired;effective_from|生效时间|datetime||带时区"),
 ("BomLine","BOM行","master","某BOM版本下的组件与精确单位用量。",None,"id",
  "id|BOM行ID|string||BOM版本内唯一;bom_revision_id|BOM版本|string||恰好一个版本;component_id|组件物料|string||物料编码;quantity_numerator|用量分子|integer||有理数表达;quantity_denominator|用量分母|integer||大于零;uom|单位|string||组件基本单位"),
 ("Equipment","设备与产线","master","带组织、产能单位和状态的生产资源。",None,"id",
  "id|设备ID|string||设备稳定身份;site_id|场地|string||归属工厂;status|设备状态|enum||available/maintenance/down;capacity_per_hour|小时产能|integer||须与产能单位一起使用;capacity_uom|产能单位|string||不能默认kg"),
 ("CustomerOrderLine","客户订单行","plan","客户交付承诺的最小行粒度；与内部材料需求分开。",None,"id",
  "id|订单行ID|string||法人及订单行组合;customer_id|客户|string||业务主体;material_id|产品|string||受控物料;quantity|数量|integer||非负整数最小单位;uom|单位|string||基本单位;promised_at|承诺交期|datetime||批准版本的交期;version|版本|integer||变更产生新版本"),
 ("Demand","材料需求行","plan","ForgePilot内部齐套需求；包含用途、来源约束与当前修订。","demands","id",
  "id|需求ID|string||当前案例中稳定;plant|工厂|string||演示场地标签，尚非Site主键;order_line|订单行引用|string||外部行号，需与订单主数据对照;material|物料描述|string||当前为描述，待MDM编码映射;quantity_kg|需求量|integer|kg|需求整数kg;need_by|需要时点|datetime||带时区;source_requirement_id|来源要求|string||空字符串表示无特殊要求;version|版本|integer||批准绑定该版本"),
 ("ProductionOrder","生产工单","plan","绑定产品、BOM版本、产线及计划时间的制造工单。",None,"id",
  "id|工单ID|string||工厂内稳定;demand_id|需求|string||内部需求;equipment_id|设备|string||设备身份;bom_revision_id|BOM版本|string||已批准版本;planned_quantity|计划量|integer||以uom解释;uom|单位|string||产品单位;status|状态|enum||planned/released/running/completed/cancelled"),
 ("MaterialRequirement","工单用料需求","plan","工单BOM展开后的组件需求，保留计算依据。",None,"id",
  "id|用料需求ID|string||工单组件行;production_order_id|工单|string||所属工单;bom_line_id|BOM行|string||计算依据;material_id|物料|string||组件;quantity|需求量|integer||精确最小单位;uom|单位|string||物料单位"),
 ("SupplierQualification","供应商资格","source","物料、组织和有效期范围内的供方准入；不替代批次放行。",None,"id",
  "id|准入ID|string||受控记录;supplier_id|供方|string||业务主体;material_id|物料|string||准入物料;site_id|场地|string||批准场地;valid_to|到期|datetime||超期不得自动沿用;status|状态|enum||pending/approved/suspended/expired"),
 ("SourceRequirement","来源要求","source","需求的来源类别、比例、监管链与证据要求。","source_requirements","id",
  "id|来源要求ID|string||受控规则身份;version|版本|string||批准规则版本;material|物料|string||当前描述;source_category|来源类别|string||不能只凭供方声明判定;post_consumer_pct_min|比例下限|integer|%|0到100;chain_of_custody|监管链|string||物理隔离等模式;tc_required_before_use|投料前需TC|boolean||不能用范围证书替代交易证书"),
 ("ApprovedSource","批准来源","source","物料、供方、场地及有效期组合的批准来源。","approved_sources","id",
  "id|批准来源ID|string||记录身份;material|物料|string||物料范围;supplier_id|供方|string||主体身份;site_id|场地|string||适用场地;valid_from|有效起|datetime||带时区;valid_to|有效止|datetime||带时区;version|版本|integer||受控版本"),
 ("Inquiry","询价与追证请求","source","按需求向特定供方发出的结构化请求。","requests","id",
  "id|请求ID|string||稳定身份;kind|类型|enum||evidence/supply;to_party_id|接收方|string||仅向授权供方共享;demand_id|需求|string||恰好一个需求;status|状态|enum||sent/answered/closed;created_at|创建时间|datetime||带时区"),
 ("Offer","供货报价","source","有数量、费用、时效和未决条件的供方报价；不是库存。","offers","id",
  "id|报价ID|string||稳定身份;resource_id|供方资源|string||待收货前不是买方批次;supplier_id|供方|string||主体身份;material|物料|string||物料范围;quantity_kg|数量|integer|kg|整数kg;unit_price_fen|单价|integer|fen/kg|整数分每kg;total_fen|总价|integer|fen|完整拟支出，含附加费;valid_until|有效期|datetime||批准需重新校验;version|版本|integer||修订隔离;status|状态|enum||quoted/confirmed/declined/expired/rescheduled"),
 ("SupplierReply","供方回复","source","供方带渠道和时间的声明；人工转录不视作已核真的源事实。","supplier_replies","id",
  "id|回复ID|string||稳定身份;party_id|回复方|string||授权供方;channel|渠道|string||消息或人工回流;received_at|接收时间|datetime||按业务时点过滤;text|内容|string||不可直接改变库存;related_demand_id|需求|string||关联需求"),
 ("Commitment","双方供货承诺","source","绑定需求版本和条件的双方承诺，签署不产生实际可用量。","state.commitment","id",
  "id|承诺ID|string||承诺稳定身份;version|版本|integer||每次修订递增;status|状态|enum||pending_supplier/signed等;payload|签署条件|object||包含需求基线、数量、费用与责任;hash|条件摘要|string||对具体payload的摘要，实际源字段另核对"),
 ("Shipment","供方发运","inbound","发运声明与偏差记录，不能替代仓库接收。",None,"id",
  "id|发运ID|string||物流运单行;commitment_id|承诺|string||供货依据;quantity|发运量|integer||声明量;uom|单位|string||须明确单位;departed_at|发运时间|datetime||带时区;status|状态|enum||declared/in_transit/arrived/exception"),
 ("GoodsReceipt","到货接收回执","inbound","WMS接收事实回执；同一business_id不得重复入账。","receipts:goods_receipt","business_id",
  "business_id|回执ID|string||幂等业务键;lot_id|批次|string||已接收批次;demand_id|需求|string||业务需求;quantity_kg|实收量|integer|kg|累计不得超过签署量;result|结果|enum||ok/pass/fail/pending;occurred_at|发生时间|datetime||带时区;recorded_at|入档时间|datetime||系统获知时间"),
 ("Lot","库存批次","inbound","有所有权、场地、物料和实物量的批次，质量与来源分别核验。","lots","id",
  "id|批次ID|string||业务稳定身份;owner_id|所有者|string||业务主体;location|库位|string||演示位置标签;material|物料|string||当前描述;supplier_id|供方|string||来源主体;site_id|场地|string||来源场地;delivery_line|交货单行|string||追溯锚点;quantity_kg|实物量|integer|kg|不得直接称作合格可用;state|库存状态|enum||on_hand/in_transit/pending_check;performance_inspection|性能检验|enum||released/pending/rejected;version|版本|integer||快照修订;updated_at|源更新时间|datetime||用于新鲜度检查"),
 ("Reservation","库存预留","inbound","需求对批次的有效占用；冻结预留不得挪用。","reservations","id",
  "id|预留ID|string||独立分配关系;lot_id|批次|string||恰好一个批次;demand_id|需求|string||恰好一个需求;quantity_kg|占用量|integer|kg|不超过批次可分配范围;status|状态|enum||active/released;frozen|冻结|boolean||其他订单保护;qualification_ref|资格依据|string||批准资格版本"),
 ("Evidence","证据记录","quality","证据身份与适用主体/场地；有效内容在EvidenceVersion中。","evidence","id",
  "id|证据ID|string||稳定证据身份;type|类型|enum||scope_certificate/transaction_certificate/quality_report;standard|标准|string||声明标准名不等于逐条合规;issuer_id|签发方|string||认证主体;holder_id|持有方|string||业务主体;site_id|场地|string||适用场地;valid_from|有效起|datetime||带时区;valid_to|有效止|datetime||带时区"),
 ("EvidenceVersion","证据版本","quality","追加式证据版本及覆盖批次、交货单行、数量；撤回与更正保留。","evidence.versions","version",
  "version|版本号|integer||证据身份内唯一;status|状态|enum||valid/superseded/withdrawn/pending;lot_ids|覆盖批次|array||显式覆盖列表;delivery_lines|交货单行|array||必须与实际交货一致;quantity_kg|覆盖量|integer|kg|覆盖台账不得超用;recorded_at|获知时间|datetime||历史回放依据;issued_at|签发时间|datetime||不等于获知时间"),
 ("Inspection","性能检验回执","quality","QMS性能检验事实，与来源资格和最终放行分别记录。","receipts:inspection","business_id",
  "business_id|回执ID|string||独立幂等键;lot_id|批次|string||被检批次;quantity_kg|检验量|integer|kg|本次覆盖;result|结论|enum||pass/fail/pending;occurred_at|检验时点|datetime||带时区;issued_by|签发人|string||QMS签发责任"),
 ("SourceVerification","来源核验回执","quality","对具体证据版本、批次和交货范围的核验。","receipts:source_verification","business_id",
  "business_id|回执ID|string||独立幂等键;lot_id|批次|string||适用批次;evidence_id|证据|string||证据身份;evidence_version|证据版本|integer||确切版本;result|结论|enum||pass/fail/pending;quantity_kg|核验量|integer|kg|核验范围;occurred_at|发生时间|datetime||带时区"),
 ("ReleaseDecision","授权放行回执","quality","授权角色依据当前范围确认放行；不由Agent自动执行。","receipts:release","business_id",
  "business_id|回执ID|string||独立幂等键;lot_id|批次|string||放行对象;demand_id|需求|string||用途范围;quantity_kg|放行量|integer|kg|不大于收货与有效覆盖;result|结论|enum||pass/fail/pending;issued_by|授权人|string||需当前权限;occurred_at|放行时点|datetime||带时区"),
 ("MaterialIssue","生产领料","make","WMS出库到工单的消耗事实，不能由预留推断。",None,"id",
  "id|领料ID|string||独立出库行;production_order_id|工单|string||目标工单;lot_id|投入批次|string||合格放行批次;quantity|领用量|integer||最小单位;uom|单位|string||与批次一致;occurred_at|领用时间|datetime||带时区"),
 ("ProductionReport","生产报工","make","MES实际产出、报废与时间，不用计划量代替。",None,"id",
  "id|报工ID|string||独立MES事件;production_order_id|工单|string||来源工单;output_lot_id|产出批次|string||成品或半成品;good_quantity|良品量|integer||实际产出;scrap_quantity|报废量|integer||实际损失;uom|单位|string||生产计量单位;occurred_at|报工时间|datetime||带时区"),
 ("LotGenealogy","批次谱系分配","make","投入批次到产出批次的带量关系，分拆混料均保留依据。",None,"id",
  "id|谱系ID|string||独立分配身份;input_lot_id|投入批次|string||来源批次;output_lot_id|产出批次|string||目标批次;quantity|投入分配量|integer||仅在同单位口径守恒;uom|单位|string||换算需受控依据;production_order_id|工单|string||制造证据"),
 ("Nonconformance","不合格事项","make","有隔离范围、原因与处置审批的不合格事件。",None,"id",
  "id|不合格ID|string||事件身份;lot_id|批次|string||受影响批次;quantity|受影响量|integer||隔离范围;uom|单位|string||计量单位;status|状态|enum||open/contained/dispositioned/closed;reason|原因|string||实测或待核实原因"),
 ("Delivery","客户发运交付","deliver","客户订单行与实际出库批次的交付行。",None,"id",
  "id|交付ID|string||发运明细身份;customer_order_line_id|客户订单行|string||交付依据;lot_id|批次|string||出库批次;quantity|发运量|integer||实际发运;uom|单位|string||计量单位;status|状态|enum||planned/dispatched/delivered/exception"),
 ("CustomerAcceptance","客户签收验收","deliver","客户签收与验收事实，不等同发运完成。",None,"id",
  "id|验收ID|string||客户回执;delivery_id|交付行|string||验收范围;accepted_quantity|接收量|integer||实际接收;uom|单位|string||计量单位;result|验收结论|enum||accepted/rejected/partial;occurred_at|验收时间|datetime||带时区"),
 ("PurchaseOrderLine","采购订单行","finance","采购合同下的订货与计价行；承诺不能冒充正式采购单。",None,"id",
  "id|采购行ID|string||法人与单号行组合;commitment_id|承诺|string||可选关联;material_id|物料|string||订货物料;supplier_id|供方|string||交易主体;quantity|数量|integer||订单基本单位;uom|单位|string||采购单位;amount_minor|金额|integer|minor_currency|最小货币单位;currency|币种|string||ISO代码"),
 ("Invoice","供应商发票","finance","有币种、税额和冲红关系的应付发票。",None,"id",
  "id|发票ID|string||税务或财务稳定身份;supplier_id|供方|string||交易主体;gross_minor|价税合计|integer|minor_currency|最小货币单位;tax_minor|税额|integer|minor_currency|不能与含税额混加;currency|币种|string||币种必须相同才可加;status|状态|enum||received/verified/reversed"),
 ("InvoiceAllocation","发票采购分配","finance","发票向采购订单行的带金额关系，防止多对多汇总膨胀。",None,"id",
  "id|分配ID|string||独立分配身份;invoice_id|发票|string||恰好一张发票;purchase_order_line_id|采购行|string||恰好一行;amount_minor|分配额|integer|minor_currency|不超过有效发票余额;currency|币种|string||不隐式换汇"),
 ("Payment","付款事件","finance","权威财务付款或冲销事件，不从采购结清状态推断。",None,"id",
  "id|付款ID|string||财务事件身份;supplier_id|收款方|string||交易主体;amount_minor|金额|integer|minor_currency|原币最小单位;currency|币种|string||原币代码;reversal_of|冲销原事件|string||保留原始记录;occurred_at|发生时间|datetime||带时区"),
 ("PaymentAllocation","付款发票分配","finance","付款向发票的带金额分配；未分配余额显式保留。",None,"id",
  "id|分配ID|string||独立分配身份;payment_id|付款|string||恰好一笔;invoice_id|发票|string||恰好一张;amount_minor|分配额|integer|minor_currency|累计不超有效付款;currency|币种|string||不隐式换汇"),
 ("ProcurementSettlement","采购结清","finance","采购数量、费用、责任的管理结清；不代表已经付款。",None,"id",
  "id|结清ID|string||独立结清单;commitment_id|承诺|string||业务依据;received_quantity|接收量|integer||实际接收口径;uom|单位|string||计量单位;status|状态|enum||open/closed_complete/closed_short;receipt_ref|结清回执|string||ERP管理回执"),
 ("ReturnOrder","退供与退货单","service","有原交易、原因及实际退回回执的逆向物流。",None,"id",
  "id|退货ID|string||逆向单行;lot_id|批次|string||实际退回范围;quantity|退回量|integer||授权量与执行量分别记录;uom|单位|string||计量单位;status|状态|enum||requested/authorized/shipped/received/closed;reason|原因|string||退回依据"),
 ("Disposition","余料与异常处置","service","受限余料的授权、执行与结案记录；授权不扣库存。","state.remainders","id",
  "id|处置ID|string||独立事项;lot_id|批次|string||受影响批次;quantity_kg|范围量|integer|kg|受控范围;status|状态|enum||restricted/authorized_pending_execution/execution_completed/resolved;owner|责任方|string||业务责任;next_action|下一步|string||待办而非执行事实"),
 ("ServiceCase","售后与质量索赔","service","客户投诉、质量追溯和赔付责任的独立生命周期。",None,"id",
  "id|售后ID|string||独立工单;customer_id|客户|string||请求主体;delivery_id|交付|string||原交易;lot_id|批次|string||追溯对象;status|状态|enum||open/investigating/resolved/closed;owner|责任人|string||具名角色"),
 ("ActionReceipt","业务动作回执","govern","ERP/WMS/QMS/供方的事实回执，按business_id幂等。","receipts","business_id",
  "business_id|业务回执ID|string||重复不重复记账;type|类型|enum||分配/收货/检验/核验/放行等;system|来源系统|enum||ERP/WMS/QMS/SUPPLIER;result|结果|enum||ok/pass/fail/pending;quantity_kg|数量|integer|kg|类型范围内解释;occurred_at|业务时间|datetime||有效时间;recorded_at|入档时间|datetime||获知时间"),
 ("DecisionRecord","决策记录","govern","基于确切快照和规则版本的建议或人工裁定，不能代替回执。",None,"id",
  "id|决策ID|string||独立判断身份;release_id|模型版本|string||不可变模型版本;snapshot_id|快照|string||确切输入;actor_id|责任人|string||建议与批准分别记录;status|状态|enum||proposed/approved/rejected/invalidated;evidence_refs|证据列表|array||精确版本引用"),
 ("ModelRelease","模型发布","govern","对象、映射、规则和动作契约组合的不可变发布定义。",None,"id",
  "id|发布ID|string||稳定发布身份;ontology_version|本体版本|string||类型版本;mapping_version|映射版本|string||字段映射版本;rule_version|规则版本|string||确定规则版本;status|状态|enum||draft/review/approved/retired;approved_by|审核人|string||不得由创建者冒充审批"),
]

RELATIONS = [
 ("Party","Site","owns","1","0..n"),("Site","Equipment","contains","1","0..n"),
 ("Material","UomConversion","uses_conversion","1","0..n"),("Material","BomRevision","has_bom","1","0..n"),
 ("BomRevision","BomLine","contains","1","1..n"),("BomLine","Material","component","0..n","1"),
 ("Party","CustomerOrderLine","orders","1","0..n"),("CustomerOrderLine","Demand","drives","1","0..n"),
 ("Demand","SourceRequirement","requires","0..n","0..1"),("Demand","ProductionOrder","schedules","1","0..n"),
 ("ProductionOrder","BomRevision","uses_revision","0..n","1"),("ProductionOrder","Equipment","runs_on","0..n","1"),
 ("ProductionOrder","MaterialRequirement","requires","1","0..n"),("MaterialRequirement","BomLine","derived_from","0..n","1"),
 ("MaterialRequirement","Material","component","0..n","1"),
 ("SupplierQualification","Party","qualifies","0..n","1"),("ApprovedSource","Party","approves_supplier","0..n","1"),
 ("ApprovedSource","Site","approves_site","0..n","1"),("Demand","Inquiry","raises","1","0..n"),
 ("Inquiry","Party","addresses","0..n","1"),("Inquiry","SupplierReply","receives","1","0..n"),
 ("Party","Offer","quotes","1","0..n"),("Offer","Commitment","selected_for","1","0..n"),
 ("Commitment","Demand","binds_revision","0..n","1"),("Commitment","Shipment","ships","1","0..n"),
 ("Shipment","GoodsReceipt","received_as","1","0..n"),("GoodsReceipt","Lot","creates_or_adds","0..n","1"),
 ("Lot","Reservation","allocated_by","1","0..n"),("Demand","Reservation","reserves","1","0..n"),
 ("Party","Lot","owns_lot","1","0..n"),("Evidence","EvidenceVersion","versioned_as","1","1..n"),
 ("EvidenceVersion","Lot","covers","0..n","0..n"),("Evidence","Party","held_by","0..n","1"),
 ("Lot","Inspection","tested_by","1","0..n"),("Lot","SourceVerification","verified_by","1","0..n"),
 ("SourceVerification","EvidenceVersion","binds_version","0..n","1"),("Lot","ReleaseDecision","released_by","1","0..n"),
 ("ProductionOrder","MaterialIssue","consumes","1","0..n"),("MaterialIssue","Lot","issues_from","0..n","1"),
 ("ProductionOrder","ProductionReport","reports","1","0..n"),("Lot","LotGenealogy","input_to","1","0..n"),
 ("LotGenealogy","Lot","output","0..n","1"),("Lot","Nonconformance","affected_by","1","0..n"),
 ("CustomerOrderLine","Delivery","fulfilled_by","1","0..n"),("Delivery","Lot","ships_lot","0..n","1"),
 ("Delivery","CustomerAcceptance","accepted_by","1","0..n"),("Commitment","PurchaseOrderLine","formalized_as","1","0..n"),
 ("Invoice","InvoiceAllocation","allocated_by","1","0..n"),("InvoiceAllocation","PurchaseOrderLine","bills","0..n","1"),
 ("Payment","PaymentAllocation","allocated_by","1","0..n"),("PaymentAllocation","Invoice","settles","0..n","1"),
 ("Commitment","ProcurementSettlement","closed_by","1","0..n"),("Lot","ReturnOrder","returned_by","1","0..n"),
 ("Lot","Disposition","managed_by","1","0..n"),("Delivery","ServiceCase","investigated_by","1","0..n"),
 ("ServiceCase","Lot","traces_to","0..n","0..1"),("DecisionRecord","ModelRelease","uses_release","0..n","1"),
 ("ReleaseDecision","ActionReceipt","proven_by","1","1"),
]

RULES = [
 ("R01","库存、承诺与合格可用分离","inbound","Lot,Offer,Commitment,Reservation","只有当前用途、证据版本、收货、性能检验、来源核验及授权放行均成立的数量进入合格可用量。","ForgePilot/demo/app/workflow.py"),
 ("R02","保护已冻结预留","inbound","Reservation,Lot","有效冻结预留不得挪给其他需求；同批有效占用合计不得超过实物量。","ForgePilot/demo/app/workflow.py"),
 ("R03","证据绑定确切版本与范围","quality","Evidence,EvidenceVersion,SourceVerification","证据主体、场地、交货行、批次、数量及有效期逐项核验；范围证书不能代替本次交易证书。","ForgePilot/demo/app/evidence_scope.py"),
 ("R04","回执幂等与超收拒绝","inbound","ActionReceipt,GoodsReceipt,Commitment","同business_id同内容回查原回执，不同内容拒绝；累计收货不超过签署数量。","ForgePilot/demo/app/workflow.py"),
 ("R05","旧需求或承诺批准失效","source","Demand,Commitment,Offer","数量、交期或条件变化后重新审核；旧签署和库存占用保留到释放回执。","ForgePilot/demo/app/workflow.py"),
 ("R06","新鲜度与来源冲突阻断","plan","Demand,Lot","过期快照、未裁决来源冲突不能作为新分配或新批准的依据。","ForgePilot/demo/app/input_integrity.py"),
 ("R07","结清、付款与齐套分别计算","finance","ProcurementSettlement,Payment,Demand","采购结清不证明付款，付款不证明收货，承诺不证明实际齐套。","ForgePilot/demo/app/closure.py"),
 ("R08","单位与金额不隐式转换","master","UomConversion,Invoice,Payment","不同物料单位、币种不得直接求和；金额以整数最小货币单位加币种记录。",None),
 ("R09","BOM版本与工单绑定","make","BomRevision,ProductionOrder,MaterialRequirement","工单冻结批准BOM版本；工程变更保留旧版，重新评估在制与采购影响。",None),
 ("R10","投入产出谱系有依据","make","LotGenealogy,MaterialIssue,ProductionReport","分拆、合并与损耗均有实际事件依据；不能凭关系路径推断物料物理混配。",None),
 ("R11","发票付款分配守恒","finance","Invoice,InvoiceAllocation,Payment,PaymentAllocation","同币种有效分配不超过发票或付款余额；冲销保留原记录，未分配余额单独显示。",None),
 ("R12","客户签收独立于发运","deliver","Delivery,CustomerAcceptance","以实际签收与验收回执判交付，不能以物流发运状态冒充客户验收。",None),
 ("R13","处置授权不直接改库存","service","Disposition,ReturnOrder,Lot","范围确认、质量授权、WMS执行与结案分开；实际退供回执才扣库存。","ForgePilot/demo/app/closure.py"),
 ("R14","Agent建议与授权写入分离","govern","DecisionRecord,ActionReceipt","只读工具不给业务写权限；批准绑定当前身份、版本、幂等键与业务守卫。","ForgePilot/demo/app/agent_runtime.py"),
]

CQS = [
 ("CQ01","当前需求为什么缺料，实际合格可用是多少？","plan","Demand,Lot,Reservation,EvidenceVersion,ReleaseDecision","R01,R02,R03,R06","现有演示业务可核对"),
 ("CQ02","哪些供方可补缺口，费用和交付条件是什么？","source","Inquiry,Offer,Commitment,ApprovedSource","R05,R07","现有演示业务可核对"),
 ("CQ03","某批材料能否用于当前需求，证据覆盖是否重复使用？","quality","Evidence,EvidenceVersion,SourceVerification,ReleaseDecision","R01,R03","现有演示业务可核对"),
 ("CQ04","短收、改期、撤证对当前齐套和旧承诺有什么影响？","inbound","GoodsReceipt,Commitment,Reservation,Demand","R01,R04,R05","现有演示业务可核对"),
 ("CQ05","工程变更影响哪些工单、组件与已下采购？","make","BomRevision,BomLine,ProductionOrder,MaterialRequirement,PurchaseOrderLine","R08,R09","待PLM/MES/ERP数据接入"),
 ("CQ06","成品涉及哪些原料批次、检验及客户交付？","make","LotGenealogy,MaterialIssue,ProductionReport,Lot,Delivery","R03,R10","待MES/TMS数据接入"),
 ("CQ07","订单是否实际交付并被客户验收？","deliver","CustomerOrderLine,Delivery,CustomerAcceptance","R12","待ERP/TMS/客户回执接入"),
 ("CQ08","采购、发票和付款是否一致，未分配差额是多少？","finance","PurchaseOrderLine,Invoice,InvoiceAllocation,Payment,PaymentAllocation","R07,R11","待财务数据接入"),
 ("CQ09","受限余料谁负责，何时授权、执行和结案？","service","Disposition,Lot,ActionReceipt","R13","现有演示业务可核对"),
 ("CQ10","哪版模型、快照和证据支撑了当前决策？","govern","DecisionRecord,ModelRelease,ActionReceipt","R14","动作回执已有；模型发布与决策记录待接入"),
]

def build():
    domains = [dict(id=i,name=n,owner=o,systems=s) for i,n,o,s in DOMAINS]
    domain_by_id = {d['id']: d for d in domains}
    entities = []
    for code,name,domain,definition,source,key,attrs in SPECS:
        attributes=[]
        for ordinal,spec in enumerate(attrs.split(';')):
            field,label,type_,unit,meaning=spec.split('|')
            mapped=bool(source)
            if code == 'ApprovedSource' and field in ('valid_from','valid_to'):
                type_='date'
                meaning += '；源为日期精度，按案例Asia/Shanghai解释，不补造日内时点'
            if code == 'Evidence' and field in ('valid_from','valid_to') or code == 'EvidenceVersion' and field == 'issued_at':
                type_='date_or_datetime'
                meaning += '；源支持日期或带时区时点，保留原精度，日期按案例Asia/Shanghai解释'
            attributes.append(dict(code=field,name=label,type=type_,unit=unit or None,
                definition=meaning,is_key=field==key,required=field==key,
                nullable=field!=key,unknown_policy="unknown，不补0，不推断不存在",
                source_field=field if mapped else None,
                mapping_status="adapter_defined" if mapped else "proposed",
                sensitivity="commercial" if domain in ('source','finance') else "internal",sort=ordinal))
        entities.append(dict(code=code,name=name,domain=domain,definition=definition,
            identity=("tenant_id + source_system + source_record_id" if code!='EvidenceVersion' else "tenant_id + evidence_id + version"),
            owner=domain_by_id[domain]['owner'],source_system=domain_by_id[domain]['systems'],
            source_collection=source,source_key=key,availability="demo_adapter" if source else "proposed",
            attributes=attributes,temporal_policy="保留业务有效时间、源更新时间与采集时间；缺失显式标记",
            access_policy="买方当前组织范围；供方业务数据仅本方；财务字段单独授权"))
    relations=[dict(id=f"REL{i:02}",from_entity=a,to_entity=b,name=n,
        from_cardinality=ca,to_cardinality=cb,transitive=False,
        valid_time="随源事实及具体版本",authority="待源业务owner逐项签认",
        evidence_required=True,permission="两端分别验权，不沿关系自动扩大权限",
        status="design",description=f"{a} → {b}：{n}；基数为模型目标约束，不能由外键存在推断业务成立")
        for i,(a,b,n,ca,cb) in enumerate(RELATIONS,1)]
    rules=[dict(id=i,name=n,domain=d,entities=e.split(','),expression=x,source_code=s,
        status="existing_demo_guard" if s else "proposed",owner=domain_by_id[d]['owner']) for i,n,d,e,x,s in RULES]
    questions=[dict(id=i,question=q,domain=d,entities=e.split(','),rules=r.split(','),
        data_status=s,business_acceptance="pending") for i,q,d,e,r,s in CQS]
    actions=[
        dict(id="ACT01",name="批准补供方案",roles=["buyer"],entities=["Demand","Offer","Commitment"],
             precondition="当前需求与报价版本、资格、预算、交期有效；源冲突已裁决",effect="生成待供方确认承诺；不增加实际可用量",endpoint="POST /api/demo/approve"),
        dict(id="ACT02",name="记录到货",roles=["operator"],entities=["GoodsReceipt","Lot"],
             precondition="有效签署与未超量；检验开始后不得直接扩量",effect="写WMS仿真回执与待核验库存",endpoint="参见 ForgePilot OpenAPI /api/demo/simulate"),
        dict(id="ACT03",name="来源与质量授权",roles=["quality"],entities=["EvidenceVersion","ReleaseDecision"],
             precondition="当前证据版本、范围、来源及性能均满足授权要求",effect="记录授权与适用范围；执行回执独立",endpoint="参见 ForgePilot OpenAPI 质量动作"),
        dict(id="ACT04",name="领料与生产报工",roles=["production","warehouse"],entities=["MaterialIssue","ProductionReport"],
             precondition="批准工单/BOM、合格批次、单位一致、现有库存足够",effect="目标：扣减库存并追加MES谱系；本期无执行器",endpoint=None),
        dict(id="ACT05",name="付款与核销",roles=["finance","reviewer"],entities=["Payment","PaymentAllocation"],
             precondition="当前发票、分配余额、职责分离、付款审批与幂等业务键",effect="目标：财务提交与独立回执；本期无执行器",endpoint=None),
    ]
    for action in actions:
        action.update(execution_enabled=False,idempotency="actor_id + idempotency_key + canonical_body_hash",
            failure="拒绝保留原因；结果未知先按原业务键查回执；不可盲目重试",
            compensation="显式冲销/释放/新版本；不删除历史",version_binding="模型release + 案例run + 业务revision + 证据version")
    metric_specs=[
        ('M01','实际材料缺口','Demand','kg','max(0, 当前需求量 - 当前用途合格可用量)','ForgePilot summary.shortfall_kg','现有演示可核对，按需求行而非全库存计算'),
        ('M02','其他订单保护量','Reservation','kg','其他需求的有效冻结预留合计','ForgePilot summary.protected_kg','现有演示为O2保护范围，不冒充全企业口径'),
        ('M03','供应商按时足量交付率','GoodsReceipt','%','按批准交期足量接收的到期采购行数 / 到期采购行数','ERP采购行 + WMS接收','待源接入；退货、改期及分母为0的口径需owner确认'),
        ('M04','一次检验合格率','Inspection','%','首次检验合格批次数 / 首次检验完成批次数','QMS首次检验事件','待完整检验事件；不能由当前批次状态倒推历史'),
        ('M05','生产良品率','ProductionReport','%','实际良品产出 / (实际良品产出 + 实际报废产出)','MES报工','待源接入；相同物料单位、工序和统计窗口'),
        ('M06','客户按时验收率','CustomerAcceptance','%','约定交期内验收完成订单行数 / 到期订单行数','ERP订单 + 客户验收','待源接入；发运不能当验收'),
        ('M07','应付未分配余额','Payment','minor_currency','有效付款额 - 同币种有效付款分配额','财务付款 + PaymentAllocation','待源接入；币种分组、冲销另计，不隐式换汇'),
        ('M08','处置关闭周期','Disposition','minute','实际结案时点 - 处置创建时点','WMS/QMS处置事件','待全生命周期时间戳；未结案列在途账龄而非0'),
    ]
    metrics=[dict(id=i,name=n,entity=e,unit=u,formula=f,authority=a,data_status=s,
        owner=next(x['owner'] for x in entities if x['code']==e),execution_enabled=False,
        window='按业务有效时间、组织范围与明确统计期间；不混入未来获知事实',zero_denominator='not_applicable，不填0%')
        for i,n,e,u,f,a,s in metric_specs]
    return dict(schema_version="1.0.0",release_id="forgepilot-industrial-0.1.0",status="draft",
        title="ForgePilot 工业供应链本体",upstream="https://github.com/yueyezhufeng/bemodel-java",
        scope="工业供应链管理基础模型；当前实例仅来自ForgePilot虚构演示，不代表生产系统接入",
        domains=domains,entities=entities,relations=relations,rules=rules,questions=questions,actions=actions,metrics=metrics,
        unknown_states=["unknown","missing","restricted","not_applicable","source_unavailable","conflict","stale"],
        units={"quantity_kg":"整数kg","money_fen":"整数人民币分（现有演示）","money_minor":"整数最小货币单位 + currency（目标模型）","timestamp":"ISO 8601，必须含时区"},
        release_policy=dict(required=["ontology_version","mapping_version","rule_version","action_version","owner_approval"],
            current="draft；没有业务owner签认，不作为正式生产发布",rollback="模型回退不撤销库存、付款或其他外部动作"))

if __name__ == '__main__':
    catalog=build()
    (ROOT/'catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
    print(f"{len(catalog['domains'])} domains, {len(catalog['entities'])} entities, "
          f"{sum(len(e['attributes']) for e in catalog['entities'])} attributes, {len(catalog['relations'])} relations")
