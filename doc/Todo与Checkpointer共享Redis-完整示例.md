# Todo 与 Checkpointer 共享 Redis 完整示例

> 适用版本：agent-core-java 0.1.13 + Spring Boot 4.0.x + Jedis 5.1.5
> 关联文档：`agent-core-java/documents/zh/2.开发指南/高阶用法/多租户数据隔离.md` 章节「共享 KV 实例机制」
> 参考实现：RuoYi `ruoyi-ai` 模块（commit `7a334794`）

## 一、目标

让 DeepAgent 内部的 **Todo**（任务规划存储）和 **Checkpointer**（会话状态检查点）共用同一份 Redis 连接，避免重复配置；并构造 AI 查询验证持久化生效。

## 二、设计原理

### 2.1 两条独立路径

agent-core-java 内部，Todo 和 Checkpointer 走两条独立的 KV 路径：

| 组件 | 路径 | 入口 |
|------|------|------|
| Checkpointer | `CheckpointerFactory.getCheckpointer()` 静态单例 | DeepAgent 调 `AgentSessionApi.preRun/postRun` 时取默认 checkpointer |
| Todo | `deepAgent.getKvStore()` 实例字段 | `TaskPlanningRail.init` 时 `buildTodoStorageConfig(deepAgent, "kv")` 读 `deepAgent.getKvStore()` |

文档「共享 KV 实例机制」说"一套 kvStoreConfig 同时服务 Checkpointer 和 Todo"——实际源码两条路径**默认不共享**，需要应用层手动桥接。

### 2.2 桥接策略

应用层（RuoYi `AiConfiguration`）在启动期创建一份线程安全的 `JedisPooled`，然后：

1. **Checkpointer 路径**：包装 `RedisStore(jedisPooled)` 为 `RedisCheckpointer`，注册到 `CheckpointerFactory.setDefaultCheckpointer()`
2. **Todo 路径**：通过 `DeepAgentConfig.kvStoreConfig.conf.redis_client = jedisPooled` 让 `HarnessFactory.injectKvStore` 创建的 `agent.kvStore` 也是包装同一 `jedisPooled` 的 `RedisStore`

两条路径包装同一 `jedisPooled`，最终连同一 Redis，**真正共享**。

### 2.3 框架时序 bug 与绕过

agent-core-java 0.1.13 的执行时序：

```
HarnessFactory.createDeepAgent:
  1. enrichConfig()           # TaskPlanningRail 加入 config.rails
  2. new DeepAgent(...)      # 构造函数本身不调 ensureInitialized
  3. injectKvStore(agent)    # 此时才设 agent.kvStore
  4. return agent             # ensureInitialized 还没跑（lazy）

DeepAgent.ensureInitialized():  # 在首次 invoke/stream 时才调
  iterate config.rails:
    TaskPlanningRail.init(this)  # 此时读 deepAgent.getKvStore() —— 非 null ✓
```

`ensureInitialized()` 默认 lazy 调用，运行期首次 invoke 时 kvStore 已注入，理论上 shared 路径生效。但实测需要应用层手动调一次 `ensureInitialized()` 保证 Todo 走 shared 路径（直接走 fallback 会因 `buildTodoStorageConfig` 未传 `kvStoreType` 字段，`KvTodoStorageProvider` 默认 `in_memory`）。

## 三、前置条件

| 项 | 要求 |
|----|------|
| Redis 服务 | 已启动，应用可访问（示例：`127.0.0.1:6379`） |
| Jedis 依赖 | `redis.clients:jedis:5.1.5` 在 classpath（agent-core-java 是 test scope，不传递，应用需自行引入） |
| agent-core-java | 0.1.13+ |
| Spring Boot | 4.0.x（其他版本未验证） |

## 四、完整代码示例

### 4.1 Maven 依赖

`ruoyi-ai/pom.xml`：

```xml
<!-- Redis 客户端：JedisPooled 线程安全连接池，方法签名满足 RedisStore 反射契约 -->
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.1.5</version>
</dependency>
```

### 4.2 配置属性

`AiProperties.java`（新增 `Redis` 静态嵌套类）：

