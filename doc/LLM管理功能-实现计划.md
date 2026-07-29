# LLM 管理功能 实现计划

> 配套文档：`doc/LLM管理功能-设计文档.md`
> 目标：按 6 个阶段顺序实现，每阶段独立可验证；遵循 AGENTS.md 的"三层验证"原则

---

## 阶段总览

| 阶段 | 内容 | 产物 | 验证 |
|------|------|------|------|
| P1 | 数据库表 + 菜单 SQL | `sql/ai_model.sql`、`sql/ai_model_menu.sql` | `mysql <` 导入后菜单可见 |
| P2 | Models 后端 CRUD + 模板 | domain/mapper/service/controller + 3 个 html | 浏览器走通增删改查 |
| P3 | DeepAgentRegistry + AiConfiguration 重构 | `DeepAgentRegistry`、`AiConfiguration` 改造、`AiProperties`/yml 清理 | 启动日志不变，无可用模型不报错 |
| P4 | 连通性测试功能 | `AiModelConnectionTester` + 表单/列表测试按钮 | 故意填错 api_key 时返回失败 |
| P5 | AI 问答页面模型选择 | `AiChatService`/`AiChatController` 改造 + `chat.html` 加下拉 | 切换模型后能正常对话 |
| P6 | 收尾：yml 清理、AGENTS.md 同步 | `application.yml`、`AGENTS.md` 更新 | 全量 `mvn clean package -DskipTests` 通过 |

---

## P1 — 数据库与菜单

### 任务

- [ ] 编写 `sql/ai_model.sql`
  - `drop table if exists sys_ai_model;` + `create table sys_ai_model (...)`（见设计文档 §4.1）
  - Seed：1 条 `GLM-5.1` 默认模型（`is_default='Y', status='0'`），值取自 AGENTS.md §"LLM 配置"
- [ ] 编写 `sql/ai_model_menu.sql`
  - 新增一级目录 `AI辅助` (menu_id=1180)
  - `INSERT ... ON DUPLICATE KEY UPDATE` 改写 menu_id=117 → `AI问答`，parent_id=1180
  - 新增 `Models` (1182) 及 5 个按钮权限 (1183~1187)（见设计文档 §5.2）
- [ ] 导入数据库
  ```bash
  mysql -u ry -p<db_password> ry < sql/ai_model.sql
  mysql -u ry -p<db_password> ry < sql/ai_model_menu.sql
  ```

### 验证

```sql
-- 检查表与 seed
select model_id, model_name, is_default, status from sys_ai_model;
-- 检查菜单树：AI辅助(1180) -> AI问答(117), Models(1182)
select menu_id, menu_name, parent_id, menu_type, perms from sys_menu
 where menu_id in (117, 1180, 1182, 1183, 1184, 1185, 1186, 1187) order by menu_id;
```

浏览器：登录后左侧菜单出现"AI辅助"一级，展开有"AI问答"和"Models"两项。

---

## P2 — Models 后端 CRUD + 前端模板

### 任务（参照 `SysConfig` 全套模板，逐文件照搬改字段）

- [ ] `ruoyi-ai/src/main/java/com/ruoyi/ai/domain/SysAiModel.java`
  - 继承 `BaseEntity`；`@Excel` 注解；字段对应 `sys_ai_model` 表
  - 校验：`@NotBlank modelName/modelProvider/apiBase/apiKey`；`@Size` 限制长度
- [ ] `ruoyi-ai/src/main/java/com/ruoyi/ai/mapper/SysAiModelMapper.java`
  - 接口方法：`selectModelById`、`selectModelList`、`selectDefaultModel`、`checkModelNameUnique`、`insertModel`、`updateModel`、`deleteModelById`、`deleteModelByIds`、`updateDefaultModel`（保证唯一默认）
- [ ] `ruoyi-ai/src/main/resources/mapper/ai/SysAiModelMapper.xml`
  - 参考 `mapper/system/SysConfigMapper.xml` 写法：`resultMap` + `selectXxxVo` + `<where>` 列表过滤 + `<insert>/<update>/<delete>`
- [ ] `ruoyi-ai/src/main/java/com/ruoyi/ai/service/ISysAiModelService.java`
- [ ] `ruoyi-ai/src/main/java/com/ruoyi/ai/service/impl/SysAiModelServiceImpl.java`
  - CRUD + 唯一性检查 + 默认模型互斥逻辑（设新默认时把其他 `is_default` 改为 N）
  - **P3 接入后再加 `evict` 联动**（P2 阶段先注释占位）
- [ ] `ruoyi-ai/src/main/java/com/ruoyi/ai/controller/SysAiModelController.java`
  - `@Controller @RequestMapping("/ai/models")` extends `BaseController`
  - 方法：`model()`(页面)、`list()`、`add()`(页面)、`addSave()`、`edit()`(页面)、`editSave()`、`remove()`、`checkModelNameUnique()`
  - 权限：`ai:model:view/list/add/edit/remove`
  - `@Log(title="LLM模型管理", businessType=...)` 记录操作日志
