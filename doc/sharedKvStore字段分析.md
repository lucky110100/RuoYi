# sharedKvStore 字段分析：有什么用？是否多余？

> 分析对象：`agent-core-java` 的 `TaskPlanningRail.buildTodoStorageConfig` 中 `conf.put("sharedKvStore", kvStore)` 这一字段
> 关联代码：
> - `TaskPlanningRail.java:189-194`（shared 路径设置 sharedKvStore）
> - `KvTodoStorageProvider.java:25-26`（消费 sharedKvStore 走 shared 分支）
> - `HarnessFactory.java:80-90`（injectKvStore 创建 agent.kvStore）

## 一、问题

`buildTodoStorageConfig` 在 conf 里塞了 `sharedKvStore` 字段让 `KvTodoStorageProvider` 走 shared 分支直接复用 `deepAgent.getKvStore()`。这个字段有什么用？是否多余？

## 二、sharedKvStore 的设计意图

`buildTodoStorageConfig` 有两条路径：

```java
BaseKVStore kvStore = deepAgent.getKvStore();
if (kvStore != null) {
    // shared 路径：复用 agent.kvStore 实例
    conf.put("kvStoreType", "shared");
    conf.put("sharedKvStore", kvStore);     // ← 这个字段
    return conf;
}
// fallback 路径：从 config.kvStoreConfig 重新创建一份
Map<String, Object> kvConf = deepAgent.getConfig().getKvStoreConfig();
if (kvConf != null) {
    conf.put("kvStoreConf", kvConf);
}
return conf;
```

`KvTodoStorageProvider.create(conf)` 消费：

```java
if (conf.get("sharedKvStore") instanceof BaseKVStore) {
    return new KvTodoStorage((BaseKVStore) conf.get("sharedKvStore"));  // 直接复用
}
// 否则从 kvStoreType + kvStoreConf 调 KVStoreFactory.create 重新创建一份
BaseKVStore kvStore = KVStoreFactory.create(kvStoreType, kvStoreConf != null ? kvStoreConf : Map.of());
return new KvTodoStorage(kvStore);
```

**意图**：让 Todo 复用 `injectKvStore` 创建的那份 RedisStore（`agent.kvStore`），而不是 fallback 路径再创建一份。

## 三、三种方案下的分析

| 方案 | shared 路径结果 | fallback 路径结果 | shared 是否多余 |
|------|-----------------|-------------------|-----------------|
| **A. kvConf 含 `redis_client=jedisPooled`（RuoYi 最终方案）** | Todo 用 `RedisStore_A(jedisPooled)` | `RedisKVStoreProvider.resolveRedisClient` 复用 `kvConf["redis_client"]` → `RedisStore_B(jedisPooled)`，**同一 jedisPooled** | **功能多余**（两份 RedisStore 包装同一 jedisPooled，等价） |
| **B. kvConf 仅含 host/port（文档示例）** | Todo 用 `RedisStore_A(单 Jedis_A)` | `RedisKVStoreProvider` 反射创建新单 Jedis_B → `RedisStore_C(Jedis_B)`，**独立连接** | **必需**（fallback 会创建第二份 Jedis 连接，浪费） |
| **C. 不配 kvStoreConfig，手动 setKvStore（曾尝试方案）** | init 时 kvStore 为 null（时序 bug），不走 shared | kvConf 也为 null → `KvTodoStorageProvider` 默认 `in_memory` | **完全用不上**（路径都走不到） |

## 四、结论

### 4.1 设计上是否多余？

**不多余**。在文档推荐的 host/port 配置方案下（方案 B），shared 路径能省一份 Jedis 连接，必需。

### 4.2 在 RuoYi 实际方案下是否多余？

**功能上多余**。RuoYi 用 `redis_client=jedisPooled` 复用方案（方案 A），fallback 路径也会复用同一 `jedisPooled`，shared 路径仅省一个 RedisStore 对象（开销极小）。

