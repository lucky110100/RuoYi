package com.ruoyi.ai.event;

import org.springframework.context.ApplicationEvent;

/**
 * LLM 模型配置变更事件。
 * <p>
 * 由 {@code SysAiModelServiceImpl} 在新增/修改/删除后发布，
 * 由 {@code DeepAgentRegistry} 监听以失效对应模型的 DeepAgent 缓存实例。
 * <p>
 * 用事件解耦避免 Service 与 Registry 之间形成循环依赖。
 *
 * @author ruoyi
 */
public class AiModelChangedEvent extends ApplicationEvent
{
    private static final long serialVersionUID = 1L;

    /** 变更涉及的模型 ID；批量删除时为 null（表示全量失效） */
    private final Long modelId;

    public AiModelChangedEvent(Object source, Long modelId)
    {
        super(source);
        this.modelId = modelId;
    }

    public Long getModelId()
    {
        return modelId;
    }
}