- [ ] `ruoyi-ai/src/main/resources/templates/ai/model/model.html`
  - 复制 `templates/system/config/config.html`，改字段：modelName/modelType/modelVersion/modelProvider/status/isDefault
  - dict 字段：`status` 用 `sys_yes_no` 反向展示（0=是=启用，1=否=停用），或新增 dict `sys_ai_model_status`；为简化先用前端 formatter
- [ ] `ruoyi-ai/src/main/resources/templates/ai/model/add.html`
  - 复制 `templates/system/config/add.html`，加全部字段；`apiKey` 用 `type="password"`；`sslVerify`/`isDefault` 用 radio
- [ ] `ruoyi-ai/src/main/resources/templates/ai/model/edit.html`
  - 同 add，回填 `th:value`

### 验证

```bash
mvn -pl ruoyi-ai -am compile -DskipTests    # 必须 BUILD SUCCESS
# 启动后浏览器实测：
# - 进入 /ai/models 看到列表（应有 1 条 seed 记录）
# - 新增 / 编辑 / 删除 全流程
# - 唯一性校验：重名新增报错
```

---

## P3 — DeepAgentRegistry 与 AiConfiguration 重构

### 任务

- [ ] 修改 `ruoyi-ai/src/main/java/com/ruoyi/ai/config/AiConfiguration.java`
  - 删除 `@Bean public DeepAgent deepAgent()`
  - 抽取公共方法：`public DeepAgent buildDeepAgent(SysAiModel model, String agentId)`
    - 复用现有 `createSysOperation(agentId, workDir)`、`buildModelConfig(...)`、`buildBackendConfig(...)`、`injectSysOpTools(...)`
    - `buildModelConfig`/`buildBackendConfig` 改签名接收 `SysAiModel`
  - 保留 `extractBundledSkills()` 与 `@PostConstruct init()`
  - 保留原有的启动日志风格（在 `buildDeepAgent` 内打印 `[ruoyi-ai] 初始化 DeepAgent: ...`）
- [ ] 修改 `ruoyi-ai/src/main/java/com/ruoyi/ai/config/AiProperties.java`
  - 删除 `Llm` 内部类及 `llm` 字段
  - 保留 `agent` / `tenantDataRoot` / `skillsDir`
- [ ] 新增 `ruoyi-ai/src/main/java/com/ruoyi/ai/service/DeepAgentRegistry.java`
  - 字段：`ConcurrentHashMap<Long, DeepAgent> cache`
  - 方法：`preloadDefault()` (`@PostConstruct`)、`getOrCreate(modelId)`、`evict(modelId)`、`evictAll()`
  - `@PreConstruct` 兜底：`@PreDestroy public void destroy() { evictAll(); }`
- [ ] 修改 `SysAiModelServiceImpl`：在 `updateModel`/`deleteModelById`/`deleteModelByIds`/`updateDefaultModel` 后调用 `registry.evict(affectedIds)`
  - 注意循环依赖：`DeepAgentRegistry` 依赖 `ISysAiModelService`，`SysAiModelServiceImpl` 依赖 `DeepAgentRegistry` → 用 `@Lazy` 注入或用 `ApplicationContext` 解耦
- [ ] 修改 `ruoyi-admin/src/main/resources/application.yml`
  - 删除 `ai.llm.*` 段（保留 `ai.tenant-data-root`/`ai.skills-dir`/`ai.agent.*`）

### 验证

```bash
mvn -pl ruoyi-ai -am clean compile -DskipTests
java -jar ruoyi-admin/target/ruoyi-admin.jar --shiro.user.captchaEnabled=false
# 看日志：
#   [ruoyi-ai] 内置技能已解压至: ...
#   [ruoyi-ai] 初始化 DeepAgent: model=GLM-5.1, provider=OpenAI, apiBase=...
#   [ruoyi-ai] DeepAgent 初始化完成: ...
# Started RuoYiApplication in X.XXX seconds
```

降级测试：临时把 DB 中 `sys_ai_model.status` 全设为 1，重启，应只打印 WARN 不抛异常。

---

## P4 — 连通性测试

### 任务

- [ ] 新增 `ruoyi-ai/src/main/java/com/ruoyi/ai/service/AiModelConnectionTester.java`
  - 常量：`PING_PROMPT = "你好"`、`TIMEOUT_MS = 30_000`
  - 方法：`testById(Long modelId)`、`testByModel(SysAiModel model)`
  - 临时实例 `agentId = "ruoyi_ai_test_" + System.nanoTime()`；try-finally `close()`
- [ ] 扩展 `SysAiModelController`：
  - `POST /ai/models/test/{id}` (`ai:model:test`) → `tester.testById(id)`
  - `POST /ai/models/testConnect` (`ai:model:test`) → `tester.testByModel(form)`，便于保存前测试
- [ ] 修改 `templates/ai/model/model.html`：操作列加"测试连通"按钮，调用 `testConnectById(id)`
- [ ] 修改 `templates/ai/model/add.html` 和 `edit.html`：表单底部加"测试连通"按钮，先 `$('#form-...').serialize()` 再 POST `testConnect`

### 验证

