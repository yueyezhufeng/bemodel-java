# BeModel 本体建模平台

> 一句话定位：把医院九套业务系统（HIS / LIS / PACS / EMR / 药房 / 收费 / 门诊 / 护士站 / 物资）的数据**不搬家、不复制**，通过「本体 + 映射」建成统一的语义层，让规则引擎和 AI 都能直接基于业务语义工作。

BeModel 是一个语义层（Semantic Layer）平台：数据留在各业务库里，平台只维护「概念 → 属性 → 物理表列」的映射与值字典，在此之上提供本体建模、版本化发布、规则/指标口径管理、链路追溯、病案质控、根因分析、AI 客服与智能问数等能力，并内置认证授权与密钥加密。

设计哲学：**物理自治、逻辑统一**——避开数据搬迁和图数据库的成本，语义层随使用持续生长。

## V0.1.4 更新亮点

相对 [release-V0.1.3](../../tree/release-V0.1.3) 的主要变化：

- **知识双轨**：文档摄取（Tika 解析切分）+ 本地向量化（Ollama 兼容端点，未启动自动退纯关键词）+ 混合检索（FULLTEXT 与向量召回 RRF 融合）；客服新增**文档问答**意图，回复引用条目出处，检索不到不硬凑；散落的硬编码经验迁入知识条目统一治理，知识库页面全流程管理
- **仿真试点三库收口**：语义问数按场景数据源清单过滤（默认 HIS / 病案 / 结算三库），物理映射段、表列白名单、口径卡候选只认清单内数据源；清单外或零映射时如实告知，不再拿旧演示库硬答
- **对账治理工单化**：对账组可编辑（白名单接口），归因桶自动**拆堆**（各桶 sum 与总差额对账、对不上如实标注），认领 / 裁决按登录账号入档，对账组挂每日巡检（非零差额幂等告警，无人值守也能发现问题）；「直连数据库对照」同屏实跑语义层与直连 SQL 的两边结果
- **口径卡可编辑 + 问数接指标探针**：口径卡界面直接编辑（白名单接口直写、换月快捷平移）；指标命中改两段式——规则预筛 + LLM 判命中，探针出数、LLM 只表达不编数
- **短语召回修复**：全文检索切词拼布尔查询，治 ngram 分词下短语语义零命中；测试 JVM 钉空 Key，根治向审计表刷零耗时失败行
- **权限与门禁加固**：评审类按钮按角色显隐（建模员不再看到点了没用的发布 / 审批入口），已发布文档 / 条目的停用加评审门禁

## 核心能力