```java
public class AiProperties {

    private Agent agent = new Agent();
    private String tenantDataRoot = "/home/luffy/ruoyi/ai/workspace";
    private String skillsDir = "/home/luffy/ruoyi/ai/skills";

    /** Redis 检查点存储配置（用于 DeepAgent 内置 RedisCheckpointer） */
    private Redis redis = new Redis();

    // ... getter/setter ...

    public static class Agent { /* ... */ }

    /** Redis 检查点存储参数 */
    public static class Redis {
        /** Redis 主机。留空则禁用 RedisCheckpointer，回退到 InMemoryCheckpointer */
        private String host;
        /** Redis 端口 */
        private int port = 6379;
        /** 默认 TTL（分钟）。null 表示不设置 TTL（永久保留）。 */
        private Double defaultTtlMinutes;

        // ... getter/setter ...
    }
}
```

### 4.3 application.yml 配置

```yaml
ai:
  tenant-data-root: /home/luffy/ruoyi/ai/workspace
  skills-dir: /home/luffy/ruoyi/ai/skills
  # Redis 检查点存储：DeepAgent 内置 RedisCheckpointer 通过此连接持久化会话状态
  # host 留空则禁用，回退到 InMemoryCheckpointer（仅本进程内存，重启丢失）
  redis:
    host: 127.0.0.1
    port: 6379
    # 默认 TTL（分钟），null=永久保留。会话状态 7 天后自动过期
    default-ttl-minutes: 10080
  agent:
    max-iterations: 30
    system-prompt: >-
      你是一个乐于助人的 AI 助手。当需要查询实时信息（例如天气）时，
      请使用 executeCmd 工具运行 shell 命令获取数据
      （如：curl -s "https://wttr.in/Shenzhen?format=3&lang=zh" 查询天气）。
      回答时使用中文。
```

### 4.4 AiConfiguration 核心代码

