# LLM 管理功能 设计文档

> 模块：RuoYi AI 辅助 — LLM 模型管理与问答模型选择
> 版本：v1.0
> 日期：2026-07-28
> 关联需求：菜单调整、LLM 配置数据库化（增删改查 + 连通性测试）、Models 管理菜单、AI 问答页面模型选择

---

## 1. 背景与目标

### 1.1 现状

- AI 模块 `ruoyi-ai` 通过 `AiConfiguration.deepAgent()` 在启动期构建**单例** `DeepAgent`，模型配置来自 `application.yml` 的 `ai.llm.*`。
- `AiChatService` 注入该单例，对外提供 `/ai/chat` 页面与 `/ai/chat/send` 接口。
- 菜单：`sql/ai_menu.sql` 中 `menu_id=117` 的"AI辅助"挂在"系统工具(3)"下。
- `DeepAgent` 的模型配置（`model`、`backend` Map）在 `DeepAgentConfig.builder()` 时固化，**运行期不可热切换**（`agent-core-java` 0.1.13 的 `DeepAgent` 无 `switchModel` API，见 `agent-core-java/src/main/java/com/openjiuwen/harness/deep_agent/DeepAgent.java`）。

### 1.2 目标

| # | 需求 | 验收 |
|---|------|------|
| R1 | 新增一级菜单"AI辅助"，原"系统工具->AI辅助"调整为"AI辅助->AI问答" | 菜单树展示新结构，旧入口失效 |
| R2 | `ai.llm.*` 配置从 yml 迁移到数据库，支持增删改查 + 连通性测试 | Models 页面可 CRUD；测试按钮返回成功/失败 |
| R3 | 新增菜单"AI辅助->Models"，管理模型名称/类型/版本/状态等 | 列表/新增/编辑/删除/测试全部可用 |
| R4 | "AI辅助->AI问答"可下拉选择模型进行问答 | 切换模型后能正常对话；切模型自动开新会话 |

### 1.3 非目标

- 不实现模型调用的流式输出（保持现有非流式 `invoke`）。
- 不实现模型计费/配额/限流。
- 不重构 DeepAgent 多租户隔离机制（仍用 `ShiroUtils.getLoginName()` 作 tenantId）。
- 不修改 `ruoyi-ai` 的技能目录与 SysOperation 注入逻辑。

---

## 2. 关键设计决策（已与需求方确认）

| 决策点 | 选择 | 理由 |
|--------|------|------|
| yml 配置迁移 | 迁移到 DB 作为 seed 数据 | DB 优先；yml `ai.llm.*` 段移除，实际配置以 DB 为准 |
| 模型切换与会话上下文 | 切模型即开新会话 | `DeepAgent` 模型配置 baked-in，不同模型对应不同 `DeepAgent` 实例；session 状态与实例绑定，跨实例复用会话不可行 |
| 连通性测试 | 发送轻量 ping 提示词 | 真正验证 `api_base + api_key + model_name` 三者匹配；超时 30s；提示词"你好" |
| Models `status` 语义 | `0=启用 / 1=停用` | 启用模型才在问答页面下拉显示；测试只返回结果，不自动改 status |

---

## 3. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        前端 (Thymeleaf)                      │
│  ┌──────────────────┐         ┌──────────────────────────┐  │
│  │ /ai/models CRUD  │         │ /ai/chat  模型下拉 + 对话 │  │
│  └────────┬─────────┘         └────────┬─────────────────┘  │
└───────────┼─────────────────────────────┼──────────────────┘
            │                              │