```bash
# 测试已保存的 seed 模型
curl -s -b /tmp/cookies.txt -X POST http://localhost:8080/ai/models/test/1
# 期望: {"code":0,"msg":"连通成功，耗时 XXXms","reply":"你好！有什么..."}

# 故意破坏 api_key 后保存，再测，应返回 {"code":500,"msg":"连通失败：..."}
```

浏览器：列表行内"测试连通"按钮弹窗显示结果；新增表单底部按钮在保存前可测。

---

## P5 — AI 问答页面模型选择

### 任务

- [ ] 修改 `ruoyi-ai/src/main/java/com/ruoyi/ai/service/AiChatService.java`
  - 注入 `DeepAgentRegistry` 替代 `DeepAgent`
  - `chat(String username, String conversationId, String query, Long modelId)`：
    - 校验 `modelId` 非空
    - `DeepAgent deepAgent = registry.getOrCreate(modelId)`
    - 其余 convId 构造、TenantContext、invoke、sessionState 缓存逻辑不变
- [ ] 修改 `ruoyi-ai/src/main/java/com/ruoyi/ai/controller/AiChatController.java`
  - `/send` 增加 `@RequestParam Long modelId`，透传给 service
  - 新增 `@GetMapping("/models") @ResponseBody` 返回启用模型（精简字段）
- [ ] 修改 `ruoyi-ai/src/main/resources/templates/ai/chat/chat.html`
  - ibox-tools 加 `<select id="modelSelect">`
  - `$(function(){ loadModels(); ... })`：拉取 `/ai/chat/models`，回填下拉，localStorage 记忆
  - `$('#modelSelect').on('change', ...)`：清空 `conversationId` + 清屏 + 提示
  - `sendMessage()` 的 `data` 加 `modelId: $('#modelSelect').val()`

### 验证

```bash
# 取启用模型列表
curl -s -b /tmp/cookies.txt http://localhost:8080/ai/chat/models
# 期望: {"code":0,"data":[{"modelId":1,"modelName":"GLM-5.1","modelVersion":"5.1"}, ...]}

# 选模型发问答
curl -s -b /tmp/cookies.txt -X POST http://localhost:8080/ai/chat/send \
  --max-time 90 \
  -d "query=查询深圳天气&modelId=1"
# 期望: {"code":0,"msg":"...深圳...°C...","conversationId":"...","modelId":1}
```

浏览器：下拉切换不同模型，各发一句问答，回复正常；切换时聊天记录清空、提示"已切换模型"。

---

## P6 — 收尾

### 任务

- [ ] 全量编译打包：`mvn clean package -DskipTests`
- [ ] 更新根 `AGENTS.md`（**仅在用户确认后**）
  - "AI Module" 章节加一句：`ai.llm.*` 配置已迁移到 `sys_ai_model` 表，由 `/ai/models` 管理
  - "LLM 配置" 表标注"已迁移到 DB，见 sys_ai_model 表"
  - "Quick Verification" 部分加 `curl /ai/chat/models` 步骤
- [ ] 编写完成情况说明（可选）：`doc/LLM管理功能-完成情况.md`

### 验证

```bash
mvn clean package -DskipTests   # 全量 BUILD SUCCESS
java -jar ruoyi-admin/target/ruoyi-admin.jar --shiro.user.captchaEnabled=false
# 端到端走查 P1~P5 全部验证点
```

---

## 跨阶段注意点

1. **循环依赖**（P3）：`SysAiModelServiceImpl` ↔ `DeepAgentRegistry`。推荐方案：
   - `SysAiModelServiceImpl` 用 `@Lazy private DeepAgentRegistry registry;` 注入
   - 或在 `SysAiModelServiceImpl` 中改用 `SpringUtils.getBean(DeepAgentRegistry.class).evict(id)` 解耦（参考 RuoYi 其他模块用法）
2. **MyBatis Mapper 扫描路径**：`classpath*:mapper/**/*Mapper.xml`（见 AGENTS.md），新 XML 放 `ruoyi-ai/src/main/resources/mapper/ai/` 即可被扫描。
3. **包扫描**：`com.ruoyi.ai.*` 自动被 `@SpringBootApplication` 扫描，无需 `@ComponentScan`。
4. **Thymeleaf 模板路径**：`templates/ai/model/*.html` 自动被 classpath 加载。
5. **每次模板改动后**：浏览器实测一次（curl 测不出 Thymeleaf 渲染 bug，见 AGENTS.md "常见坑"）。
6. **不提交**：除非用户明确要求，不要 `git add/commit`。

---

## 验收 Checklist

- [ ] 浏览器左侧菜单：一级"AI辅助" → 二级"AI问答"、"Models"
- [ ] Models 页面：列表、新增、编辑、删除、唯一性校验、默认模型互斥
- [ ] Models 页面"测试连通"按钮：成功显示耗时与样例回复；失败显示原因
- [ ] `application.yml` 不再含 `ai.llm.*`，启动正常
- [ ] 启动日志与 AGENTS.md 一致（预热默认模型）
- [ ] AI 问答页面：下拉显示启用模型；切换清空会话；选不同模型问答正常
- [ ] `mvn clean package -DskipTests` 通过