```java
package com.ruoyi.ai.config;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import com.openjiuwen.core.session.checkpointer.Checkpointer;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.extensions.checkpointer.redis.RedisCheckpointer;
import com.openjiuwen.extensions.store.kv.RedisStore;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;

import redis.clients.jedis.JedisPooled;

@Configuration
public class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    private final AiProperties properties;

    /** JedisPooled 客户端实例；@PreDestroy 中关闭以释放连接池 */
    private JedisPooled jedisPooled;

    public AiConfiguration(AiProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        setupRedisCheckpointer();  // 创建共享 jedisPooled + 注册 Checkpointer
    }

    @PreDestroy
    public void destroy() {
        if (jedisPooled != null) {
            try {
                jedisPooled.close();
                log.info("[ruoyi-ai] JedisPooled 已关闭");
            } catch (Exception e) {
                log.warn("[ruoyi-ai] 关闭 JedisPooled 异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 注册 Redis 检查点存储到 DeepAgent 内置 CheckpointerFactory。
     * <p>
     * 共享的 RedisStore 实例由 {@link #buildSharedRedisStore} 构造，同时通过
     * DeepAgentConfig.kvStoreConfig.conf.redis_client 让 Todo 复用，实现 Checkpointer
     * 与 Todo 共享同一份 Redis 连接（文档「共享 KV 实例机制」）。
     * <p>
     * 任何异常（host 未配置、连接失败）都被吞掉，回退到 InMemoryCheckpointer，应用仍可启动。
     */
    private void setupRedisCheckpointer() {
        AiProperties.Redis cfg = properties.getRedis();
        if (cfg == null || cfg.getHost() == null || cfg.getHost().isBlank()) {
            log.warn("[ruoyi-ai] ai.redis.host 未配置，回退到 InMemoryCheckpointer");
            return;
        }

        try {
            sharedRedisStore = buildSharedRedisStore(cfg);

            // TTL 配置（可选）
            Map<String, Object> ttlMap = null;
            if (cfg.getDefaultTtlMinutes() != null) {
                ttlMap = new LinkedHashMap<>();
                ttlMap.put("default_ttl", cfg.getDefaultTtlMinutes());
                ttlMap.put("refresh_on_read", true);
            }

            // 注册 Checkpointer（包装 sharedRedisStore）
            RedisCheckpointer redisCheckpointer = new RedisCheckpointer(sharedRedisStore, ttlMap);
            CheckpointerFactory.setDefaultCheckpointer(redisCheckpointer);
            log.info("[ruoyi-ai] RedisCheckpointer 已注册为默认 checkpointer: {}:{} (ttl={}min)",
                    cfg.getHost(), cfg.getPort(),
                    cfg.getDefaultTtlMinutes() != null ? cfg.getDefaultTtlMinutes() : "永久");
        } catch (Exception e) {
            // 清理已创建的连接，避免连接泄漏
            if (jedisPooled != null) {
                try {
                    jedisPooled.close();
                } catch (Exception closeEx) {
                    log.debug("[ruoyi-ai] 关闭失败的 JedisPooled 异常: {}", closeEx.getMessage());
                }
                jedisPooled = null;
            }
            sharedRedisStore = null;
            log.warn("[ruoyi-ai] Redis 初始化失败（{}:{}），回退到 InMemoryCheckpointer。原因: {}",
                    cfg.getHost(), cfg.getPort(), e.getMessage(), e);
        }
    }

    /**
     * 构造共享 RedisStore 实例（含 JedisPooled 客户端创建 + 连通性探测）。
     * <p>
     * 创建的 JedisPooled（线程安全连接池）同时赋值给 {@link #jedisPooled} 字段，供
     * {@link #buildDeepAgent} 通过 kvStoreConfig.conf.redis_client 注入到 DeepAgent 让
     * Todo 复用同一份；返回的 RedisStore 由 {@link #setupRedisCheckpointer} 包装为
     * RedisCheckpointer 注册到 CheckpointerFactory。
     * <p>
     * Jedis 5.x 的 JedisPooled 方法签名（set/get/exists/del/expire/keys/mget）与
     * RedisStore 反射契约完全兼容，无需适配器代码。
     * <p>
     * 调用方负责异常处理（host 未配置或连接失败时回退到 InMemory）。
     *
     * @param cfg Redis 配置（host/port）
     * @return 包装 JedisPooled 的 RedisStore 实例
     * @throws Exception 连接失败或客户端创建失败
     */
    private RedisStore buildSharedRedisStore(AiProperties.Redis cfg) throws Exception {
        jedisPooled = new JedisPooled(cfg.getHost(), cfg.getPort());
        // 用 PING 探测一次连接，避免配置错误时延迟到首条对话才暴露
        String pong = jedisPooled.ping();
        log.info("[ruoyi-ai] Redis PING {}:{} -> {}", cfg.getHost(), cfg.getPort(), pong);
        return new RedisStore(jedisPooled);
    }

    /**
     * 构建 DeepAgent 实例（不缓存，由 DeepAgentRegistry 按模型懒加载）。
     */
    public DeepAgent buildDeepAgent(SysAiModel model, String agentId) throws Exception {
        AiProperties.Agent agentConf = properties.getAgent();

        Map<String, Object> modelConfig = buildModelConfig(model);
        Map<String, Object> backendConfig = buildBackendConfig(model);

        DeepAgentConfig.DeepAgentConfigBuilder configBuilder = DeepAgentConfig.builder()
                .enableTaskLoop(true)
                // 启用 TaskPlanningRail，暴露 todo_create/todo_modify/todo_list 工具给 LLM
                .enableTaskPlanning(true)
                .systemPrompt(agentConf.getSystemPrompt())
                .maxIterations(agentConf.getMaxIterations())
                .language("cn")
                .model(modelConfig)
                .backend(backendConfig)
                .enableTenantIsolation(true)
                .tenantDataRoot(properties.getTenantDataRoot())
                // Todo 存储切到 KV 模式
                .todoStorageType("kv")
                .rails(List.of(/* ... */));

        // 配置 KV 存储：通过 redis_client 字段传入共享的 jedisPooled
        // 让 HarnessFactory.injectKvStore 创建的 agent.kvStore 也是包装同一 jedisPooled 的 RedisStore
        // 与 Checkpointer 真正共享同一份 Redis 连接
        AiProperties.Redis redisCfg = properties.getRedis();
        if (redisCfg != null && redisCfg.getHost() != null && !redisCfg.getHost().isBlank()
                && jedisPooled != null) {
            Map<String, Object> kvStoreConfig = new LinkedHashMap<>();
            kvStoreConfig.put("type", "redis");
            Map<String, Object> kvConf = new LinkedHashMap<>();
            kvConf.put("host", redisCfg.getHost());
            kvConf.put("port", redisCfg.getPort());
            // 关键：复用启动期创建的共享 JedisPooled
            kvConf.put("redis_client", jedisPooled);
            kvStoreConfig.put("conf", kvConf);
            configBuilder.kvStoreConfig(kvStoreConfig);
        }

        DeepAgentConfig config = configBuilder.build();
        DeepAgent deepAgent = HarnessFactory.createDeepAgent(card, config, workspace);

        // 框架时序 bug 绕过：手动调 ensureInitialized() 提前触发 lazy 初始化
        // 此时 injectKvStore 已跑过，deepAgent.getKvStore() 非 null
        // TaskPlanningRail.init 会走 shared 路径复用同一 RedisStore
        if (jedisPooled != null) {
            try {
                deepAgent.ensureInitialized();
                log.info("[ruoyi-ai] DeepAgent.ensureInitialized 已提前触发"
                        + "（kvStore={}，与 Checkpointer 共享）",
                        deepAgent.getKvStore() == null
                                ? "null"
                                : deepAgent.getKvStore().getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("[ruoyi-ai] ensureInitialized 提前触发失败: {}", e.getMessage(), e);
            }
        }

        return deepAgent;
    }
}
```