- **架构全貌（双视图）**：「实体全景」铺出全部概念与映射关系；「分层架构」渲染业务应用 → 推理引擎 → 数据映射 → 本体四层，节点点击直达对应模块；顶部统计卡实时显示规模与**映射覆盖度**
- **本体建模**：业务域 → 概念 → 关系，公理（对称 / 传递 / 互逆 / 互斥）结构化存储，概念支持多父继承；OWL 导入（预览与落库同一计划）、Turtle ABox 导出（Apache Jena，含脱敏）
- **发布门禁（双引擎互证）**：一键打整体不可变快照；发布前自动跑本体自检（对称且反对称、互逆不成对、互斥自身等 7 类缺陷，BLOCKER 拒绝发布），并以 Apache Jena SHACL 真跑约束校验——矛盾的本体出不了门；发布走审批流，REVIEWER 通过后生效
- **数据源绑定与映射**：注册业务库连接（密码 AES-GCM 加密存储），扫描 information_schema 建物理快照；物理列 ↔ 概念属性逐列绑定（AI 推荐、人工确认），值字典归一
- **AI 客服（scene=CS）**：投诉 / 工单自动诊断与一键处置，缴费发药跨库核对，政策类问题模型作答；结构类「能不能」问题从本体结构推理作答 + 数据探针实证；答不了的说法转**澄清任务**跟踪闭环
- **智能问数（scene=ANALYTICS）**：事实问题走 **Ontology2SQL**——LLM 基于本体映射生成 SQL，白名单安全校验后真实执行，证据栏展示 SQL 与数据源；指标名直命中时返回**口径卡**（口径 / 公式 / 探针 SQL / 巡检实测值），口径卡支持界面编辑与换月快捷平移；指标命中走两段式探针（规则预筛 + LLM 判命中），探针出数、LLM 只表达
- **知识双轨**：文档摄取（Tika 解析切分）+ 本地向量化（Ollama 兼容端点，未启动自动退纯关键词）+ 混合检索（FULLTEXT 与向量召回 RRF 融合）；客服**文档问答**意图回复引用条目出处；硬编码经验迁入知识条目，知识库页面统一管理
- **对账治理**：语义层与源数据自动对账出差异，差异走认领 / 裁决工单流（按登录账号入档）；归因桶自动拆堆并与总差额对账；对账组可挂每日巡检无人值守告警；「直连数据库对照」同屏实跑两边结果
- **本体增长回路**：搜索未命中、映射失败、客服 / 问数答不了的说法自动汇入**概念缺口**，按热度排序，AI 归类建议 → 人工采纳 → 草稿 → 发布；**语义漂移**检测术语分叉与口径演进
- **指标巡检与告警**：指标定时巡检（cron 可配），实测值落库，异常进入平台内告警中心；客服错例反馈回流路由 prompt
- **治理与追溯**：病案内涵质控八类规则跨库执法；链路追溯双向 BFS 还原每笔异常的来龙去脉；影响分析支持多跳传播
- **LLM 合规链路**：统一 LLM 网关（DeepSeek），每次调用落审计表并绑定本体版本号；主备双端点故障自动切换、连败熔断，路由健康可视化；AI 不可用时自动降级为规则 / 模板，演示不断链
- **AI 对比实验室**：同一问题三组同题对照（AI+本体 / 裸 SQL / 固定页面），AI 每步工具调用留痕可回放；预设四场景 + 自由提问，演示数据一键重置、核查自动对账
- **本体传导推演**：变更沿本体概念关系逐跳传导推演，每步落库可查
- **MCP 只读开放**：`POST /mcp` 对外开放语义问答 / 搜索 / 指标三只读工具，复用白名单、脱敏与审计链路

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.3 · Spring Security + JJWT · MyBatis-Plus · Flyway · Apache Jena（ARQ + SHACL） |
| 前端 | Vue 3 · Vite · Element Plus · ECharts · Pinia（设计令牌层 + 深色导航布局） |
| 存储 | MySQL（平台元数据库 + 各业务演示库） |
| 安全 | JWT 认证（三角色）· 数据源密码 AES-GCM 加密 · SQL 白名单校验 · RDF 导出脱敏 |
| AI | DeepSeek（主备双端点路由 + 熔断，API Key 走环境变量，无 Key 全链路可降级演示）· MCP 只读工具端点 · Ollama 本地向量化（可选，未启动自动退纯关键词检索） |

## 目录结构

