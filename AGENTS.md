# AGENTS.md

Shared instructions for AI coding assistants working in the RuoYi project.
Keep this file specific, factual, and cross-tool. Prefer nearby code and
existing patterns over assumptions. Update it when project behavior changes.

`pom.xml` is the canonical source of truth for build/tooling settings.

## What This Repo Is

RuoYi 4.8.3 — a Spring Boot–based admin management system (权限管理后台).
Multi-module Maven project, Java 17, Thymeleaf server-side rendered UI,
Shiro authentication, MyBatis persistence, Druid connection pool.

- 主入口：`ruoyi-admin`（可执行 jar，web 入口）
- 业务模块：`ruoyi-system`、`ruoyi-quartz`（定时任务）、`ruoyi-generator`（代码生成）
- 基础设施：`ruoyi-common`（工具与领域对象）、`ruoyi-framework`（Shiro/MyBatis/Web 配置）
- AI 模块：`ruoyi-ai`（基于 AgentCore DeepAgent，见下文专节）

## Tech Stack & Versions

| 组件 | 版本 | 备注 |
|------|------|------|
| Java | 17 | source/target=17 |
| Spring Boot | 4.0.6 | 注意：是 Boot 4.x，不是 3.x 或 2.x |
| Thymeleaf | 6.x（spring6 集成） | `#httpServletRequest` 等 SpEL 已失效，用 `@{...}` |
| Shiro | 2.2.0 (jakarta classifier) | 默认 `/**` 要求登录 |
| MyBatis | spring-boot-starter 4.0.1 | mapper xml 在 `classpath*:mapper/**/*Mapper.xml` |
| Druid | 1.2.28 + spring-boot-4-starter | 数据源监控页 `/druid` |
| MySQL | 8.0.46 | 数据库 `ry` |
| PageHelper | 4.1.0 | 分页插件 |
| Fastjson | 1.2.83 | 阿里 JSON |
| Springdoc | 3.0.3 | OpenAPI/Swagger UI |
| Java servlet API | jakarta（不是 javax） | 所有 import 用 `jakarta.servlet.*` |

## Local Environment

| 项 | 值 |
|----|------|
| 本地 Maven 仓库 | `/opt/programs/java/maven-repo`（自定义 settings.xml 在 `/home/luffy/.m2/settings.xml`，**不是** 默认 `~/.m2/repository`） |
| Maven 镜像 | 阿里云 + 华为云（见 settings.xml） |
| agent-core-java | `0.1.13` 已装在本地仓库（`com.openjiuwen:agent-core-java`） |
| 上传路径 | `/home/luffy/ruoyi/uploadPath` |
| 日志路径 | `/home/luffy/ruoyi/logs/` |
| AI 工作目录 | `/home/luffy/ruoyi/ai/workspace`（DeepAgent 多租户隔离） |
| AI 技能目录 | `/home/luffy/ruoyi/ai/skills/`（启动时从 classpath:skills/ 解压） |

## Build & Run

### 编译

```bash
# 全量编译并打包（标准做法）
cd /home/luffy/projects/yaodh/RuoYi
mvn clean package -DskipTests

# 只编译单个模块（快，用于验证改动）—— -am 自动带上依赖模块
mvn -pl ruoyi-ai -am compile -DskipTests
mvn -pl ruoyi-ai -am clean compile -DskipTests   # 强制重编

# 产物：ruoyi-admin/target/ruoyi-admin.jar （Spring Boot repackage 后约 227MB）
```

### 启动

```bash
# 标准启动（验证码开启）
java -jar ruoyi-admin/target/ruoyi-admin.jar

# 后台启动 + 日志重定向
nohup java -jar ruoyi-admin/target/ruoyi-admin.jar > /home/luffy/ruoyi/logs/ai-startup.log 2>&1 &

# 测试时临时关闭验证码（不污染 JAR，重启不带参数即恢复）
java -jar ruoyi-admin/target/ruoyi-admin.jar --shiro.user.captchaEnabled=false
```

### 停止

```bash
ps aux | grep ruoyi-admin.jar | grep -v grep
# 拿到 PID 后
kill <PID>
```

## Quick Verification

服务起来后，看到下面这行说明启动成功：

```
Started RuoYiApplication in X.XXX seconds
```

如果加载了 `ruoyi-ai` 模块，会先看到三行（`DeepAgentRegistry.@PostConstruct` 预热 `is_default=Y AND status=0` 的默认模型；无默认模型时此处仅打印 WARN，启动不中断）：