┌───────────▼──────────────────────────────▼──────────────────┐
│                  Controller 层 (ruoyi-ai)                     │
│  SysAiModelController        AiChatController                 │
│   /ai/models/*               /ai/chat/send (含 modelId)      │
│                               /ai/chat/models (下拉数据)      │
└───────┬──────────────────────────────┬──────────────────────┘
        │                              │
┌───────▼──────────────────┐  ┌────────▼──────────────────────┐
│  ISysAiModelService       │  │  AiChatService                │
│  CRUD + 唯一性检查        │  │  chat(user, convId, query,    │
│  + 测试调用               │  │       modelId)                │
└───────┬──────────────────┘  └────────┬──────────────────────┘
        │                            │
        │  evict(modelId)            │  getOrCreate(modelId)
        ▼                            ▼
┌──────────────────────────────────────────────────────────────┐
│              DeepAgentRegistry  (新增, 核心)                 │
│   - ConcurrentHashMap<Long, DeepAgent> cache                 │
│   - getOrCreate(modelId): 懒构建 + 复用                      │
│   - evict(modelId): close + 移除 (CRUD 后调用)               │
│   - evictAll(): 全清 (刷新缓存按钮)                          │
│   - preloadDefault(): 启动期预热默认模型                      │
└───────┬──────────────────────────────┬──────────────────────┘
        │                              │
┌───────▼──────────────────┐  ┌────────▼──────────────────────┐
│ SysAiModelMapper         │  │ AiModelConnectionTester       │
│ (MyBatis XML)            │  │ test(model): 临时构建 DeepAgent│
│                          │  │ + 发"你好" + close             │
└──────────────────────────┘  └───────────────────────────────┘
```

### 3.1 关键约束与影响

1. **不再有全局单例 `DeepAgent` Bean**：`AiConfiguration.deepAgent()` Bean 移除；`AiChatService` 改注入 `DeepAgentRegistry`。
2. **每个模型一个 `DeepAgent` 实例**：`AGENT_ID` 必须按模型区分（`ruoyi_ai_deep_agent_<modelId>`），否则多个实例共享同一 `SysOperation` 注册会冲突（见 `AiConfiguration.createSysOperation()` 当前实现）。
3. **缓存生命周期**：CRUD 后必须 evict 对应模型的缓存实例，否则 DB 改了但运行期仍用旧配置。
4. **启动期行为**：保留 AGENTS.md 中描述的启动日志（`[ruoyi-ai] 初始化 DeepAgent: ...`），通过预热默认模型实现。
5. **降级**：DB 无可用模型时，启动不报错；问答页面提示"无可用模型，请到 Models 菜单配置"。

---

## 4. 数据库设计

### 4.1 新增表 `sys_ai_model`

```sql
-- ----------------------------------------------------------
-- LLM 模型配置表 sys_ai_model
-- 替代 application.yml 中 ai.llm.* 的静态配置
-- ----------------------------------------------------------
drop table if exists sys_ai_model;
create table sys_ai_model (
  model_id         bigint(20)      not null auto_increment    comment '模型主键',
  model_name       varchar(100)    not null                   comment '模型名称（传给 LLM 的 model 标识，如 GLM-5.1）',
  model_type       varchar(50)     default 'chat'             comment '模型类型（chat 对话 / reasoning 推理 / embedding 嵌入）',
  model_version    varchar(50)     default ''                 comment '模型版本（展示用，如 5.1）',
  model_provider   varchar(50)     not null                   comment '后端提供方（AgentCore client_provider，如 OpenAI / DashScope）',
  api_base         varchar(255)    not null                   comment 'API 基地址（如 https://api.openai.com/v1）',
  api_key          varchar(255)    not null                   comment 'API Key',
  ssl_verify       char(1)         default '1'                comment '是否校验 SSL（0否 1是）',
  status           char(1)         default '0'                comment '状态（0启用 1停用）',
  is_default       char(1)         default 'N'                comment '是否默认模型（Y是 N否，仅可有一条为 Y）',
  sort_order       int(4)          default 0                  comment '显示顺序',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default ''                 comment '备注',
  primary key (model_id)
) engine=innodb auto_increment=100 comment = 'LLM 模型配置表';
```

#### 字段映射关系（DB ↔ AgentCore）

| DB 字段 | `AiProperties.Llm` 旧字段 | AgentCore `backend`/`model` Map key | 说明 |
|---------|----------------------------|--------------------------------------|------|
| `model_name` | `modelName` | `model` (in modelConfig) | 传给 LLM 的模型标识 |
| `model_provider` | `modelProvider` | `client_provider` (in backendConfig) | 后端 client 类型 |
| `api_base` | `apiBase` | `api_base` (in backendConfig) | API 基地址 |
| `api_key` | `apiKey` | `api_key` (in backendConfig) | API Key |
| `ssl_verify` | `sslVerify` | `verify_ssl` (in backendConfig) | SSL 校验开关 |
| `model_type` / `model_version` | — | — | 仅展示与过滤，不传给 AgentCore |

#### Seed 数据（迁移自 AGENTS.md 记录的真实运行值）

```sql
insert into sys_ai_model values (
  default, 'GLM-5.1', 'chat', '5.1', 'OpenAI',
  'http://example.com/v1', 'sk-your-api-key', '0',
  '0', 'Y', 0,
  'admin', sysdate(), '', null, '默认模型（自 yml 迁移）'
);
```

> 注：`is_default='Y'` 用于问答页面打开时默认选中的模型。

---

## 5. 菜单 SQL 设计

### 5.1 目标菜单结构

```
AI辅助 (1180, M, parent=0, order=5)            ← 新增一级目录
├── AI问答 (117, C, parent=1180, /ai/chat)     ← 原"系统工具->AI辅助"重命名+改父
│   └── 对话发送 (1171, F, ai:chat:send)        ← 已存在，不动
└── Models (1182, C, parent=1180, /ai/models)  ← 新增
    ├── 列表 (1183, F, ai:model:list)
    ├── 新增 (1184, F, ai:model:add)
    ├── 修改 (1185, F, ai:model:edit)
    ├── 删除 (1186, F, ai:model:remove)
    └── 测试连通 (1187, F, ai:model:test)
```

> menu_id 范围说明：现有占用 1-4、100-117、1171；`sys_menu` 表 `auto_increment=2000`，手填 ID 须 < 2000。选取 1180~1187 避免冲突。

### 5.2 SQL 文件 `sql/ai_model_menu.sql`

```sql
-- ----------------------------------------------------------
-- LLM 管理功能菜单
-- 1) 新增一级菜单"AI辅助"
-- 2) 原 117 由"系统工具->AI辅助"改为"AI辅助->AI问答"
-- 3) 新增"AI辅助->Models"及其按钮权限
-- ----------------------------------------------------------

-- 一级目录：AI辅助
insert into sys_menu values (
  '1180', 'AI辅助', '0', '5', '#', '', 'M', '0', '1', '',
  'fa fa-rocket', 'admin', sysdate(), '', null, 'AI辅助目录'
);

-- 重命名并改父：原 117 "AI辅助" -> "AI问答" (parent=1180)
-- 用 INSERT ... ON DUPLICATE KEY UPDATE 同时兼容"已导入旧 ai_menu.sql"和"全新库"两种情况
insert into sys_menu values (
  '117', 'AI问答', '1180', '1', '/ai/chat', '', 'C', '0', '1',
  'ai:chat:view', 'fa fa-comments', 'admin', sysdate(), '', null, 'AI问答菜单'
)
on duplicate key update
  menu_name='AI问答', parent_id='1180', order_num=1,
  url='/ai/chat', menu_type='C', perms='ai:chat:view',
  icon='fa fa-comments', update_by='admin', update_time=sysdate(),
  remark='AI问答菜单';

-- 新增二级菜单：Models
insert into sys_menu values (
  '1182', 'Models', '1180', '2', '/ai/models', '', 'C', '0', '1',
  'ai:model:view', 'fa fa-cubes', 'admin', sysdate(), '', null, 'LLM 模型管理菜单'
);

-- Models 按钮权限
insert into sys_menu values ('1183', '列表查询', '1182', '1', '#', '', 'F', '0', '1', 'ai:model:list',  '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values ('1184', '新增',     '1182', '2', '#', '', 'F', '0', '1', 'ai:model:add',   '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values ('1185', '修改',     '1182', '3', '#', '', 'F', '0', '1', 'ai:model:edit',  '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values ('1186', '删除',     '1182', '4', '#', '', 'F', '0', '1', 'ai:model:remove','#', 'admin', sysdate(), '', null, '');
insert into sys_menu values ('1187', '测试连通', '1182', '5', '#', '', 'F', '0', '1', 'ai:model:test',  '#', 'admin', sysdate(), '', null, '');
```

> admin 角色权限为 `*`，菜单导入后立即可见，无需配角色权限。

---

## 6. 代码结构

### 6.1 新增文件清单

| 路径 | 作用 |
|------|------|
| `ruoyi-ai/src/main/java/com/ruoyi/ai/domain/SysAiModel.java` | 实体，继承 `BaseEntity`，加 `@Excel` 注解（参考 `SysConfig`） |
| `ruoyi-ai/src/main/java/com/ruoyi/ai/mapper/SysAiModelMapper.java` | MyBatis Mapper 接口 |
| `ruoyi-ai/src/main/resources/mapper/ai/SysAiModelMapper.xml` | Mapper XML |
| `ruoyi-ai/src/main/java/com/ruoyi/ai/service/ISysAiModelService.java` | Service 接口 |
| `ruoyi-ai/src/main/java/com/ruoyi/ai/service/impl/SysAiModelServiceImpl.java` | Service 实现，CRUD + 唯一性 + evict 联动 |
| `ruoyi-ai/src/main/java/com/ruoyi/ai/controller/SysAiModelController.java` | 页面 Controller（参考 `SysConfigController`） |
| `ruoyi-ai/src/main/java/com/ruoyi/ai/service/DeepAgentRegistry.java` | **核心**：DeepAgent 实例缓存与生命周期 |
| `ruoyi-ai/src/main/java/com/ruoyi/ai/service/AiModelConnectionTester.java` | 连通性测试（ping 提示词） |
| `ruoyi-ai/src/main/resources/templates/ai/model/model.html` | 列表页（参考 `system/config/config.html`） |
| `ruoyi-ai/src/main/resources/templates/ai/model/add.html` | 新增表单 |
| `ruoyi-ai/src/main/resources/templates/ai/model/edit.html` | 编辑表单 |

### 6.2 修改文件清单

| 路径 | 改动 |
|------|------|
| `ruoyi-ai/src/main/java/com/ruoyi/ai/config/AiConfiguration.java` | 移除 `deepAgent()` 单例 Bean；保留 `extractBundledSkills()`；提取公共 DeepAgent 构建方法供 Registry 与 Tester 复用 |
| `ruoyi-ai/src/main/java/com/ruoyi/ai/config/AiProperties.java` | 移除 `Llm` 内部类与 `ai.llm.*` 字段；保留 `agent` / `tenantDataRoot` / `skillsDir` |
| `ruoyi-admin/src/main/resources/application.yml` | 删除 `ai.llm.*` 段；保留 `ai.tenant-data-root` / `ai.skills-dir` / `ai.agent.*` |
| `ruoyi-ai/src/main/java/com/ruoyi/ai/service/AiChatService.java` | 改注入 `DeepAgentRegistry`；`chat()` 增加 `modelId` 参数；模型切换时 `clearSession(oldConvId)` 并生成新 convId |
| `ruoyi-ai/src/main/java/com/ruoyi/ai/controller/AiChatController.java` | `/send` 增加 `modelId` 参数；新增 `GET /ai/chat/models` 返回启用模型 JSON |
| `ruoyi-ai/src/main/resources/templates/ai/chat/chat.html` | 加模型下拉框；切换时清空会话；`sendMessage` 携带 `modelId`；`localStorage` 记忆选择 |

> 注：`xss.urlPatterns` 已包含 `/ai/*`（见 `application.yml:146`），无需新增。Shiro 默认 `/**` 要求登录，新接口自动受保护；CSRF 已禁用。

---

## 7. 核心组件设计

### 7.1 `DeepAgentRegistry`

```java
@Service
public class DeepAgentRegistry {
    private final ConcurrentHashMap<Long, DeepAgent> cache = new ConcurrentHashMap<>();
    private final ISysAiModelService modelService;
    private final AiConfiguration aiConfiguration;   // 复用其 buildDeepAgent / extractBundledSkills
    private final AiProperties properties;

    /** 启动期预热默认模型，保持原有启动日志 */
    @PostConstruct
    public void preloadDefault() {
        SysAiModel m = modelService.selectDefaultModel();   // is_default=Y & status=0
        if (m == null) {
            log.warn("[ruoyi-ai] 无可用默认模型，请到 Models 菜单配置");
            return;
        }
        getOrCreate(m.getModelId());
    }

    /** 获取或构建指定模型的 DeepAgent */
    public DeepAgent getOrCreate(Long modelId) {
        return cache.computeIfAbsent(modelId, this::buildDeepAgent);
    }

    /** 构建单个 DeepAgent（复用 AiConfiguration 的逻辑，AGENT_ID 按 modelId 区分） */
    private DeepAgent buildDeepAgent(Long modelId) {
        SysAiModel m = modelService.selectModelById(modelId);
        if (m == null || !"0".equals(m.getStatus())) {
            throw new ServiceException("模型不可用: " + modelId);
        }
        String agentId = "ruoyi_ai_deep_agent_" + modelId;   // 关键：按模型区分，避免 SysOperation 注册冲突
        return aiConfiguration.buildDeepAgent(m, agentId);
    }

    /** CRUD 后调用，关闭并移除缓存 */
    public void evict(Long modelId) {
        DeepAgent d = cache.remove(modelId);
        if (d != null) {
            try { d.close(); } catch (Exception ignored) {}
        }
    }

    /** 刷新缓存按钮 */
    public void evictAll() {
        cache.keySet().forEach(this::evict);
    }
}
```

**关键点**：
- `computeIfAbsent` 保证同 modelId 不会重复构建（避免 SysOperation 重复注册）。
- `AGENT_ID` 加 modelId 后缀，避免多实例共享同一 SysOperation 注册。
- `evict` 必须先 `close()`（`DeepAgent implements AutoCloseable`，释放底层资源）。
- `buildDeepAgent` 内部复用 `AiConfiguration` 提取出的公共构建方法（不重复 SysOperation 注入逻辑）。

### 7.2 `AiModelConnectionTester`

```java
@Service
public class AiModelConnectionTester {
    private static final String PING_PROMPT = "你好";
    private static final long TIMEOUT_MS = 30_000;