## 五、验证步骤

### 5.1 启动验证

启动应用，确认日志含：

```
[ruoyi-ai] Redis PING 127.0.0.1:6379 -> PONG
[ruoyi-ai] RedisCheckpointer 已注册为默认 checkpointer: 127.0.0.1:6379 (ttl=10080.0min)
[ruoyi-ai] DeepAgent.ensureInitialized 已提前触发（kvStore=RedisStore，与 Checkpointer 共享）
```

### 5.2 端到端 AI 查询验证

发一个多步任务请求，明确要求 LLM 用 TodoWrite 规划任务：

```bash
curl -b cookies.txt -X POST 'http://127.0.0.1:8080/ai/chat/send' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'query=请帮我完成多步任务：1) 查询北京天气 2) 查询上海天气 3) 对比两个城市温度。请先用 TodoWrite 工具规划任务清单，再逐步执行。' \
  -d 'modelId=102'
```

预期 AI 会调用 `todo_create` 创建 3 个任务，逐步 `todo_modify` 更新状态。

### 5.3 Redis 持久化验证

#### 方式一：redis-cli 直接查询

```bash
$ redis-cli -h 127.0.0.1 -p 6379 KEYS 'admin:*'
1) "admin:default:todo"                                              # Todo 持久化
2) "admin:ruoyi_ai_admin_XXX:agent:ruoyi_ai_deep_agent_102:agent_state_blobs"  # Checkpointer state
3) "admin:ruoyi_ai_admin_XXX:agent:ruoyi_ai_deep_agent_102:agent_state_blobs_dump_type"

$ redis-cli -h 127.0.0.1 -p 6379 STRLEN "admin:default:todo"
(integer) 1326

$ redis-cli -h 127.0.0.1 -p 6379 GET "admin:default:todo" | head -c 200
[{"id":"2443c331-...","content":"查询北京天气","status":"COMPLETED","result_summary":"北京：零星小雨，+28°C，湿度69%","..."}]
```

#### 方式二：MONITOR 实时观察

```bash
$ timeout 60 redis-cli -h 127.0.0.1 -p 6379 MONITOR > /tmp/redis.log &
$ # 在另一终端发 AI 查询请求
$ grep '"SET"\|"GET"' /tmp/redis.log | grep -E 'todo|state_blobs'

1785256924.459580 "SET" "admin:default:todo" "[{\"id\":\"2443c331-...\",...}]"
1785256939.404047 "SET" "admin:default:todo" "[...\"status\":\"COMPLETED\",...]"
1785256982.010555 "SET" "admin:ruoyi_ai_admin_XXX:agent:ruoyi_ai_deep_agent_102:agent_state_blobs_dump_type" "java"
```

**关键证据**：Checkpointer 与 Todo 写入同一 Redis 实例，key 前缀不同（`{tenantId}:{sessionId}:agent:...` vs `{tenantId}:{sessionId}:todo`），共存不冲突。

