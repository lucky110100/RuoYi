package com.ruoyi.ai.service;

import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.ruoyi.ai.config.AiConfiguration;
import com.ruoyi.ai.domain.SysAiModel;
import com.ruoyi.ai.event.AiModelChangedEvent;

/**
 * DeepAgent 实例缓存与生命周期管理。
 * <p>
 * 因 {@code agent-core-java} 的 {@link DeepAgent} 在构建期固化模型配置（无运行期切换 API），
 * 不同模型对应不同 DeepAgent 实例。本类按模型 ID 懒构建 + 复用，
 * 在模型 CRUD 后通过 {@link AiModelChangedEvent} 失效对应实例。
 *
 * @author ruoyi
 */
@Service
public class DeepAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeepAgentRegistry.class);

    private final ConcurrentHashMap<Long, DeepAgent> cache = new ConcurrentHashMap<>();

    private final ISysAiModelService modelService;
    private final AiConfiguration aiConfiguration;

    public DeepAgentRegistry(ISysAiModelService modelService, AiConfiguration aiConfiguration) {
        this.modelService = modelService;
        this.aiConfiguration = aiConfiguration;
    }

    /**
     * 启动期预热默认模型，保持原启动日志（便于按 AGENTS.md 步骤验证）。
     * 找不到默认模型时仅打印 WARN，不中断启动。
     */
    @PostConstruct
    public void preloadDefault() {
        try {
            SysAiModel m = modelService.selectDefaultModel();
            if (m == null) {
                log.warn("[ruoyi-ai] 无可用默认模型，请到 Models 菜单配置");
                return;
            }
            getOrCreate(m.getModelId());
        } catch (Exception e) {
            log.warn("[ruoyi-ai] 预热默认模型失败（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 获取或构建指定模型的 DeepAgent。
     * <p>
     * 使用 {@code computeIfAbsent} 保证同一 modelId 不会重复构建，
     * 避免 {@code SysOperation} 重复注册。
     *
     * @param modelId 模型主键
     * @return DeepAgent 实例（缓存命中或新构建）
     */
    public DeepAgent getOrCreate(Long modelId) {
        if (modelId == null) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        return cache.computeIfAbsent(modelId, this::buildFor);
    }

    /**
     * 失效指定模型的缓存实例（CRUD 后由事件触发，或测试连通前手动调用）。
     * 先 close 释放底层资源，再从缓存移除。
     */
    public void evict(Long modelId) {
        if (modelId == null) {
            return;
        }
        DeepAgent d = cache.remove(modelId);
        if (d != null) {
            try {
                d.close();
                log.info("[ruoyi-ai] 已失效 DeepAgent 缓存: modelId={}", modelId);
            } catch (Exception e) {
                log.warn("[ruoyi-ai] 关闭 DeepAgent 失败: modelId={}, err={}", modelId, e.getMessage());
            }
        }
    }

    /**
     * 全量失效（刷新缓存按钮、容器关闭时调用）。
     */
    public void evictAll() {
        cache.keySet().forEach(this::evict);
    }

    /**
     * 监听模型变更事件，自动失效对应实例。
     */
    @EventListener
    public void onModelChanged(AiModelChangedEvent event) {
        evict(event.getModelId());
    }

    /**
     * 容器销毁时全量关闭，避免资源泄漏。
     */
    @PreDestroy
    public void destroy() {
        evictAll();
    }

    private DeepAgent buildFor(Long modelId) {
        SysAiModel m = modelService.selectModelById(modelId);
        if (m == null) {
            throw new IllegalArgumentException("模型不存在: " + modelId);
        }
        if (!"0".equals(m.getStatus())) {
            throw new IllegalStateException("模型已停用: " + modelId);
        }
        String agentId = "ruoyi_ai_deep_agent_" + modelId;
        try {
            return aiConfiguration.buildDeepAgent(m, agentId);
        } catch (Exception e) {
            throw new IllegalStateException("构建 DeepAgent 失败: modelId=" + modelId
                    + ", err=" + e.getMessage(), e);
        }
    }
}