    /** 测试已保存的模型（按 id 取配置） */
    public AjaxResult testById(Long modelId) { ... }

    /** 测试表单中尚未保存的配置（前端"测试连通"按钮，避免先保存再测） */
    public AjaxResult testByModel(SysAiModel model) {
        long start = System.currentTimeMillis();
        String tmpAgentId = "ruoyi_ai_test_" + System.nanoTime();   // 唯一，避免与缓存实例和并发测试冲突
        try {
            DeepAgent tmp = aiConfiguration.buildDeepAgent(model, tmpAgentId);
            Map<String, Object> r = tmp.invoke(Map.of("query", PING_PROMPT));
            String reply = String.valueOf(r.getOrDefault("output", ""));
            long cost = System.currentTimeMillis() - start;
            return AjaxResult.success("连通成功，耗时 " + cost + "ms").put("reply", reply);
        } catch (Exception e) {
            return AjaxResult.error("连通失败：" + e.getMessage());
        } finally {
            // close tmp（try/catch）
        }
    }
}
```

> 临时实例 `AGENT_ID` 用纳秒时间戳，避免与缓存实例和并发测试互相冲突；测试结束 `close()`。

### 7.3 `AiChatService` 改造

```java
public String chat(String username, String conversationId, String query, Long modelId) {
    if (modelId == null) throw new ServiceException("请选择模型");
    DeepAgent deepAgent = registry.getOrCreate(modelId);
    // 其余逻辑不变：构造 convId、TenantContext、invoke、保存 sessionState
    // 注意：调用方在 modelId 切换时清空旧 convId（见 7.4）
}
```

### 7.4 `AiChatController` 改造

```java
@PostMapping("/send")
@ResponseBody
public AjaxResult send(@RequestParam String query,
                      @RequestParam(required=false) String conversationId,
                      @RequestParam Long modelId) {
    // 模型切换：前端切换下拉时已清空 conversationId，后端只校验 modelId 非空
    String reply = aiChatService.chat(getLoginName(), conversationId, query, modelId);
    AjaxResult r = AjaxResult.success(reply);
    r.put("conversationId", conversationId == null ? genConvId() : conversationId);
    r.put("modelId", modelId);
    return r;
}