## 六、Key 命名规则

| 数据类型 | Key 格式 | TTL | 写入方 |
|----------|----------|-----|--------|
| Checkpointer state | `{tenantId}:{sessionId}:agent:{agentId}:agent_state_blobs` | 配置的 default_ttl | `RedisCheckpointer.postAgentExecute` |
| Checkpointer dump type | `{tenantId}:{sessionId}:agent:{agentId}:agent_state_blobs_dump_type` | 配置的 default_ttl | 同上 |
| Todo | `{tenantId}:{sessionId}:todo` | -1（永久，KvTodoStorage 不设 TTL） | `KvTodoStorage.save` |

**注意**：sessionId 字段目前 LLM 不传时默认 `"default"`，导致 Todo key 是 `{tenantId}:default:todo` 而非真实 conversationId。多会话会串数据，详见 `doc/sharedKvStore字段分析.md` 第四节。

## 七、故障排查

### 7.1 启动期 Redis 连接失败

**日志**：
```
[ruoyi-ai] Redis 检查点初始化失败（127.0.0.1:6379），回退到 InMemoryCheckpointer
```

**排查**：
1. `redis-cli -h 127.0.0.1 -p 6379 ping` 确认 Redis 在线
2. 检查 `application.yml` 的 `ai.redis.host/port` 配置
3. 检查防火墙/网络

应用启动不受影响，但会话状态不持久化。

### 7.2 Todo 没存 Redis（只有 Checkpointer state）

**症状**：`redis-cli KEYS '*:todo'` 返回空，但 `*:agent_state_blobs` 有

**可能原因**：
1. 没配 `.enableTaskPlanning(true)` → LLM 工具列表里没有 todo_create
2. 没配 `.todoStorageType("kv")` → Todo 走 file 模式
3. 没调 `deepAgent.ensureInitialized()` → 时序 bug 导致 Todo 走 InMemoryKVStore
4. AI 查询里没明确要求 LLM 用 TodoWrite → LLM 直接调 executeCmd 不规划

**排查**：看启动日志是否含 `kvStore=RedisStore` 行；看 server log 是否含 `tool_call: todo_create` 行

### 7.3 bash 工具杀子进程导致 Java 进程被 SIGTERM

**症状**：用 `nohup java -jar ... & disown` 启动，bash 命令结束后 java 进程消失

**原因**：`nohup` 只忽略 SIGHUP，不忽略 SIGTERM。bash 工具调用结束时给整个进程组发 SIGTERM

**解决**：用 `setsid -f` 让 java 真正脱离 process group：

```bash
setsid -f bash -c 'java -jar ruoyi-admin.jar --shiro.user.captchaEnabled=false > running.log 2>&1' < /dev/null
```

### 7.4 `pkill -f 'ruoyi-admin.jar'` 自匹配 bash 自身

**症状**：`pkill` 命令把当前 bash 自身也 kill 掉，120s 超时

**原因**：`pkill -f` 匹配整个命令行，bash -c 'pkill ... ruoyi-admin.jar' 命令行包含 'ruoyi-admin.jar' 字符串

**解决**：用更精确的模式，只匹配 java 进程：

```bash
for pid in $(pgrep -f '^java -jar ruoyi-admin'); do kill -9 "$pid"; done
```

### 7.5 conversationId 不一致（DeepAgent 内部 vs controller 返回）

**症状**：curl 返回 `conversationId=1785253091745`，但 Redis 里 key 是 `1785253081747`

**原因**：`AiChatController.send()` 中 chat() 内部生成一个 conversationId 存到 Redis，controller 给前端又重新生成另一个

**解决**：让 chat() 返回 conversationId 给 controller，controller 用同一个返回给前端

## 八、依赖关系图