```
bemodel-java
├── bemodel-server              # 后端（Spring Boot）
│   ├── pom.xml
│   └── src
│       ├── main
│       │   ├── java/com/bemodel
│       │   │   ├── BeModelApplication.java
│       │   │   ├── architecture/   # 架构全貌（实体全景 / 分层架构双视图、覆盖度统计）
│       │   │   ├── auth/           # 认证授权：登录、JWT 签发校验、Spring Security 三角色
│       │   │   ├── ontology/       # 本体层：业务域 / 概念 / 多父继承 / 指标 / 术语 / OWL 导入
│       │   │   ├── modeling/       # 规范层：规则 / 动作 / 公理结构化 / 版本发布与门禁
│       │   │   ├── datasource/     # 数据源注册（密码加密）、连接管理与 information_schema 扫描
│       │   │   ├── instance/       # 语义映射：概念属性 ↔ 物理表列、值字典
│       │   │   ├── link/           # 链路追溯（双向 BFS 还原异常链路）
│       │   │   ├── impact/         # 变更影响分析（多跳传播）
│       │   │   ├── governance/     # 数据治理扫描（GovRule / GovScan / GovIssue）
│       │   │   ├── clinical/       # 临床决策：病案内涵质控 + 危重症预警
│       │   │   ├── rca/            # 根因分析（RCA）与处置
│       │   │   ├── cs/             # AI 客服 + 智能问数：场景分叉路由 + 语义问答（Ontology2SQL）+ 口径卡 + 澄清任务流
│       │   │   ├── lab/            # AI 对比实验室：三组对照运行器、工具循环、演示库克隆、四场景剧本
│       │   │   ├── mcp/            # MCP 只读开放端点（语义问答 / 搜索 / 指标三工具）
│       │   │   ├── simulation/     # 本体传导推演（传导目录 + 逐跳传导引擎）
│       │   │   ├── trace/          # 证据链查询（QA / 概念 / 映射，五段式证据）
│       │   │   ├── knowledge/      # 知识双轨：文档摄取切分、本地向量化、混合检索、条目治理
│       │   │   ├── search/         # 语义搜索
│       │   │   ├── notice/         # 平台内告警中心（巡检异常通知）
│       │   │   ├── value/          # 价值实证
│       │   │   ├── flow/           # 医嘱闭环演示（业务闭环）
│       │   │   ├── rdf/            # OWL / Turtle 导出（Apache Jena，导出脱敏）
│       │   │   ├── llm/            # LLM 网关：DeepSeek 主备路由客户端、熔断、审计日志、路由健康
│       │   │   ├── common/         # 统一返回 / 异常 / 状态机 / AES-GCM 加密
│       │   │   ├── config/         # CORS 等配置
│       │   │   └── seed/           # 演示数据生成器（启动自动播种，幂等）
│       │   └── resources
│       │       ├── application.yml # 配置（账号密码与密钥全部走环境变量）
│       │       └── db/migration/   # Flyway 迁移（V1~V45，含本体种子数据与演示账号）
│       └── test/java/com/bemodel   # 单元测试（ontology / modeling / clinical / cs / rdf 等）
├── bemodel-web                 # 前端（Vue 3 + Vite）
│   ├── index.html
│   ├── vite.config.js          # dev 代理 /api → 127.0.0.1:18080
│   ├── package.json
│   └── src
│       ├── api/                # axios 接口封装（自动附 JWT）
│       ├── router/ store/ layout/ components/ utils/
│       ├── styles/             # 设计令牌层（tokens.css）+ Element 主题覆盖
│       └── views/              # 页面：login / architecture / ontology / datasource / glossary
│                               #       link / flow / clinical / gov / ask / cs
│                               #       evolve（概念缺口）/ drift（语义漂移）/ value
│                               #       lab（AI 对比实验室）/ simulation（传导推演）/ trace（证据链）
│                               #       knowledge（知识库）
└── docs/screenshots/intro/     # README 界面截图
```

## 界面截图

### 登录（JWT 三角色）

Spring Security + JWT 认证，内置 ADMIN / EDITOR / VIEWER 三种角色，路由守卫拦截未登录访问。

![登录页](docs/screenshots/intro/00-login.png)

### 架构全貌（分层架构视图）

业务应用 → 推理引擎 → 数据映射 → 本体四层语义架构，节点点击直达对应模块；推理引擎层为规则引擎、本体自检发布门禁与 SHACL 校验双引擎互证。

![架构全貌](docs/screenshots/intro/01-architecture.png)

### 本体管理

业务域 → 概念 → 关系，公理结构化存储，支持多父继承；发布版本与 LLM 能力在同一工作台管理。

![本体管理](docs/screenshots/intro/02-ontology.png)

### 概念邻域图

概念「诊断」的邻域图：支撑检验报告、可能触发感染上报，与「患者」的互斥约束以红色虚线标出。

![概念邻域图](docs/screenshots/intro/03-concept-graph.png)

### 概念缺口（本体增长回路）