@GetMapping("/models")
@ResponseBody
public AjaxResult listEnabledModels() {
    // 返回 status=0 的模型，按 sort_order 排序，字段精简（id/name/version）
}
```

### 7.5 `AiConfiguration` 改造

- 删除 `@Bean public DeepAgent deepAgent()`。
- 保留 `extractBundledSkills()`、`createSysOperation()`、`injectSysOpTools()`。
- 抽取公共方法 `buildDeepAgent(SysAiModel model, String agentId)`，供 `DeepAgentRegistry` 与 `AiModelConnectionTester` 复用（避免逻辑重复）。
- 原 `buildModelConfig(AiProperties.Llm)` / `buildBackendConfig(AiProperties.Llm)` 改签名为接收 `SysAiModel`。

---

## 8. 前端设计

### 8.1 `chat.html` 改动

```html
<!-- 在 ibox-title 区加下拉 -->
<div class="ibox-tools">
    <select id="modelSelect" class="form-control input-sm" style="min-width:180px">
        <option value="">加载中...</option>
    </select>
</div>
```

```javascript
// 页面加载：拉取启用模型列表，回填下拉，回填上次选择（localStorage）
function loadModels() {
    $.get(ctx + 'ai/chat/models', function(resp) {
        var $sel = $('#modelSelect').empty();
        (resp.data || []).forEach(function(m) {
            $sel.append('<option value="'+m.modelId+'">'
                + m.modelName + ' (' + m.modelVersion + ')</option>');
        });
        var saved = localStorage.getItem('aiChatModelId');
        if (saved && $sel.find('option[value="'+saved+'"]').length) $sel.val(saved);
        else if (resp.data[0]) $sel.val(resp.data[0].modelId);   // 默认第一个
    });
}

