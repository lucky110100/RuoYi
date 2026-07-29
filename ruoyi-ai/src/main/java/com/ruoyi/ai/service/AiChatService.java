package com.ruoyi.ai.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.openjiuwen.core.multitenant.TenantContext;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.harness.deep_agent.DeepAgent;

/**
 * AI 对话 Service：包装 DeepAgent，提供简洁的对话接口。
 * <p>
 * 多用户隔离：以登录用户名作为 tenant_id，借助 DeepAgent 的多租户能力实现会话隔离。
 * <p>
 * 多轮会话状态恢复：依赖 DeepAgent 内置的 {@code InMemoryCheckpointer}
 * （由 {@code AgentSessionApi.preRun} 在调用前恢复状态、{@code postRun} 在调用后
 * 保存状态，按 {@code tenantId:sessionId:agentId} 在内部 map 持久）。
 * 只要保证 {@code conversationId + tenantId} 一致即可自动续接上下文，
 * 不再维护外部 sessionStateCache。
 * <p>
 * 模型选择：每次调用传入 {@code modelId}，由 {@link DeepAgentRegistry} 取对应实例；
 * 前端切换模型时清空 conversationId，避免跨模型复用 session 状态。
 *
 * @author ruoyi
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final DeepAgentRegistry registry;

    public AiChatService(DeepAgentRegistry registry) {
        this.registry = registry;
    }

    /**
     * 发送一条消息并获取回复。
     *
     * @param username       登录用户名（作为租户 ID）
     * @param conversationId 会话 ID；为空时按用户+时间生成
     * @param query          用户提问
     * @param modelId        模型主键（必填，对应 sys_ai_model.model_id）
     * @return Agent 回复文本
     */
    @SuppressWarnings("unchecked")
    public String chat(String username, String conversationId, String query, Long modelId) {
        if (query == null || query.isBlank()) {
            return "提问内容不能为空";
        }
        if (modelId == null) {
            return "请选择模型";
        }
        if (username == null || username.isBlank()) {
            username = "anonymous";
        }

        DeepAgent deepAgent = registry.getOrCreate(modelId);

        String convId = (conversationId == null || conversationId.isBlank())
                ? "ruoyi_ai_" + username + "_" + System.currentTimeMillis()
                : conversationId;

        TenantContext tenantCtx = TenantContext.builder().tenantId(username).build();

        try {
            // 用 2-arg invoke(inputs, tenantCtx)：DeepAgent 内部创建 session、
            // 自动调用 checkpointer.preAgentExecute 恢复状态、postAgentExecute 保存状态。
            Map<String, Object> agentInputs = new LinkedHashMap<>();
            agentInputs.put("query", query);
            agentInputs.put("conversation_id", convId);

            Map<String, Object> agentResult = (Map<String, Object>) deepAgent.invoke(agentInputs, tenantCtx);

            String output = String.valueOf(agentResult.getOrDefault("output", ""));
            log.info("[ruoyi-ai] 用户={} 模型={} 会话={} 提问={} 回复长度={}",
                    username, modelId, convId,
                    query.length() > 50 ? query.substring(0, 50) + "..." : query, output.length());
            return output;
        } catch (Exception e) {
            log.error("[ruoyi-ai] 对话异常: user={}, modelId={}, convId={}", username, modelId, convId, e);
            return "AI 处理失败：" + e.getMessage();
        }
    }

    /**
     * 清除指定会话的缓存（切换模型时调用，避免跨模型串话）。
     * <p>
     * 状态由 InMemoryCheckpointer 维护，调用 {@link Checkpointer#release} 释放。
     */
    public void clearSession(String conversationId) {
        if (conversationId != null) {
            try {
                com.openjiuwen.core.session.checkpointer.CheckpointerFactory.getCheckpointer()
                        .release(conversationId);
                log.info("[ruoyi-ai] 释放会话 checkpointer 状态: convId={}", conversationId);
            } catch (Exception e) {
                log.warn("[ruoyi-ai] 释放会话状态失败: convId={}, err={}", conversationId, e.getMessage());
            }
        }
    }

    /**
     * 启动一次流式对话。
     * <p>
     * 利用 DeepAgent 自带的会话恢复机制（{@code AgentSessionApi.preRun} 调用
     * {@code CheckpointerFactory.getCheckpointer().preAgentExecute} 恢复状态，
     * {@code postRun} 调用 {@code postAgentExecute} 保存状态；按
     * {@code tenantId:sessionId:agentId} 在 {@code InMemoryCheckpointer} 内部 map 持久）。
     * 调用方只需保证同一 {@code conversationId + tenantId}，即可自动恢复上轮上下文。
     *
     * @param username       登录用户名（作为租户 ID）
     * @param conversationId 会话 ID；为空时按用户+时间生成
     * @param query          用户提问
     * @param modelId        模型主键
     * @return 流会话句柄
     */
    public StreamSession streamSession(String username, String conversationId, String query, Long modelId) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("提问内容不能为空");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("请选择模型");
        }
        if (username == null || username.isBlank()) {
            username = "anonymous";
        }

        DeepAgent deepAgent = registry.getOrCreate(modelId);

        String convId = (conversationId == null || conversationId.isBlank())
                ? "ruoyi_ai_" + username + "_" + System.currentTimeMillis()
                : conversationId;

        TenantContext tenantCtx = TenantContext.builder().tenantId(username).build();

        Map<String, Object> agentInputs = new LinkedHashMap<>();
        agentInputs.put("query", query);
        agentInputs.put("conversation_id", convId);

        // 用 2-arg stream(inputs, tenantCtx)：由 DeepAgent 内部创建 session、
        // 自动调用 checkpointer.preAgentExecute 恢复状态、postAgentExecute 保存状态。
        Iterator<Object> iterator = deepAgent.stream(agentInputs, tenantCtx);
        log.info("[ruoyi-ai] 流式对话开始: user={}, modelId={}, convId={}", username, modelId, convId);
        return new StreamSession(iterator, convId, modelId);
    }

    /**
     * 从流事件中提取可展示的文本增量。
     * <p>
     * 仅消费 {@code llm_output}（LLM 真实输出 chunk）与 {@code answer}（终态完整输出）的
     * {@code content} 字段，忽略 reasoning/usage/error 等内部事件，避免把思维链噪声推到前端。
     * <p>
     * 重复抑制：{@code answer} 事件携带的是整段最终输出，若此前已通过 {@code llm_output}
     * 流过增量，再次发送会造成前端"1+1=2" + "1+1=2" 的重复展示。调用方传入
     * {@code alreadyStreamed=true} 时跳过 {@code answer} 的整段输出；为 {@code false} 时
     * 仍允许把 answer 当成单条 chunk 发出（兼容非流式 LLM 只产生 answer 事件的场景）。
     *
     * @param event          流事件
     * @param alreadyStreamed 是否已经发送过 llm_output 增量
     * @return 文本增量；返回 null 表示该事件不产出可展示文本
     */
    public static String extractStreamContent(Object event, boolean alreadyStreamed) {
        if (!(event instanceof OutputSchema os)) {
            return null;
        }
        String type = os.getType();
        Object payload = os.getPayload();
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        // 终态 answer 事件：仅在尚未流过增量时才发出整段 output，避免重复
        if ("answer".equals(type)) {
            if (alreadyStreamed) {
                return null;
            }
            Object output = map.get("output");
            return output instanceof String s && !s.isBlank() ? s : null;
        }
        if ("llm_output".equals(type)) {
            Object content = map.get("content");
            return content instanceof String s ? s : null;
        }
        if ("error".equals(type)) {
            Object content = map.get("content");
            return content instanceof String s && !s.isBlank() ? s : null;
        }
        return null;
    }

    /**
     * 流会话句柄：聚合迭代器与元信息。
     * <p>
     * 状态保存由 DeepAgent 内置 InMemoryCheckpointer 在迭代结束时自动完成
     * （{@code effectiveSession.postRun()} → {@code postAgentExecute(inner)} →
     * {@code agentStore.save(inner)}），无需调用方介入。
     */
    public class StreamSession {
        private final Iterator<Object> iterator;
        private final String convId;
        private final Long modelId;

        StreamSession(Iterator<Object> iterator, String convId, Long modelId) {
            this.iterator = iterator;
            this.convId = convId;
            this.modelId = modelId;
        }

        public Iterator<Object> getIterator() {
            return iterator;
        }

        public String getConvId() {
            return convId;
        }

        public Long getModelId() {
            return modelId;
        }
    }
}