提问与搜索中本体未覆盖的说法自动记入缺口：来源（AI 客服 / 智能问数 / 概念搜索 / AI 映射）、热度计数、AI 归类建议、一键去本体处置——平台被问得越多，本体长得越全。

![概念缺口](docs/screenshots/intro/04-concept-gap.png)

### 扩展提案（缺口处置队列）

缺口说法一键 AI 归类（概念 / 属性 / 问题分型），人工确认后创建为草稿概念，走正常发布流程生效；来源、出现次数、最近出现全程可溯。

![扩展提案](docs/screenshots/intro/04-proposals.png)

### AI 客服语义问答

问「多个患者的处方可以一起结算吗？」——结构依据来自本体（结算记录按住院号维系），探针 SQL 实时验证（22 条结算记录对应 22 个住院号，一比一），证据与跳转一体呈现。

![AI 客服语义问答](docs/screenshots/intro/05-cs-semantic.png)

### 数据源绑定

注册九个业务库连接（密码 AES-GCM 加密落库），一键扫描 information_schema 建物理快照。

![数据源绑定](docs/screenshots/intro/06-datasource.png)

### 链路追溯

双向 BFS 还原每笔异常的来龙去脉：医嘱 → 计费 → 缴费 → 发药 / 检验全链路可视，变更影响多跳可查。

![链路追溯](docs/screenshots/intro/07-link.png)

### 病案内涵质控

八类规则跨库执法（如「男性患者诊断卵巢囊肿」命中性别互斥公理 AX-003），每条发现标注跨了哪几个库、引用了哪条规则与公理。

![病案内涵质控](docs/screenshots/intro/08-clinical.png)

### 医嘱闭环

住院 / 门诊医嘱到结算全链路追踪，数据来自业务库实测，支持导出患者 ABox（Turtle）。

![医嘱闭环](docs/screenshots/intro/09-flow.png)

### 智能问数（口径卡）

指标名直命中时返回结构化口径卡：口径定义、计算公式、探针 SQL 与最近一次巡检实测值同源呈现，数字只来自业务库。

![智能问数口径卡](docs/screenshots/intro/10-analytics.png)

### 智能问数（语义解析证据栏）

右侧语义解析栏展开概念匹配、关系推理链与查询逻辑：SQL 经白名单校验后真实执行，结果行与本体概念、实例页一体互跳，数字只来自业务库。

![智能问数语义解析](docs/screenshots/intro/12-analytics-records.png)

### 语义漂移

术语分叉与口径演进检测，全部来自真实数据，无推断占比。

![语义漂移](docs/screenshots/intro/11-semantic-drift.png)

## 快速开始

环境要求：JDK 21+、Maven 3.9+、MySQL 8、Node 18+（推荐 pnpm）。

```bash
# 1. 配置环境变量（数据库账号密码必填；账号需有建库权限，首次启动会自动建库建表）
export MYSQL_USERNAME=your_mysql_user
export MYSQL_PASSWORD=your_mysql_password
export JWT_SECRET=your_jwt_secret          # 可选；不配置使用内置开发密钥（仅限演示）
export APP_SECRET_KEY=your_encrypt_key     # 可选；同上，用于数据源密码加密
export DEEPSEEK_API_KEY=sk-xxxx            # 可选；不配置则 LLM 能力自动降级为规则/模板

# 2. 启动后端（Flyway 自动建表 + DataSeeder 自动生成演示数据与演示账号）
cd bemodel-server
mvn spring-boot:run                    # http://127.0.0.1:18080

# 3. 启动前端
cd bemodel-web
pnpm install
pnpm dev                               # http://127.0.0.1:5173（打开后进入登录页）
```

内置演示账号（仅限演示环境，定义于 [V26__auth_and_security.sql](bemodel-server/src/main/resources/db/migration/V26__auth_and_security.sql)）：

| 账号 | 密码 | 角色 | 权限 |
|---|---|---|---|
| `admin` | `admin123` | ADMIN | 全部能力 |
| `modeler` | `model123` | EDITOR | 建模与写操作（无用户管理） |
| `viewer` | `viewer123` | VIEWER | 只读 + 问答 / 搜索 |