// 切换模型：清空会话 + 提示
$('#modelSelect').on('change', function() {
    conversationId = '';
    $('#chatMessages').empty();
    appendMessage('agent', '已切换模型，新会话已开始。');
    localStorage.setItem('aiChatModelId', $(this).val());
});

// sendMessage 增加 modelId
data: { query: query, conversationId: conversationId, modelId: $('#modelSelect').val() }
```

### 8.2 Models CRUD 页面

完全复刻 `system/config` 的三件套（`model.html` / `add.html` / `edit.html`），仅改字段：
- 列表列：`modelId` / `modelName` / `modelType` / `modelVersion` / `modelProvider` / `status`（0启用/1停用 dict）/ `isDefault` / 操作
- 工具栏按钮：新增 / 修改 / 删除 / 测试连通（行内按钮 + 工具栏"刷新缓存"）
- 表单字段：除列表字段外，含 `apiBase`（text，必填）/ `apiKey`（password，必填）/ `sslVerify`（radio Y/N）/ `sortOrder`（number）/ `remark`（textarea）
- 表单内"测试连通"按钮：先 `serialize` 表单，POST `/ai/models/testConnect`，弹窗显示结果

---

## 9. 权限设计

| 权限标识 | 用途 | 菜单/按钮 |
|----------|------|-----------|
| `ai:chat:view` | 进入 AI问答页面 | 117 (已存在) |
| `ai:chat:send` | 发送对话 | 1171 (已存在) |
| `ai:model:view` | 进入 Models 页面 | 1182 |
| `ai:model:list` | 列表查询 | 1183 |
| `ai:model:add` | 新增 | 1184 |
| `ai:model:edit` | 修改 | 1185 |
| `ai:model:remove` | 删除 | 1186 |
| `ai:model:test` | 测试连通 | 1187 |

> Controller 用 `@RequiresPermissions(...)`；模板用 `shiro:hasPermission="ai:model:xxx"` 控制按钮显隐，参考 `system/config/config.html`。

---

## 10. 配置迁移与启动行为

### 10.1 yml 改动（`application.yml`）

删除 `ai.llm.*` 段：

```yaml
ai:
  tenant-data-root: /home/luffy/ruoyi/ai/workspace
  skills-dir: /home/luffy/ruoyi/ai/skills
  # llm: 段删除 — 改由数据库 sys_ai_model 表管理
  agent:
    max-iterations: 30
    system-prompt: >-
      你是一个乐于助人的 AI 助手。当需要查询实时信息（例如天气）时，
      请使用 executeCmd 工具运行 shell 命令获取数据
      （如：curl -s "https://wttr.in/Shenzhen?format=3&lang=zh" 查询天气）。
      也可以先调用 list_skill 查看可用技能，再调用 skill_tool 阅读技能说明。
      回答时使用中文。