```
[ruoyi-ai] 内置技能已解压至: /home/luffy/ruoyi/ai/skills (共 N 个文件)
[ruoyi-ai] 初始化 DeepAgent: model=GLM-5.1, provider=OpenAI, apiBase=...
[ruoyi-ai] DeepAgent 初始化完成: agentId=ruoyi_ai_deep_agent_<modelId>, skillsDir=/home/luffy/ruoyi/ai/skills
```

最快的端到端验证：

```bash
# 1. 登录（验证码关闭时；密码见下文）
rm -f /tmp/cookies.txt
curl -s -c /tmp/cookies.txt -X POST http://localhost:8080/login \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin&password=<admin_password>&rememberMe=false"
# 期望: {"msg":"操作成功","code":0}

# 2. 取启用模型列表（用于拿 modelId）
curl -s -b /tmp/cookies.txt http://localhost:8080/ai/chat/models
# 期望: {"code":0,"data":[{"modelId":100,"modelName":"GLM-5.1",...}]}

# 3. 验证 AI 对话（modelId 必填，从上一步取）
curl -s -b /tmp/cookies.txt -X POST http://localhost:8080/ai/chat/send \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --max-time 150 \
  -d "query=查询深圳天气&modelId=100"
# 期望: {"code":0,"msg":"...深圳...°C...","conversationId":"...","modelId":100}

# 4. （可选）测试某模型连通性
curl -s -b /tmp/cookies.txt -X POST http://localhost:8080/ai/models/test/100 --max-time 120
# 期望: {"code":0,"msg":"连通成功，耗时 XXXms","reply":"..."}
```

**注意**：curl 测试**不能完全代替浏览器测试**——Thymeleaf 模板中途解析失败时 Spring 可能仍返回 HTTP 200 + 截断体。模板改动后务必用浏览器实测一次。详见下文"常见坑"。

## Access & Credentials

| 项 | 值 |
|----|------|
| 访问地址 | http://localhost:8080/ |
| 端口 | 8080（在 `application.yml` 中 `server.port`） |
| Context path | `/`（空） |
| 管理员账号 | `admin` |
| 管理员密码 | **`<admin_password>`**（注意：原始 SQL 默认密码与规格可能不同；密码哈希 = `Md5Utils.hash(loginName + password + salt)`） |
| 测试账号 | `ry` / `<admin_password>` |
| 验证码 | 默认开启（`captchaEnabled: true`，类型 `math` 算术题） |

## Database

| 项 | 值 |
|----|------|
| 类型 | MySQL 8.0.46 |
| 主机 | localhost |
| 端口 | 3306 |
| 库名 | `ry` |
| 用户 | `ry@localhost` |
| 密码 | `<db_password>` |
| 连接配置 | `ruoyi-admin/src/main/resources/application-druid.yml` |

```bash
# 命令行连接
mysql -u ry -p<db_password> -h localhost ry
```

数据库初始化 SQL 在 `sql/ry_20260319.sql`；菜单/权限的 DDL 也在这里。新增菜单的 SQL 范式见 `sql/ai_menu.sql`；`sql/ai_model.sql` 与 `sql/ai_model_menu.sql` 是 LLM 管理功能的表 DDL（含 seed）与菜单 SQL（含 `INSERT ... ON DUPLICATE KEY UPDATE` 兼容新旧库的写法）。

## Configuration Files

| 文件 | 位置 | 关键配置 |
|------|------|----------|
| 主配置 | `ruoyi-admin/src/main/resources/application.yml` | 端口、Thymeleaf、MyBatis、Shiro、XSS、CSRF、AI 模块配置（`ai.*`） |
| 数据源 | `ruoyi-admin/src/main/resources/application-druid.yml` | MySQL 连接、Druid 监控 |
| 日志 | `ruoyi-admin/src/main/resources/logback.xml` | 输出到 `/home/luffy/ruoyi/logs/` |
| MyBatis 全局 | `ruoyi-admin/src/main/resources/mybatis/mybatis-config.xml` | |
| Mapper XML | `classpath*:mapper/**/*Mapper.xml`（各模块 `src/main/resources/mapper/`） | |
| Shiro 过滤链 | `ruoyi-framework/src/main/java/com/ruoyi/framework/config/ShiroConfig.java` | 静态资源 `anon`，其余 `/**` 走 `user,kickout,...` |

## Code Conventions

### 包结构与组件扫描

