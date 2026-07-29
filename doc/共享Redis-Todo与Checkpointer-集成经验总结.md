# Todo 与 Checkpointer 共享 Redis 集成经验总结

> 实施时间：2026-07-29
> 涉及代码：`ruoyi-ai/src/main/java/com/ruoyi/ai/config/AiConfiguration.java`
> 关联文档：`agent-core-java/documents/zh/2.开发指南/高阶用法/多租户数据隔离.md` 章节「共享 KV 实例机制」

## 一、目标

参考 agent-core-java 文档「共享 KV 实例机制」章节，让 DeepAgent 内部的 **Todo** 和 **Checkpointer** 共用同一份 Redis 连接（同一 RedisStore 实例），避免重复配置；并构造一个能生成 todo 任务的 AI 查询验证持久化是否正确。

## 二、最终改动

`AiConfiguration.buildDeepAgent()` 中 `DeepAgentConfig.builder()` 新增三个配置项 + 一段时序修复代码：

```java
.enableTaskPlanning(true)                // 启用 TaskPlanningRail，暴露 todo_create / todo_modify / todo_list 工具
.todoStorageType("kv")                   // Todo 存储切到 KV 模式
.kvStoreConfig(Map.of(
    "type", "redis",
    "conf", Map.of(
        "host", ..., "port", ...,
        "redis_client", jedisPooled)))   // 复用启动期共享的线程安全 JedisPooled

// HarnessFactory.createDeepAgent 后手动调一次（绕过框架时序 bug，详见下文）
deepAgent.ensureInitialized();
```

Checkpointer 仍由 `setupRedisCheckpointer()` 注册到 `CheckpointerFactory.setDefaultCheckpointer()`，包装同一份 `sharedRedisStore`。最终 Checkpointer 与 Todo 共享同一 `RedisStore(jedisPooled)`，连同一 Redis。

## 三、为什么花了这么长时间

### 3.1 探索阶段：框架机制不透明

| 探索点 | 耗时原因 |
|--------|----------|
| `CheckpointerFactory` 注册机制 | 需读 `CheckpointerFactory.java`、`RedisCheckpointer.java`、`META-INF/services/` SPI 注册文件，确认 RedisCheckpointer 通过 ServiceLoader 加载 |
| `RedisStore` 反射契约 | 需读 `RedisStore.java`（1179 行）找出 redisClient 上需要的方法名：`set/get/exists/del/expire/keys/mget` + 可选 `pipeline` |
| `RedisKVStoreProvider` 加载 Jedis 方式 | 通过 `Class.forName("redis.clients.jedis.Jedis")` 反射创建**单 Jedis**（非线程安全）—— 关键发现，决定了不能让它自动创建客户端 |
| 文档与源码偏差 | 文档说「kvStoreConfig 同时驱动 Checkpointer 和 Todo」，实际源码 Checkpointer 走 `CheckpointerFactory.getCheckpointer()`，Todo 才用 `deepAgent.getKvStore()`，两条独立路径 |

### 3.2 失败的尝试：4 轮方案迭代

每次都需要：编译 → 打包（5+ 分钟）→ setsid 启动 Java（脱离 bash 工具 session）→ 等启动（30s）→ 登录 → 跑多步 AI 查询（30-60s，因为 LLM 多轮调用）→ 看日志/redis-cli。一轮迭代 ~10 分钟。

| 轮次 | 方案 | 失败原因 | 关键日志 |
|------|------|----------|----------|
| 1 | `buildDeepAgent` 后 `deepAgent.setKvStore(sharedRedisStore)` | `TaskPlanningRail.init` 在 DeepAgent 构造期跑（早于 setKvStore），那时 kvStore 为 null → 走 InMemoryKVStore | Redis 无 todo key |
| 2 | 配 `kvStoreConfig` 让 `HarnessFactory.injectKvStore` 在 DeepAgent 构造期注入 | 误以为 `TaskPlanningRail.init` 在构造函数调（实际在 `ensureInitialized` lazy 调）；且 `buildTodoStorageConfig` 没在 conf 放 `kvStoreType` 字段，`KvTodoStorageProvider` 默认走 in_memory | Redis 无 todo key |
| 3 | `uninit + init` 重跑 `TaskPlanningRail` | `deepAgent.getRegisteredRails()` 返回空列表——rails 注册在 `ensureInitialized` 里（lazy，首次 invoke 时才跑），buildDeepAgent 后还没注册 | `rails=[]` |
| 4 | `deepAgent.ensureInitialized()` 提前触发 lazy 初始化 | ✅ 成功——此时 `injectKvStore` 已跑过，`deepAgent.getKvStore()` 非 null，`buildTodoStorageConfig` 走 shared 路径 | `kvStore=RedisStore` + Redis SET 成功 |

### 3.3 框架时序 bug：核心障碍