```

### 10.2 启动期行为

- `DeepAgentRegistry.@PostConstruct preloadDefault()`：找 `is_default=Y AND status=0` 的模型预热。
- 找到时打印原有日志（保持 AGENTS.md 描述的启动验证步骤有效）：
  ```
  [ruoyi-ai] 内置技能已解压至: ...
  [ruoyi-ai] 初始化 DeepAgent: model=GLM-5.1, provider=OpenAI, apiBase=...
  [ruoyi-ai] DeepAgent 初始化完成: skillsDir=...
  ```
- 找不到时打印 WARN：`[ruoyi-ai] 无可用默认模型，请到 Models 菜单配置`，启动不中断。

---

## 11. 风险与注意事项

| 风险 | 缓解 |
|------|------|
| 多个 `DeepAgent` 实例共享 `AGENT_ID` 导致 `SysOperation` 注册冲突 | `AGENT_ID` 加 `_<modelId>` 后缀；临时测试实例用纳秒时间戳 |
| CRUD 后未 evict 缓存 → DB 改了但运行期仍用旧配置 | `SysAiModelServiceImpl` 的 `update`/`delete`/`changeStatus` 均调用 `registry.evict(modelId)` |
| 缓存中的 `DeepAgent` 未 `close()` → 资源泄漏 | `evict` 先 `close()` 再移除；`@PreDestroy` 兜底全清 |
| 模型切换后会话串话 | 前端切换下拉清空 `conversationId`；后端不依赖跨模型复用 convId |
| DB 无可用模型时启动失败 | `preloadDefault` 找不到时仅 WARN，不抛异常 |
| 旧 `ai_menu.sql` 已导入 vs 未导入两种环境 | 用 `INSERT ... ON DUPLICATE KEY UPDATE` 兼容 |
| 临时测试实例与缓存实例并发构建相同 SysOperation | 临时实例用唯一 `AGENT_ID`（纳秒），构建后立即 `close` |
| `application.yml` 改动打进 JAR 后不生效 | 全量 `mvn clean package -DskipTests` 后再启动 |
| `agent-core-java` 0.1.13 仓库找不到 | 本地仓库 `/opt/programs/java/maven-repo`（见 AGENTS.md），已存在 |
| Thymeleaf 用 `#httpServletRequest` 会报错 | 模板里只用 `@{...}` 表达式（参考现有 `chat.html`） |