- 主类 `com.ruoyi.RuoYiApplication` 用 `@SpringBootApplication`，默认扫描 `com.ruoyi.*` 整个包。
- 新增模块的 Java 类放在 `com.ruoyi.<module>.*` 下，自动被扫描，**无需**手动配置 `@ComponentScan`。
- Controller 继承 `com.ruoyi.common.core.controller.BaseController`，返回 `AjaxResult`（继承 `HashMap`，code=0 成功、500 失败）。
- 权限标识格式：`模块:功能:动作`，如 `tool:build:view`、`ai:chat:send`。Controller 用 `@RequiresPermissions("xxx:xxx:xxx")` 注解。

### 新增业务模块的标准步骤

1. 在根 `pom.xml` 的 `<modules>` 与 `<dependencyManagement>` 同时登记新模块。
2. 在 `ruoyi-admin/pom.xml` 加上新模块的 `<dependency>`（admin 是 web 入口）。
3. 新模块 `pom.xml` 继承 `com.ruoyi:ruoyi`，仅依赖 `ruoyi-common` + 必要第三方库。
4. Java 类放在 `com.ruoyi.<module>.*` 包下。
5. Thymeleaf 模板放 `src/main/resources/templates/<module>/xxx.html`（多模块时 Thymeleaf 通过 classpath 自动找到，无需额外配置）。
6. 静态资源放 `ruoyi-admin/src/main/resources/static/`。
7. 在 `application.yml` 的 `xss.urlPatterns` 加上新模块的 URL 前缀。
8. 菜单 SQL 写到 `sql/<module>_menu.sql`，由用户手工 `mysql < sql/xxx.sql` 导入；admin 角色权限是 `*`，菜单导入后立即可见。

### 现成的 Controller 写法样板

| 模板 | 路径 |
|------|------|
| 简单 REST Controller（`@RestController` + `R<T>`） | `ruoyi-admin/.../web/controller/tool/TestController.java` |
| 简单页面 Controller（`@Controller` + Thymeleaf） | `ruoyi-admin/.../web/controller/tool/BuildController.java` |
| AI 模块 Controller（带 Shiro 权限 + AjaxResult） | `ruoyi-ai/.../ai/controller/AiChatController.java` |

## AI Module (ruoyi-ai)

`ruoyi-ai` 把 `com.openjiuwen.harness.deep_agent.DeepAgent` 嵌入 Spring 容器，对外暴露 `/ai/chat` 页面与 `/ai/chat/send` 接口。**不**启动独立 HTTP 服务（不像示例 `DeepAgentA2AServer` 那样用 `com.sun.net.httpserver.HttpServer`）。

### 关键文件

| 文件 | 作用 |
|------|------|
| `ruoyi-ai/pom.xml` | 依赖 `agent-core-java:0.1.13` + `ruoyi-common` |
| `config/AiProperties.java` | `@ConfigurationProperties(prefix="ai")`，对应 `application.yml` 中 `ai.*`（注：原 `ai.llm.*` 已迁移到数据库 `sys_ai_model` 表） |
| `config/AiConfiguration.java` | 不再 `@Bean` 单例 DeepAgent；提供公共 `buildDeepAgent(SysAiModel, agentId)` 方法供 Registry 与 Tester 复用；`@PostConstruct` 把 `classpath:skills/` 解压到 `ai.skills-dir` |
| `service/DeepAgentRegistry.java` | **核心**：按 `modelId` 缓存 `ConcurrentHashMap<Long, DeepAgent>`，懒构建+复用；监听 `AiModelChangedEvent` 自动 evict（CRUD 后失效）；`@PostConstruct` 预热默认模型 |
| `service/AiModelConnectionTester.java` | 模型连通性测试：临时构建 DeepAgent 发"你好" ping；空回复也判失败 |
| `service/AiChatService.java` | 注入 `DeepAgentRegistry`（不再是单例 DeepAgent）；`chat(user, convId, query, modelId)`；以 `ShiroUtils.getLoginName()` 作 tenantId 实现多用户隔离 |
| `controller/AiChatController.java` | `/ai/chat`（页面）+ `/ai/chat/send`（接口，含 `modelId`）+ `/ai/chat/models`（启用模型下拉数据） |
| `domain/SysAiModel.java` + `mapper/SysAiModelMapper.xml` | LLM 模型配置实体与 MyBatis 映射 |
| `service/ISysAiModelService.java` + `impl/SysAiModelServiceImpl.java` | 模型 CRUD + 唯一性检查 + 默认模型互斥；CRUD 后通过 `ApplicationEventPublisher` 发 `AiModelChangedEvent` |
| `controller/SysAiModelController.java` | `/ai/models`（CRUD 页面）+ `/ai/models/test/{id}` + `/ai/models/testConnect`（连通测试） |
| `event/AiModelChangedEvent.java` | 模型变更事件（解耦 Service 与 Registry，避免循环依赖） |
| `resources/skills/weather/SKILL.md` | 天气技能（教 LLM 用 `curl wttr.in`） |
| `resources/templates/ai/chat/chat.html` | 聊天界面（Thymeleaf，jQuery + AJAX），含模型下拉 |
| `resources/templates/ai/model/{model,add,edit}.html` | Models CRUD 页面（参考 `system/config` 三件套） |
| `sql/ai_model.sql` | `sys_ai_model` 表 DDL + seed（值取自原 yml `ai.llm.*`） |
| `sql/ai_model_menu.sql` | 菜单 SQL：新增一级 `AI辅助(1180)`、改 `117` 为 `AI问答`、新增 `Models(1182)` + 5 个按钮权限 |

