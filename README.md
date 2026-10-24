# ForgePilot 工业本体与接入管理

围绕 ForgePilot 工业供应链管理建设对象、关系、规则、指标和来源映射数字底座。默认启动和前端构建均进入工业管理界面。

## 现有能力

- 10个工业业务域、45类对象、287属性、58关系，支持对象编辑、关系图、概念邻域、多父继承、闭包/路径查询、OWL导入与版本审核。
- 11份连接器配置、413条字段契约：126条现有演示适配路径，287条生产源字段待确认；支持配置、版本、校验、样本检查和变更记录。
- 保留原JDBC工作台：数据源扫描、物理表列、人工/AI映射提议和生效/停用生命周期。
- 工业术语、指标定义、制度文档和检索；个人联系人字段脱敏与模型维护告警保留。
- 工业库与ForgePilot业务事实分开管理。定义、接入契约和发布状态不会自动产生实物库存或执行链上交易。

## 本地启动

需要JDK21+、Maven、Node.js、Python3及Docker。所有默认地址仅绑定本机。

```sh
npm ci --prefix bemodel-web
npm run build --prefix bemodel-web
mvn -f bemodel-server/pom.xml package -DskipTests
python3 forgepilot/dev.py init-db
python3 forgepilot/dev.py start
```

管理端：[http://127.0.0.1:18081](http://127.0.0.1:18081)。前端开发：`npm run dev --prefix bemodel-web`，默认18082并代理至18081。`build:forgepilot`、`dev:forgepilot`为兼容命令，均构建同一工业应用。

独立配置保存在 `application-forgepilot.yml`；不指定profile时也使用工业配置。数据库使用 `forgepilot_industrial` 与 `db/industrial` 迁移，账号/密钥由忽略的 `.runtime/` 和环境变量提供。

本机演示账号：admin/admin123、modeler/model123、reviewer/reviewer123、viewer/viewer123。生产部署需接入自己的身份体系与受控密钥。不要把本机演示服务直接公开。

## 文档与验证

- [工业目录与启动说明](forgepilot/README.md)
- [连接器工厂](forgepilot/CONNECTORS.md)
- [工业迁移与不可变基线](forgepilot/MIGRATION.md)
- [PR验证记录](forgepilot/PR_VALIDATION.md)

工业V1/V2为已应用的不可变元数据迁移，原始输入固定在 `forgepilot/baseline/`，生成不再依赖已删除的旧应用迁移。升级保留现有工业库、模型和连接器，不删除其他业务库。

模型与接入配置仍为草稿。真实生产ERP/MES/财务连接、自动同步、Java草稿发布到业务运行器和模型摘要上链需要后续实施与验收。

基于 [BeModel](https://github.com/yueyezhufeng/bemodel-java) 演进，保留 [Apache-2.0许可证](LICENSE) 与历史来源哈希。
