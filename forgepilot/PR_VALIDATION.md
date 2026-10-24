# ForgePilot 工业管理集成验证

## 干净分支验证

基线：`78b4be93b03dbabca044ac06aad2b85557377873`。所有 Java 源码与测试源码均重新编译，未使用本机部署 JAR 的类作为构建输入。

- Maven 20 项通过：IndustrialBoundaryFilterTest（4）、RelationQueryServiceTest（4）、ReleaseClosureGoldenTest（4）、ConnectorFactoryServiceTest（8）。
- `mvn package -DskipTests` 成功生成可执行 JAR，测试已在前一步执行。
- 工业前端 `npm ci` 和 `npm run build:forgepilot` 成功。
- 本体图 Node 测试4项通过，迁移与目录 Python 测试5项通过。
- 当前本体目录的四份跨仓库副本 SHA-256 一致；模型生成与源字段、单位和引用完整性测试通过。

运行命令：

```sh
cd bemodel-server
mvn test -Dtest=IndustrialBoundaryFilterTest,RelationQueryServiceTest,ReleaseClosureGoldenTest,ConnectorFactoryServiceTest
mvn package -DskipTests
cd ../bemodel-web
npm ci
npm run build:forgepilot
node --test tests/*.test.mjs
cd ..
python3 -m unittest discover -s forgepilot/tests -v
```

## 本机部署接口验证

此前在保留的工业数据库中验证：11份初始连接器、413条映射契约（126条现有演示路径、287条生产字段待确认），重复初始化不覆盖；旧版本写入拒绝，保存使旧校验失效；匿名401，VIEWER/REVIEWER写入403；明文凭据与单位不一致拒绝；重启保留版本与校验结果；模板创建、重复编码拦截、字段编辑持久化及留痕通过。临时测试连接器已清理。

样本验证采用现有 ForgePilot 只读批次快照：当前样本通过、重复身份和布尔数量被拒绝，空身份及移出必填校验被拒绝。样本原文未入库，原业务快照修订号和内容摘要未变化。

本体管理验证：45节点/58关系的总图、概念邻域、Lot出向闭包10项与反向18项可查；工业边界拒绝未支持的接口；实例页等待当前概念目录后选取存在的概念，不再使用FEE_DETAIL。

## 范围

当前版本维护本体及只读接入契约草稿，不连接生产ERP/MES/财务，不自动同步，不将Java草稿发布到ForgePilot运行器，不发送链上交易。HTTP/MySQL/JSON协议在工厂中为契约选项；已有真实MySQL扫描仍使用原JDBC工作台。

本机运行库、密钥、数据库备份、编译产物与绝对路径测试日志均未纳入提交。

## 工业管理配置验证

- 工业运行边界始终生效，前端导航与接口范围一致。
- 默认配置仅使用工业库与工业迁移；普通`npm run build`只构建工业界面。
- 工业专用/关系/闭包/连接器/联系人脱敏测试共29项通过，前端普通构建成功。
- 新增默认配置、class path无旧业务类与种子、其他profile不能关闭工业边界、规则只接受CONTRACT的回归。
- 工业V1/V2生成结果保持逐字节一致，现有工业数据与连接器不清空。

- 不指定SPRING_PROFILES_ACTIVE，在临时端口默认启动，14项工业接口检查通过；与原18081读取的11份连接器配置逐项一致，413条字段契约保留。部署18081后再次通过14项接口检查。
- JAR与静态服务目录使用当前工业构建产物。工作区源文件与数据库备份保存在本机忽略目录，不纳入PR。


## 完整案例统一视觉（2026-10-24）

管理端与ForgePilot共享中文思源宋体、英文Times New Roman及纸面工程主题。字体按OFL-1.1独立托管；侧栏连接完整案例演示，关系图Canvas明确指定同一字体。生产构建与现有前端测试通过。模型、字段契约、JDBC与业务权限均沿用现有实现，没有修改数据库或发布模型。