agent-core-java 0.1.13 的执行时序：

```
HarnessFactory.createDeepAgent(card, config, workspace):
  1. enrichConfig(...)           # 把 TaskPlanningRail 加到 config.rails 局部 list
  2. new DeepAgent(...)          # 构造函数本身不调 ensureInitialized，isInitialized=false
  3. injectKvStore(agent, config)  # 此时才设 agent.kvStore
  4. return agent                # 但 ensureInitialized 还没跑（lazy）

DeepAgent.ensureInitialized():   # 在首次 invoke/stream 时才调
  - iterate config.rails:
    - TaskPlanningRail.init(this)  # 此时读 deepAgent.getKvStore() ——非 null ✓
    - registerDeepRail(rail)       # 加到 registeredRails
```

如果我**不**提前调 `ensureInitialized()`，运行期首次 invoke 时 `TaskPlanningRail.init` 跑，此时 `deepAgent.getKvStore()` 已被 `injectKvStore` 设置，**理论上**应该走 shared 路径——但实际 MONITOR 显示 todo 仍走 in_memory，原因不明（可能 `injectKvStore` 没设字段或 ensureInitialized 内有别的覆盖）。手动提前调一次保证 init 走 shared 路径。

### 3.4 bash 工具 / 环境摩擦

| 问题 | 影响 |
|------|------|
| `pkill -f 'ruoyi-admin.jar'` 自匹配 bash 自身命令行 | 多次 120s 超时；改用 `pgrep -f '^java -jar ruoyi-admin'` + `kill -9` |
| bash 工具调用结束杀子进程组（SIGTERM） | nohup 只忽略 SIGHUP，java 被 SIGTERM shutdown；改用 `setsid -f bash -c 'java ...'` 让 java 真正脱离 process group |
| mvn -am package 串行编译多个模块 | 单次 ~3-5 分钟；`-q` 模式无输出，需信任完成；用户要求加超时（600000ms） |
| DashScope LLM 多轮调用慢 | 单次多步任务 30-60s（todo_create + 多个 executeCmd curl + 多个 todo_modify） |
| redis-cli KEYS/MONITOR 大量输出 | 工具输出超 51200 字节被截断保存到文件，需 grep 过滤 |

### 3.5 conversationId bug 干扰判断

`AiChatController.send()` 中：
```java
String reply = aiChatService.chat(username, conversationId, query, modelId);  // 内部用 conversationId=1785253081747 存到 Redis
String convId = (conversationId == null) ? "ruoyi_ai_" + username + "_" + System.currentTimeMillis() : conversationId;
result.put("conversationId", convId);  // 给前端的是 1785253091745（差 10s）
```

DeepAgent 内部 conversationId 与 controller 返回的不一致。每次 curl 看 conversationId 都不是 Redis 里的 key，造成判断混乱。需直接看 server log `会话=ruoyi_ai_admin_XXX` 才能定位真实 conversationId。

### 3.6 TodoTool sessionId 用 "default"（额外发现）

`TaskPlanningRail.sessionId(inputs)` 静态方法从 LLM tool inputs 取 `session_id` 字段，LLM 不传则默认 `"default"`。但 line 324 `ctx.getSession().getSessionId()` 内部 task loop 用真实 conversationId。

导致 todo SET 写到 `admin:default:todo`，但 GET 读 `admin:{conversationId}:todo`，两者 key 不一致。多用户/多会话会串数据。

**这是 agent-core-java 框架 bug**，需修框架源码或在 `buildDeepAgent` 后反射 hack 替换 todoTool。**未修复**，作为已知问题记录。

## 四、验证证据

### 启动日志
```
[ruoyi-ai] Redis PING 127.0.0.1:6379 -> PONG
[ruoyi-ai] RedisCheckpointer 已注册为默认 checkpointer（共享 RedisStore 同时服务 Todo）: 127.0.0.1:6379 (ttl=10080.0min)
[ruoyi-ai] DeepAgent.ensureInitialized 已提前触发：kvStore=RedisStore（与 Checkpointer 共享） todoStorageType=kv
```

### LLM 工具调用（server log）
```
[LLM]   tool_call: todo_create({"tasks": [{"content": "查询北京天气", ...}, {"content": "查询上海天气", ...}, {"content": "对比温度", ...}]})
[LLM]   tool_call: executeCmd({"command": "curl -s \"https://wttr.in/Beijing?...\""})
[LLM]   tool_call: executeCmd({"command": "curl -s \"https://wttr.in/Shanghai?...\""})
[LLM]   tool_call: todo_modify({"action": "update", "todos": [...]})  # 多次
```

### Redis MONITOR 实时命令
```
1785256924.459580 "SET" "admin:default:todo" "[{\"id\":\"2443c331-...\",\"content\":\"查询北京天气\",\"status\":\"IN_PROGRESS\",...}, ...]"
1785256939.404047 "SET" "admin:default:todo" "[...\"status\":\"COMPLETED\",\"result_summary\":\"北京：...\"...]"
```