### 4.3 shared 路径生效的前提

需要 `TaskPlanningRail.init` 在 `injectKvStore` **之后**跑，此时 `deepAgent.getKvStore()` 才非 null。

但 agent-core-java 0.1.13 的执行时序：

```
HarnessFactory.createDeepAgent:
  line 75: new DeepAgent(...)              # 构造函数不调 ensureInitialized
  line 76: injectKvStore(agent, config)    # 此时才设 agent.kvStore
  return agent                             # ensureInitialized 还没跑

DeepAgent.ensureInitialized():             # 在首次 invoke/stream 时才调
  iterate config.rails:
    TaskPlanningRail.init(this)            # 此时读 deepAgent.getKvStore() —— 非 null ✓
```

理论上 lazy 调用 ensureInitialized 时 kvStore 已注入，shared 路径生效。但实测不提前调 `ensureInitialized()` 时 todo 仍走 in_memory（原因未完全定位，可能 ensureInitialized 内有其他覆盖）。RuoYi 用手动提前调 `ensureInitialized()` 保证 shared 路径生效。

### 4.4 框架层应优化（让 shared 字段真正多余化）

`buildTodoStorageConfig` 的 fallback 路径有 bug：只把 `kvStoreConf` 放进 conf，**没把 `kvStoreType` 也放进去**，导致 `KvTodoStorageProvider` 默认走 `in_memory`：

```java
// 现状（TaskPlanningRail.java:195-199）
Map<String, Object> kvConf = deepAgent.getConfig().getKvStoreConfig();
if (kvConf != null) {
    conf.put("kvStoreConf", kvConf);       // ← 只放 kvStoreConf
}
// 缺：conf.put("kvStoreType", kvConf.get("type"));
//     让 KvTodoStorageProvider 走 redis 而非默认 in_memory
```

修了这个 bug 后，即使 shared 路径不生效（时序问题），fallback 也能正确走 redis 类型创建 RedisStore，shared 路径就**真正多余**了——可以删掉简化代码。

## 五、给 agent-core-java 框架的建议

1. **修 `buildTodoStorageConfig` 的 fallback bug**：补 `conf.put("kvStoreType", kvConf.get("type"))`，让 fallback 路径能正确走 redis 类型
2. **修时序 bug**：`HarnessFactory.createDeepAgent` 改为 `injectKvStore` 在 `ensureInitialized` 之前调（已经是），并在 createDeepAgent 末尾调一次 `ensureInitialized()` 让 rails 提前注册，避免 lazy 调用导致的时序歧义
3. **如果上述两条都修了**：`sharedKvStore` 字段可以删除，`buildTodoStorageConfig` 简化为只走 fallback 路径，Todo 始终用 `KVStoreFactory.create` 创建新的 kvStore 实例（同一 kvConf 会复用同一 redis_client，不浪费连接）

## 六、给 RuoYi 应用层的建议

在框架未修复前，RuoYi 当前方案（含 `redis_client=jedisPooled` + 手动调 `ensureInitialized()`）已经让 shared 路径生效，Todo 复用 `agent.kvStore` 实例。

- 如果未来 agent-core-java 修了 fallback bug，可以**移除手动调 `ensureInitialized()` 的代码**，让 lazy 初始化生效，shared 路径自动走（因为 injectKvStore 在 createDeepAgent 末尾跑，ensureInitialized 在 invoke 时跑，时序正确）
- 如果未来 agent-core-java 同时删了 shared 路径（按建议 3），RuoYi 仍能工作（fallback 路径会复用 jedisPooled），但需移除 `ensureInitialized()` 调用并依赖 lazy 初始化

无论如何，RuoYi 当前的 `redis_client=jedisPooled` 配置是关键——它确保无论走 shared 还是 fallback，都复用同一份线程安全的 jedisPooled，避免反射创建非线程安全的单 Jedis。
