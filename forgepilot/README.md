# ForgePilot 工业供应链迁移

基于上游 `yueyezhufeng/bemodel-java`，基线提交 `78b4be93b03dbabca044ac06aad2b85557377873`。保留上游 Apache-2.0 文件；本目录记录 ForgePilot 工业本体与接入管理能力，采用独立工业数据库。

## 已落地的基础

- 10 个业务域、45 类对象、287 个属性、58 类有方向及基数的关系。
- 14 项管理规则、5 份动作契约、8 份指标口径、10 个能力问题；动作在本体页面不执行。
- 沿用 BeModel 的对象/属性/关系管理、数据源管理、人工确认映射、术语、知识治理和发布审核底座。
- 默认Spring配置与显式`forgepilot`配置均加载`db/industrial`（V1平台、V2本体、V3连接器）；已应用的工业V1/V2校验保持不变。
- 建模、映射及口径搜索使用工业上下文，前端提供工业管理界面与JDBC工作台。
- 现有 ForgePilot 增加 `/api/demo/ontology/catalog` 与 `/api/demo/ontology/snapshot`。业务实例从同一 SQLite 读取事务投影，合格可用量仍由原业务守卫计算，不另维护一套库存。
- 快照遵循原角色授权；全局实例拒绝供方账号，供方业务继续使用原本的本方工作台。导出不携带会话。

## 数据源与字段映射

`/datasource` 已增加连接器工厂：自动补齐 11 份接入模板、413 条映射契约（126 条现有演示路径，287 条生产字段待确认），支持从模板创建、配置与映射同版本保存、样本检查及变更记录。配置为草稿元数据，不启用采集或业务写入；原 JDBC 工作台继续保留。详见 [CONNECTORS.md](CONNECTORS.md)。

## 供应链对照

| 环节 | 核心对象 | 本次事实基础 | 后续权威来源与验收 |
|---|---|---|---|
| 主数据与工程 | Party、Site、Material、BOM、设备、单位换算 | 业务主体已有演示适配；工厂/物料主记录待接入 | MDM/PLM：源编码消歧、BOM批准版本、单位与有效期 |
| 需求与计划 | CustomerOrderLine、Demand、ProductionOrder、MaterialRequirement | Demand已有；外部订单行引用保留，不能当作已接入订单对象 | ERP/APS：客户交期、需求修订、工单BOM展开 |
| 采购协同 | 供方资格、来源要求、批准来源、Inquiry、Offer、SupplierReply、Commitment | 除供方资格主记录外，其余已有演示适配 | SRM/ERP：供方/场地/物料/时间范围；报价和签署版本 |
| 到货与仓储 | Shipment、GoodsReceipt、Lot、Reservation | 收货、批次、预留已有；发运只定义目标适配 | WMS/TMS：实收与声明量区分、冻结占用、库存新鲜度 |
| 质量与来源 | Evidence、EvidenceVersion、Inspection、SourceVerification、ReleaseDecision | 已有演示适配 | QMS/证书：性能、来源、范围及授权四条依据分别核对 |
| 生产与谱系 | MaterialIssue、ProductionReport、LotGenealogy、Nonconformance | 已定义，未接MES，不生成实例 | MES/QMS：实际投入/产出/损耗、工单与批次谱系 |
| 客户交付 | Delivery、CustomerAcceptance | 已定义，未接入 | TMS/ERP/客户回执：发运与验收独立，订单行粒度 |
| 财务对账 | PurchaseOrderLine、Invoice、InvoiceAllocation、Payment、PaymentAllocation、ProcurementSettlement | 财务完整对象均待接入；现有管理结清不冒充正式付款/发票 | ERP/财务：原币金额、冲销、分配守恒与未分配余额 |
| 退供与售后 | ReturnOrder、Disposition、ServiceCase | 余料处置已有演示适配；其余待接入 | WMS/QMS/CRM：授权、实际退回、索赔与结案分开 |
| 决策与治理 | ActionReceipt、DecisionRecord、ModelRelease | 业务回执已有；正式模型发布与决策记录待接入 | ForgePilot/IAM：确切版本、当前角色、幂等及回执 |

共有 **18 类演示适配定义、27 类待源接入对象**。适配定义不等于已经有记录；前端实时区分 `observed_demo`、`adapter_no_records` 和 `not_integrated`。当前企业、库存、证据及原系统均是虚构演示，未接任何生产 ERP/MES/财务。

## 产物

- [catalog.json](catalog.json)：完整的模型、字段类型/单位/来源、身份、关系基数、权限、动作副作用与版本策略。
- [objects.csv](objects.csv)、[attributes.csv](attributes.csv)、[relations.csv](relations.csv)：对象、字段与关系字典。
- [coverage.csv](coverage.csv)：逐对象接入状态、责任角色和下一步。
- [questions.csv](questions.csv)、[rules.csv](rules.csv)、[metrics.csv](metrics.csv)：能力问题、约束及指标。
- [baseline-provenance.json](baseline-provenance.json)：平台DDL提取来源和原文件SHA-256。
- [MIGRATION.md](MIGRATION.md)：映射、迁移及发布约束。
- [VALIDATION.md](VALIDATION.md)：已执行与待执行的核验记录。

## 本地启动

需要 Docker、JDK21+、Maven3.9+、Node。以下操作只用于新建的工业演示数据库：

```sh
# 仓库根目录：生成模型及管理端静态页面
python3 forgepilot/publish.py
npm ci --prefix bemodel-web
npm run build --prefix bemodel-web

# 创建仅绑定127.0.0.1的独立MySQL；密码生成在忽略目录.runtime/，不提交
python3 forgepilot/dev.py init-db

cd bemodel-server
mvn test -Dtest=IndustrialBoundaryFilterTest
mvn package -DskipTests
cd ..
python3 forgepilot/dev.py start
```

访问 [工业管理端](http://127.0.0.1:18081/)。管理员 `admin/admin123`、建模员 `modeler/model123`、评审员 `reviewer/reviewer123`、只读 `viewer/viewer123` 为本机演示账号。`dev.py` 生成独立本机 JWT/加密密钥，默认关闭远端模型调用。服务日志 `.runtime/backend.log`，停止服务 `python3 forgepilot/dev.py stop`；数据库和卷继续保留。外部使用前需要更换演示账号和部署身份体系，本次未作公网部署。

需要热更新可运行 `npm run dev --prefix bemodel-web`，地址18082，API代理至18081。Compose替代方案：`docker-compose -p forgepilot-ontology -f compose.forgepilot.yml up -d`；不要与直接启动的同端口数据库同时使用。

与已运行的 ForgePilot 同步模型和页面：

```sh
python3 forgepilot/publish.py --forgepilot ../ForgePilot
cd ../ForgePilot/demo/architecture && npm run build
cd ../web && npm run build
```

[ForgePilot 工业本体页面](http://127.0.0.1:8000/ontology/index.html)可连接当前业务快照；沿用采购/质量/仓库/运营员登录，演示密码 `forgepilot-demo`。源码适配位于 `ForgePilot/demo/app/ontology.py`，首次加载需重启原API服务，现有数据库保留。

## 确认边界

模型目前为 **DRAFT**：结构和代码验证不能代替业务owner签认、真实源对账、权限验收或生产发布。发布需要固定本体、映射、规则、动作版本及审批记录。模型版本回退不会撤销库存、付款或其他外部动作。

## PR 验证

干净源码构建、工业测试与本机接口验证摘要见 [PR_VALIDATION.md](PR_VALIDATION.md)。