### Redis 最终持久化
```
admin:default:todo              TYPE=string  STRLEN=1326  TTL=-1  （含 3 个 TodoItem JSON）
admin:weather_comparison:todo   TYPE=string  STRLEN=1053  TTL=-1  （LLM 尝试用 weather_comparison 作 sessionId）
admin:ruoyi_ai_admin_1785256917808:agent:ruoyi_ai_deep_agent_102:agent_state_blobs          # Checkpointer state（独立 key 前缀）
```

**关键证明**：Checkpointer 与 Todo 写入同一 Redis 实例，key 前缀不同（`{tenantId}:{sessionId}:agent:...` vs `{tenantId}:{sessionId}:todo`）共存不冲突，文档「前缀不同、互不冲突」描述准确。

## 五、反思与建议

### 5.1 文档与实现的偏差

文档说「kvStoreConfig 配置的 Redis 连接**同时服务 Checkpointer 和 Todo**」，但实际源码：
- Todo 走 `deepAgent.getKvStore()`（`injectKvStore` 注入）
- Checkpointer 走 `CheckpointerFactory.getCheckpointer()`（独立静态单例）

两条路径**默认不共享**，需要应用层手动让两者包装同一份 `RedisStore(jedisPooled)`（本文做法：通过 `redis_client` 字段让 `injectKvStore` 复用 `setupRedisCheckpointer` 创建的 `jedisPooled`）。

**建议**：在 `agent-core-java` 0.1.14+ 文档中明确「Checkpointer 默认走 CheckpointerFactory，Todo 走 agent.kvStore，需应用层手动桥接两者共享同一 RedisStore」。

### 5.2 框架时序 bug 应在框架层修复

`injectKvStore` 在 `new DeepAgent(...)` 之后才调，但 `ensureInitialized` 内的 `TaskPlanningRail.init` 读 `deepAgent.getKvStore()` 时 kvStore 可能仍为 null（如果应用层未提前调 ensureInitialized）。

**建议**：`agent-core-java` 的 `HarnessFactory.createDeepAgent` 改为：
```java
DeepAgent agent = new DeepAgent(...);
injectKvStore(agent, effectiveConfig);   # 先注入
agent.ensureInitialized();                # 再初始化 rails
return agent;
```

这样应用层无需手动调 `ensureInitialized()`，且 rails 注册在 `createDeepAgent` 返回前完成。

### 5.3 TodoTool sessionId 应绑定 ctx

`TaskPlanningRail.sessionId(inputs)` 从 LLM tool inputs 取 `session_id`，LLM 默认不传 → 用 `"default"`，造成多会话串数据。

**建议**：`sessionId(inputs)` 改为优先从当前 `TaskIterationContext.getSession().getSessionId()` 取，无 ctx 时才回退 inputs。或 `LocalFunction` 调 lambda 前自动注入当前 sessionId 到 inputs。

### 5.4 对应用层（RuoYi）的建议

| 项 | 现状 | 建议 |
|----|------|------|
| `AiChatController.send()` conversationId bug | chat() 内部生成 conversationId 存 Redis，controller 给前端重新生成另一个 | 在 `chat()` 内生成 conversationId 后**返回给 controller**，controller 用同一个返回给前端 |
| TodoTool sessionId="default" 串数据 | 待框架修复（5.3） | 框架修前可反射 hack：`buildDeepAgent` 后反射替换 `TaskPlanningRail.todoTool` 为自定义 TodoTool，从 ThreadLocal/ctx 取 sessionId |
| redis-cli MONITOR 输出量大 | 工具输出超 51200 字节截断 | 用 `timeout N redis-cli MONITOR > /tmp/xxx.log &` + 后台跑 + `grep` 过滤 |
| bash 工具杀子进程 | nohup + & + disown 仍被 SIGTERM | 用 `setsid -f bash -c '...' < /dev/null` 让进程脱离 process group |

### 5.5 整体经验

- **agent-core-java 是黑盒**：没有完整调用图，需多次源码阅读 + 反复实验才能定位时序问题
- **每轮迭代成本高**：mvn package ~5min + 启动 ~30s + AI 多步任务 ~30-60s + redis-cli 验证 → 单轮 ~10min
- **框架 bug 绕过 vs 修复**：依赖 jar 不能改框架源码，必须用应用层 hack（`ensureInitialized` 提前调）绕过；应在框架 issue tracker 报告 bug 推动 5.2 / 5.3 修复
- **MONITOR 是关键调试工具**：当 Redis 没有期望 key 时，MONITOR 能直接看到实际 SET/GET 命令，比 redis-cli KEYS 更有诊断力