```
┌─────────────────────────────────────────────────────────────────┐
│ AiConfiguration（启动期 @PostConstruct）                       │
│                                                                 │
│   setupRedisCheckpointer()                                      │
│   ├─ jedisPooled = new JedisPooled(host, port)  ← 共享客户端    │
│   ├─ sharedRedisStore = new RedisStore(jedisPooled)             │
│   └─ CheckpointerFactory.setDefaultCheckpointer(               │
│          new RedisCheckpointer(sharedRedisStore, ttl))          │
│                                                                 │
│   buildDeepAgent(model, agentId)                                │
│   ├─ DeepAgentConfig.kvStoreConfig = {                         │
│   │     type: "redis",                                          │
│   │     conf: { host, port, redis_client: jedisPooled }  ← 复用 │
│   │   }                                                         │
│   ├─ DeepAgent deepAgent = HarnessFactory.createDeepAgent(...) │
│   │   ├─ new DeepAgent(...)                                     │
│   │   ├─ injectKvStore(agent, config)                           │
│   │   │   └─ KVStoreFactory.create("redis", kvConf)             │
│   │   │       └─ RedisKVStoreProvider.resolveRedisClient(kvConf)│
│   │   │           └─ kvConf["redis_client"] 非 null → 返回      │
│   │   │              jedisPooled（复用，不反射创建新 Jedis）    │
│   │   │       └─ new RedisStore(jedisPooled) → agent.kvStore    │
│   │   └─ return agent                                           │
│   │                                                             │
│   └─ deepAgent.ensureInitialized()  ← 手动提前触发              │
│       └─ TaskPlanningRail.init(this)                            │
│           └─ buildTodoStorageConfig(deepAgent, "kv")            │
│               └─ deepAgent.getKvStore() 非 null                 │
│                   → conf["sharedKvStore"] = agent.kvStore       │
│           └─ KvTodoStorageProvider.create(conf)                  │
│               └─ new KvTodoStorage(agent.kvStore) ← 复用同一份  │
└─────────────────────────────────────────────────────────────────┘

                          ↓ 运行期 ↓

┌─────────────────────────┐       ┌──────────────────────────────┐
│ Checkpointer 路径       │       │ Todo 路径                     │
│                         │       │                              │
│ AgentSessionApi.preRun  │       │ LLM tool_call todo_create    │
│   ↓                     │       │   ↓                          │
│ CheckpointerFactory     │       │ TaskPlanningRail             │
│   .getCheckpointer()     │       │   .todoTool.create(...)      │
│   ↓                     │       │   ↓                          │
│ RedisCheckpointer       │       │ KvTodoStorage.save(...)      │
│   (sharedRedisStore)    │       │   (agent.kvStore)            │
│   ↓                     │       │   ↓                          │
│ RedisStore(jedisPooled) │       │ RedisStore(jedisPooled)      │
│         ↓               │       │         ↓                    │
│         └──→ 同一 Redis ←──────┘                              │
└─────────────────────────┘       └──────────────────────────────┘
```

## 九、关键点总结

1. **共享 JedisPooled 是核心**：Checkpointer 和 Todo 都包装同一份 `jedisPooled`（线程安全连接池），连同一 Redis
2. **`redis_client` 字段是关键技巧**：通过 `kvStoreConfig.conf.redis_client = jedisPooled` 让 `RedisKVStoreProvider` 复用而非反射创建新 Jedis（避免非线程安全单 Jedis + 孤儿连接泄漏）
3. **手动调 `ensureInitialized()` 绕过框架时序 bug**：保证 `TaskPlanningRail.init` 走 shared 路径（虽然 lazy 调用理论上也能生效，但实测需手动调）
4. **DeepAgent.close() 不会关 kvStore**：只 stop `tmpFileCleaner`，共享 `jedisPooled` 由应用层 `@PreDestroy` 关闭
5. **TTL 只对 Checkpointer 生效**：`KvTodoStorage.save` 不设 TTL，Todo 永久保留；如需 TTL 需应用层定期清理

## 十、参考

- 文档：`agent-core-java/documents/zh/2.开发指南/高阶用法/多租户数据隔离.md` 章节「共享 KV 实例机制」
- 实现：RuoYi `ruoyi-ai/src/main/java/com/ruoyi/ai/config/AiConfiguration.java`
- 配置：RuoYi `ruoyi-admin/src/main/resources/application.yml` 的 `ai.redis` 块
- 关联分析：`doc/sharedKvStore字段分析.md`（sharedKvStore 字段用途分析）
- 集成经验：`doc/共享Redis-Todo与Checkpointer-集成经验总结.md`（耗时与失败方案分析）