---

## 12. 测试计划

### 12.1 单元/集成（接口层）

```bash
# 登录（关验证码启动后）
rm -f /tmp/cookies.txt
curl -s -c /tmp/cookies.txt -X POST http://localhost:8080/login \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin&password=<admin_password>&rememberMe=false"

# Models 列表
curl -s -b /tmp/cookies.txt -X POST http://localhost:8080/ai/models/list

# 测试连通（按 id）
curl -s -b /tmp/cookies.txt -X POST http://localhost:8080/ai/models/test/1

# 问答页面取启用模型
curl -s -b /tmp/cookies.txt http://localhost:8080/ai/chat/models

# 选模型发问答
curl -s -b /tmp/cookies.txt -X POST http://localhost:8080/ai/chat/send \
  -H "Content-Type: application/x-www-form-urlencoded" --max-time 90 \
  -d "query=查询深圳天气&modelId=1"
```

### 12.2 浏览器实测（必做，per AGENTS.md）

1. 菜单：登录后左侧出现"AI辅助"一级菜单，展开有"AI问答"与"Models"两项。
2. Models 页面：列表/新增/编辑/删除/测试连通全流程；"刷新缓存"按钮可用。
3. AI问答页面：下拉有启用模型；切换下拉后聊天记录清空并提示；选不同模型各发一句问答成功。
4. 响应体完整性：`curl -s -o /tmp/page.html -w "HTTP: %{http_code}\n" http://localhost:8080/ai/models`，`wc -l` 行数合理、末尾 `</html>`。

### 12.3 三层验证（per AGENTS.md）

- 编译：`mvn -pl ruoyi-ai -am compile -DskipTests` BUILD SUCCESS
- 接口：curl 拿到预期 JSON
- 浏览器：实测页面渲染 + 一次交互

---

## 13. 后续可扩展项（非本期）

- 模型调用流式输出（`DeepAgent.stream(...)`）
- 模型调用计量与配额
- 每用户默认模型偏好持久化（当前用 localStorage）
- 模型分组/标签
- 接入更多 provider（DashScope / Anthropic / 本地 Ollama）

---

## 附：参考资料

- 项目根 `AGENTS.md`（技术栈、构建、坑）
- `ruoyi-ai/src/main/java/com/ruoyi/ai/config/AiConfiguration.java`（DeepAgent 构建范式）
- `ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysConfigController.java`（CRUD Controller 模板）
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/SysConfig.java` + `mapper/system/SysConfigMapper.xml`（Domain + Mapper XML 模板）
- `ruoyi-admin/src/main/resources/templates/system/config/{config,add,edit}.html`（CRUD 模板）
- `agent-core-java/src/main/java/com/openjiuwen/harness/deep_agent/DeepAgent.java`（无 `switchModel`，验证 baked-in 设计前提）
- `sql/ai_menu.sql`（菜单 SQL 范式）
- `sql/ry_20260319.sql` lines 132-151（`sys_menu` 表结构）