### DeepAgent 创建要点（与示例 `DeepAgentA2AServer.createDeepAgent()` 一致）

1. `AgentCard` + `DeepAgentConfig.builder().enableTaskLoop(true).maxIterations(30).language("cn").model(...).backend(...).restrictToWorkDir(false).sysOperation(...).enableTenantIsolation(true).tenantDataRoot(...).rails(List.of(new SkillUseRail(skillsDir), new SysOperationRail())).build()`
2. `Workspace.builder().rootPath(workDir).language("cn").build()`
3. `HarnessFactory.createDeepAgent(card, config, workspace)`
4. **手动注入 SysOp 工具**：`Runner.resourceMgr().getSysOpToolCards(sysOpId, null, null)` 取出 ToolCard 列表，逐个 `deepAgent.getAgent().getAbilityManager().add(toolCard)`。`SysOperationRail.init()` 是空实现，**必须**手动注入。
5. 调用范式：`deepAgent.invoke(Map.of("query", q, "conversation_id", convId), session)`，返回 `Map`，取 `output` 字段。
6. **AGENT_ID 必须按模型区分**：多模型场景下 `agentId` 形如 `ruoyi_ai_deep_agent_<modelId>`，否则 `Runner.resourceMgr()` 中 `SysOperation` 注册冲突。临时测试实例用 `ruoyi_ai_test_<nanoTime>`。
7. **TenantContext 必填**：DeepAgent 启用了 `enableTenantIsolation(true)`，调用 `invoke` 前必须传 `TenantContext`（否则报 `Tenant isolation is enabled but no tenantId was provided`）。

### LLM 配置（已迁移到数据库 `sys_ai_model` 表）

> ⚠ 原 `application.yml` 中 `ai.llm.*` 段已**删除**。模型配置以数据库 `sys_ai_model` 表为准，由"AI辅助→Models"菜单（`/ai/models`）管理。

| DB 字段 | 当前默认值（seed） | AgentCore Map key |
|---------|---------------------|-------------------|
| `model_name` | `GLM-5.1` | `model` |
| `model_provider` | `OpenAI` | `client_provider` |
| `api_base` | `http://example.com/v1` | `api_base` |
| `api_key` | `sk-your-api-key` | `api_key` |
| `ssl_verify` | `0`（否） | `verify_ssl` |
| `model_type` / `model_version` | `chat` / `5.1` | —（仅展示与过滤） |
| `status` | `0`（启用） | —（问答页下拉仅显示启用的） |
| `is_default` | `Y`（仅一条） | —（启动期预热 + 问答页默认选中） |

- 增删改查：菜单 `AI辅助 → Models`（权限 `ai:model:*`），SQL 在 `sql/ai_model.sql`。
- 连通性测试：列表行内"测试"按钮或表单内"测试连通"按钮（权限 `ai:model:test`），发送"你好"提示词，空回复也判失败。
- 缓存失效：CRUD 后 Service 发 `AiModelChangedEvent`，`DeepAgentRegistry` 监听并 evict 对应 modelId 的 DeepAgent 实例（关闭 + 移除）。

## Common Issues & Gotchas