首次启动说明：

- Flyway 自动执行 V1~V45 迁移，创建平台元数据库表结构、本体种子数据与演示账号
- `DataSeeder` 自动生成九个演示业务库（demo_charge / demo_emr / demo_his / demo_lis / demo_material / demo_nurse / demo_opd / demo_pacs / demo_pharmacy），含 40 名**虚构**患者的住院医嘱全闭环数据，并预埋若干「取消未退费」类数据裂缝供质控与追溯演示；数据确定性可重复，幂等跳过
- 未配置 `DEEPSEEK_API_KEY` 时，AI 相关能力自动降级（关键词路由 / 模板作答），平台功能不中断

## 环境变量

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `MYSQL_USERNAME` | 是 | — | 平台库用户名（需有建库权限），同时用作九个演示库连接账号 |
| `MYSQL_PASSWORD` | 是 | — | 平台库密码 |
| `MYSQL_HOST` | 否 | `127.0.0.1` | MySQL 主机 |
| `MYSQL_PORT` | 否 | `3306` | MySQL 端口 |
| `MYSQL_DATABASE` | 否 | `bemodel_platform` | 平台元数据库名 |
| `JWT_SECRET` | 生产必填 | 内置开发密钥 | JWT 签名密钥（HS256）；未配置时启动会告警 |
| `APP_SECRET_KEY` | 生产必填 | 内置开发密钥 | 数据源密码 AES-GCM 加密密钥；更换后需重新保存数据源密码 |
| `DEEPSEEK_API_KEY` | 否 | — | DeepSeek API Key；不配置则自动降级 |
| `DEEPSEEK_BACKUP_BASE_URL` | 否 | — | 备路端点地址；与 `DEEPSEEK_BACKUP_API_KEY` 同配即启用主备双端点路由 |
| `DEEPSEEK_BACKUP_API_KEY` | 否 | — | 备路 API Key |
| `DEEPSEEK_BACKUP_MODEL` | 否 | 同主路模型 | 备路模型名 |
| `EMBEDDING_BASE_URL` | 否 | `http://localhost:11434/v1` | 本地向量化服务（Ollama 兼容端点）；服务未启动时向量路自动关闭，纯关键词检索照常 |
| `EMBEDDING_MODEL` | 否 | `bge-m3` | 向量化模型名 |
| `EMBEDDING_API_KEY` | 否 | — | 向量化服务 API Key（本地 Ollama 通常不需要） |

> 安全约定：API Key、数据库账号密码、JWT / 加密密钥只走环境变量，不落入仓库（`.gitignore` 已排除 `.env*`）；代码中的内置开发密钥（`bemodel-dev-*-do-not-use-in-prod`）仅用于零配置演示，启动日志会明确告警。

## 当前边界

- 演示数据为平台自建的九套 demo 库，尚未接入真实产品线；接上真实库的只读连接后，同样的映射、探针与问答即可工作
- 认证授权已落地（JWT 三角色，写操作需 EDITOR+、评审动作放 REVIEWER；例外：问数 / 搜索 / 澄清 / 评议四组只读型 POST 对全部角色开放，只写问答伴生状态不碰建模数据），生产化仍需补齐：HTTPS 传输、口令策略与定期轮换、审计日志完善、患者敏感字段的出口脱敏收口
- 数据源密码加密与本机部署默认配置面向演示环境；公网部署前必须替换 `JWT_SECRET` / `APP_SECRET_KEY` 并启用传输加密
- LLM 依赖 DeepSeek API，需自行配置 Key；每次调用已落 `llm_log` 审计表并绑定本体版本号
- MCP 只读端点演示期未启用鉴权（演示期拍板，协议层省略合规）；公网部署前需以 Servlet Filter 接入认证
- 语义问数按场景数据源清单过滤（`bemodel.semantic.scene-ds`，默认仿真试点三库；置空恢复不过滤）：新环境未登记对应数据源时，问数如实告知无映射，不回退旧演示库硬答

## License

[Apache License 2.0](LICENSE)
