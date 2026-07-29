package com.ruoyi.ai.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.checkpointer.Checkpointer;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.sysop.SysOperation;
import com.openjiuwen.core.sysop.SysOperationCard;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.config.LocalWorkConfig;
import com.openjiuwen.extensions.checkpointer.redis.RedisCheckpointer;
import com.openjiuwen.extensions.store.kv.RedisStore;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.SkillUseRail;
import com.openjiuwen.harness.rails.SysOperationRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.ruoyi.ai.domain.SysAiModel;

import redis.clients.jedis.JedisPooled;

/**
 * AI 模块自动装配：负责技能解压与提供 DeepAgent 构建能力。
 * <p>
 * 原 {@code @Bean DeepAgent deepAgent()} 单例已移除——改为由
 * {@link com.ruoyi.ai.service.DeepAgentRegistry} 按模型 ID 懒构建并缓存，
 * 支持问答页面运行期切换不同模型。模型配置来自数据库 {@code sys_ai_model} 表。
 * <p>
 * 参考 {@code examples/deep_agent/DeepAgentA2AServer.createDeepAgent()} 的实现。
 *
 * @author ruoyi
 */
@Configuration
public class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    private final AiProperties properties;

    /**
     * JedisPooled 客户端实例；当 ai.redis.host 未配置或初始化失败时为 null。
     * 在 @PreDestroy 中关闭以释放连接池。
     */
    private JedisPooled jedisPooled;

    /**
     * 共享 RedisStore 实例；与 Checkpointer 共用同一份 Redis 连接。
     * <p>
     * DeepAgent 内部 Checkpointer 走 {@link CheckpointerFactory#getCheckpointer()}（启动时已注册
     * 包装此 RedisStore 的 {@link RedisCheckpointer}）；Todo 走 {@code deepAgent.getKvStore()}
     * （{@link #buildDeepAgent} 中通过 {@code setKvStore(sharedRedisStore)} 注入）。
     * 两者复用同一份 RedisStore / JedisPooled，避免重复 Redis 连接（文档「共享 KV 实例机制」）。
     * <p>
     * DeepAgent.close() 只 stop tmpFileCleaner，不关闭 kvStore，故共享实例不会被单个 agent 关闭；
     * 应用 shutdown 时由 {@link #destroy()} 关闭 JedisPooled。
     */
    private RedisStore sharedRedisStore;

    public AiConfiguration(AiProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        // 解压内置 skill 到运行目录，便于 SkillUseRail 加载
        extractBundledSkills();
        // 注册 Redis 检查点（DeepAgent 内置 RedisCheckpointer），失败则回退到 InMemory
        setupRedisCheckpointer();
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
     * DeepAgent 调用 {@code CheckpointerFactory.getCheckpointer()} 获取默认 checkpointer；
     * 此方法将 {@link RedisCheckpointer}（基于 {@link RedisStore}）设为默认实例，使会话状态持久化到
     * Redis 而非进程内存。
     * <p>
     * 共享的 {@link RedisStore} 实例由 {@link #buildSharedRedisStore(AiProperties.Redis)} 构造，
     * 同时通过 {@code DeepAgentConfig.kvStoreConfig.conf.redis_client} 让 Todo 复用，
     * 实现 Checkpointer 与 Todo 共享同一份 Redis 连接（文档「共享 KV 实例机制」）。
     * <p>
     * 任何异常（host 未配置、连接失败）都被吞掉，回退到 InMemoryCheckpointer，应用仍可启动。
     */
    private void setupRedisCheckpointer() {
        AiProperties.Redis cfg = properties.getRedis();
        if (cfg == null || cfg.getHost() == null || cfg.getHost().isBlank()) {
            log.warn("[ruoyi-ai] ai.redis.host 未配置，DeepAgent checkpointer 回退到 InMemoryCheckpointer（重启会话状态丢失）");
            return;
        }

        try {
            sharedRedisStore = buildSharedRedisStore(cfg);

            // TTL：可选，null 表示永久保留
            Map<String, Object> ttlMap = null;
            if (cfg.getDefaultTtlMinutes() != null) {
                ttlMap = new LinkedHashMap<>();
                ttlMap.put("default_ttl", cfg.getDefaultTtlMinutes());
                ttlMap.put("refresh_on_read", true);
            }

            RedisCheckpointer redisCheckpointer = new RedisCheckpointer(sharedRedisStore, ttlMap);
            CheckpointerFactory.setDefaultCheckpointer(redisCheckpointer);
            log.info("[ruoyi-ai] RedisCheckpointer 已注册为默认 checkpointer（共享 RedisStore 同时服务 Todo）: {}:{} (ttl={}min)",
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
            log.warn("[ruoyi-ai] Redis 检查点初始化失败（{}:{}），回退到 InMemoryCheckpointer。原因: {}",
                    cfg.getHost(), cfg.getPort(), e.getMessage(), e);
        }
    }

    /**
     * 构造共享 RedisStore 实例（含 JedisPooled 客户端创建 + 连通性探测）。
     * <p>
     * 创建的 {@link JedisPooled}（线程安全连接池）同时赋值给 {@link #jedisPooled} 字段，
     * 供 {@link #buildDeepAgent} 通过 {@code kvStoreConfig.conf.redis_client} 注入到 DeepAgent
     * 让 Todo 复用同一份；返回的 {@link RedisStore} 由 {@link #setupRedisCheckpointer} 包装为
     * {@link RedisCheckpointer} 注册到 {@link CheckpointerFactory}。
     * <p>
     * Jedis 5.x 的 {@link JedisPooled} 方法签名（{@code set/get/exists/del/expire/keys/mget}）
     * 与 {@link RedisStore} 反射契约完全兼容，无需适配器代码。
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
     * 构建一个 DeepAgent 实例（不缓存）。
     * <p>
     * 由 {@link com.ruoyi.ai.service.DeepAgentRegistry}（按模型懒加载 + 复用）和
     * {@link com.ruoyi.ai.service.AiModelConnectionTester}（临时实例测试连通）共用。
     * <p>
     * 不同模型必须用不同的 {@code agentId}，避免 {@code Runner.resourceMgr()} 中
     * {@code SysOperation} 注册冲突（原单例代码用固定 "ruoyi_ai_deep_agent"）。
     *
     * @param model   模型配置（来自 sys_ai_model 表）
     * @param agentId AgentCard 标识，需按调用方场景唯一（如 ruoyi_ai_deep_agent_&lt;modelId&gt;）
     * @return DeepAgent 实例（调用方负责 close）
     */
    public DeepAgent buildDeepAgent(SysAiModel model, String agentId) throws Exception {
        AiProperties.Agent agentConf = properties.getAgent();

        log.info("[ruoyi-ai] 初始化 DeepAgent: model={}, provider={}, apiBase={}",
                model.getModelName(), model.getModelProvider(), model.getApiBase());

        AgentCard card = AgentCard.builder()
                .id(agentId)
                .name(agentId)
                .description("RuoYi AI 辅助模块 DeepAgent (model=" + model.getModelName() + ")")
                .build();

        Map<String, Object> modelConfig = buildModelConfig(model);
        Map<String, Object> backendConfig = buildBackendConfig(model);

        // 创建 SysOperation（系统操作工具）
        String workDir = System.getProperty("user.dir");
        SysOperation sysOperation = createSysOperation(agentId, workDir);

        DeepAgentConfig.DeepAgentConfigBuilder configBuilder = DeepAgentConfig.builder()
                .enableTaskLoop(true)
                // 启用 TaskPlanningRail：暴露 todo_create/todo_modify/todo_list/todo_get 工具给 LLM，
                // LLM 在多步任务时主动规划 todo
                .enableTaskPlanning(true)
                .systemPrompt(agentConf.getSystemPrompt())
                .maxIterations(agentConf.getMaxIterations())
                .language("cn")
                .model(modelConfig)
                .backend(backendConfig)
                .restrictToWorkDir(false)
                .sysOperation(sysOperation)
                .enableTenantIsolation(true)
                .tenantDataRoot(properties.getTenantDataRoot())
                // Todo 存储切到 KV 模式：TaskPlanningRail.init 在 DeepAgent 构造期执行，
                // 通过 deepAgent.getKvStore() 取共享 kvStore（由下方 kvStoreConfig 在
                // HarnessFactory.injectKvStore 时注入）。与 Checkpointer 共用同一 Redis 连接
                .todoStorageType("kv")
                .rails(List.of(
                        new SkillUseRail(properties.getSkillsDir()),
                        new SysOperationRail()));
                

        // 配置 KV 存储：让 HarnessFactory.injectKvStore 在 DeepAgent 构造期创建 RedisStore 并
        // agent.setKvStore(kvStore)，使 TaskPlanningRail.init 拿到非 null 的 kvStore。
        // 通过 redis_client 字段传入共享的 jedisPooled（线程安全连接池），让 RedisKVStoreProvider
        // 复用同一份客户端，不反射创建单 Jedis（非线程安全）。最终 Checkpointer（CheckpointerFactory）
        // 和 Todo（deepAgent.getKvStore()）包装同一份 JedisPooled，连同一 Redis，文档「共享 KV 实例机制」
        AiProperties.Redis redisCfg = properties.getRedis();
        if (redisCfg != null && redisCfg.getHost() != null && !redisCfg.getHost().isBlank()
                && jedisPooled != null) {
            Map<String, Object> kvStoreConfig = new LinkedHashMap<>();
            kvStoreConfig.put("type", "redis");
            Map<String, Object> kvConf = new LinkedHashMap<>();
            kvConf.put("host", redisCfg.getHost());
            kvConf.put("port", redisCfg.getPort());
            // 复用启动期创建的共享 JedisPooled（线程安全），避免 RedisKVStoreProvider 反射创建
            // 单 Jedis 实例（非线程安全 + 孤儿连接泄漏）
            kvConf.put("redis_client", jedisPooled); // 见 RedisKVStoreProvider >> Object existing = conf.get("redis_client");
            kvStoreConfig.put("conf", kvConf);
            configBuilder.kvStoreConfig(kvStoreConfig);
        }

        DeepAgentConfig config = configBuilder.build();

        Workspace workspace = Workspace.builder()
                .rootPath(workDir)
                .language("cn")
                .build();

        DeepAgent deepAgent = HarnessFactory.createDeepAgent(card, config, workspace);

        // 框架时序修复：HarnessFactory.createDeepAgent 末尾才调 injectKvStore 设置 deepAgent.kvStore，
        // 但 TaskPlanningRail.init（在 DeepAgent.ensureInitialized() 里 lazy 调用）需要读取
        // deepAgent.getKvStore() 走 shared 路径。ensureInitialized 默认在首次 invoke/stream 时才调，
        // 此时 kvStore 已注入，buildTodoStorageConfig 会走 sharedKvStore 路径，复用与 Checkpointer
        // 同一份 RedisStore。提前手动调一次，幂等（isInitialized flag 保护）。
        if (jedisPooled != null) {
            try {
                deepAgent.ensureInitialized();
                Object kv = deepAgent.getKvStore();
                log.info("[ruoyi-ai] DeepAgent.ensureInitialized 已提前触发：kvStore={}（与 Checkpointer 共享）"
                        + " todoStorageType={}（agentId={}）",
                        kv == null ? "null" : kv.getClass().getSimpleName(),
                        config.getTodoStorageType(), agentId);
            } catch (Exception e) {
                log.warn("[ruoyi-ai] DeepAgent.ensureInitialized 提前触发失败: {}", e.getMessage(), e);
            }
        }

        // 手动注入系统操作工具到 Agent 的 AbilityManager（与示例一致）
        injectSysOpTools(deepAgent, agentId);

        log.info("[ruoyi-ai] DeepAgent 初始化完成: agentId={}, skillsDir={}",
                agentId, properties.getSkillsDir());
        return deepAgent;
    }

    /**
     * 解压内置 skill 资源到运行目录。
     * <p>
     * 内置 skill 位于 classpath:skills/，启动时复制到 {@code ai.skillsDir}，便于 SkillUseRail 加载。
     */
    private void extractBundledSkills() {
        try {
            Path targetRoot = Paths.get(properties.getSkillsDir());
            Files.createDirectories(targetRoot);

            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            org.springframework.core.io.Resource[] resources = resolver
                    .getResources("classpath:skills/**/*");

            int copied = 0;
            for (org.springframework.core.io.Resource res : resources) {
                String url = res.getURL().toString();
                int idx = url.indexOf("skills/");
                if (idx < 0) {
                    continue;
                }
                String relative = url.substring(idx + "skills/".length());
                if (relative.isEmpty() || relative.endsWith("/")) {
                    continue;
                }
                Path target = targetRoot.resolve(relative);
                Files.createDirectories(target.getParent());
                try (InputStream in = res.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                copied++;
            }
            log.info("[ruoyi-ai] 内置技能已解压至: {} (共 {} 个文件)", targetRoot, copied);
        } catch (IOException e) {
            log.warn("[ruoyi-ai] 解压内置技能失败（不影响启动，但技能不可用）: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[ruoyi-ai] 解压内置技能异常: {}", e.getMessage());
        }
    }

    /**
     * 创建 SysOperation（与示例 createSysOperation 一致）。
     */
    private SysOperation createSysOperation(String sysOpId, String workDir) {
        Object registered = Runner.resourceMgr().getSysOperation(sysOpId, null, TagMatchStrategy.ALL);
        if (registered instanceof SysOperation existing) {
            return existing;
        }

        SysOperationCard sysOperationCard = SysOperationCard.builder()
                .id(sysOpId)
                .name(sysOpId)
                .mode(OperationMode.LOCAL)
                .workConfig(LocalWorkConfig.builder()
                        .workDir(workDir)
                        .restrictToSandbox(false)
                        .build())
                .build();

        Runner.resourceMgr().addSysOperation(sysOperationCard, sysOpId);

        Object added = Runner.resourceMgr().getSysOperation(sysOpId, null, TagMatchStrategy.ALL);
        return added instanceof SysOperation addedSysOperation
                ? addedSysOperation
                : new SysOperation(sysOperationCard);
    }

    /**
     * 手动注入系统操作工具到 Agent 的 AbilityManager（与示例 injectSysOpTools 一致）。
     */
    private void injectSysOpTools(DeepAgent deepAgent, String sysOpId) {
        Object toolCards = Runner.resourceMgr().getSysOpToolCards(sysOpId, null, null);
        if (toolCards instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof ToolCard toolCard) {
                    deepAgent.getAgent().getAbilityManager().add(toolCard);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildModelConfig(SysAiModel model) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model.getModelName());
        // 注意：stream_options 必须与 stream=true 同时出现，否则 DashScope / OpenAI
        // 兼容接口会直接返回 400 invalid_parameter_error，AgentCore 会静默吞掉
        // 错误响应并让 output 为空，表现为"模型返回空回复"。
        // AgentCore 当前默认 is_stream=false，故此处不要附加 stream_options。
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildBackendConfig(SysAiModel model) {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("client_provider", model.getModelProvider());
        backend.put("api_key", model.getApiKey());
        backend.put("api_base", model.getApiBase());
        // DB 字段 ssl_verify: 0=否 1=是 → AgentCore verify_ssl 是 boolean
        boolean verifySsl = "1".equals(model.getSslVerify());
        backend.put("verify_ssl", verifySsl);
        return backend;
    }
}