| 现象 | 根因 | 解决 |
|------|------|------|
| curl 返回 200 但浏览器白屏/报错 | Thymeleaf 模板中途解析失败，Spring 已部分提交响应无法回退成 500 | 看响应体行数/末尾是否 `</html>`；用浏览器实测 |
| Thymeleaf 报 `EL1011E ... on null context object` | Spring Boot 3+ 不再注入 `#httpServletRequest` 到 SpEL | 把 `${#httpServletRequest.xxx()}` 换成 Thymeleaf 原生 `@{...}` URL 表达式 |
| 登录报"用户不存在/密码错误" | 实际密码可能与规格不同 | 确认实际密码；密码哈希算法 `Md5Utils.hash(loginName + password + salt)` |
| 自动化测试被验证码拦下 | `captchaEnabled: true` | 启动加 `--shiro.user.captchaEnabled=false`（不污染 JAR） |
| 改了 `application.yml` 不生效 | yml 打进 JAR 后改源文件不生效 | `mvn -pl ruoyi-admin -am clean package`，或启动用 `--key=value` 覆盖 |
| 新菜单在浏览器看不到 | 菜单 SQL 未导入 MySQL | `mysql -u ry -p<db_password> ry < sql/xxx_menu.sql` |
| 新增 URL 被 XSS 过滤器拦 | `application.yml` 的 `xss.urlPatterns` 没包含新前缀 | 在 `urlPatterns` 加 `/新模块/*` |
| `mvn -pl xxx` 报 "Could not find artifact" | 子模块未在父 pom 的 `<modules>` 登记 | 检查根 `pom.xml` 的 `<modules>` 与 `<dependencyManagement>` |
| DeepAgent 启动报技能目录找不到 | `SkillManager` 用 `Files.readString(Path)` 读文件系统，不认 JAR 内资源 | 启动时把 `classpath:skills/` 解压到 `ai.skills-dir`（参考 `AiConfiguration.extractBundledSkills()`） |
| 多用户串话 / 互相看到对方会话 | 没传 `TenantContext` 或所有用户用了同一 tenantId | 用 `ShiroUtils.getLoginName()` 作 tenantId |
| 本地仓库找不到 `com.openjiuwen` | 自定义 settings.xml 把仓库指到 `/opt/programs/java/maven-repo`，不是默认 `~/.m2/repository` | `cat /home/luffy/.m2/settings.xml` 确认 `<localRepository>` |

## Testing Tips

1. **三层验证**（任何 UI 改动都建议全做）：
   - 编译期：`mvn -pl <module> -am compile` BUILD SUCCESS
   - 接口期：curl 拿到预期 JSON（看 HTTP code **和** 响应体完整性）
   - 浏览器期：实测加载页面 + 触发一次交互（curl 测不出来的渲染/JS bug 在这一步暴露）
2. **看响应体完整性**，不能只看 HTTP code：
   ```bash
   curl -s -o /tmp/page.html -w "HTTP: %{http_code}\n" http://localhost:8080/ai/chat
   wc -l /tmp/page.html                       # 行数
   tail -3 /tmp/page.html                     # 末尾是否 </html>
   grep -E "动态变量名" /tmp/page.html        # 关键动态片段是否被求值
   ```
3. **密码反推**：忘了管理员密码时，从 DB 取 `password`/`salt`，shell 循环跑几个候选 + md5：
   ```bash
   for pw in candidate1 candidate2 candidate3; do
     hash=$(echo -n "admin${pw}<salt>" | md5sum | awk '{print $1}')
     [ "$hash" = "<stored_hash>" ] && echo "$pw MATCH"
   done
   ```
4. **看日志定位 LLM/Agent 问题**：
   ```bash
   grep -E "ruoyi-ai|executeCmd|skill_tool|ERROR|Started RuoYi" /home/luffy/ruoyi/logs/ai-startup.log
   ```

## More Detail

- 集成经验总结（含完整流程回顾、踩坑反思）：`doc/AI辅助模块集成经验总结.md`
- **LLM 管理功能设计文档**：`doc/LLM管理功能-设计文档.md`（架构、DB 设计、菜单 SQL、核心组件、风险）
- **LLM 管理功能实现计划**：`doc/LLM管理功能-实现计划.md`（P1~P6 阶段任务与验证清单）
- 本次会话提问记录：`doc/会话提问记录.md`
- RuoYi 环境使用手册：`doc/若依环境使用手册.docx`
- AgentCore 框架文档（外部）：`/home/luffy/projects/gitcode/agent-core-java/AGENTS.md`、`README.zh.md`
- DeepAgent 示例（外部）：`/home/luffy/projects/gitcode/agent-core-java-examples/deep-agent/`
