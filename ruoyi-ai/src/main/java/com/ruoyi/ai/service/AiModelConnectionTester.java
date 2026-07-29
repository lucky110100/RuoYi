package com.ruoyi.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.openjiuwen.core.multitenant.TenantContext;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.ruoyi.ai.config.AiConfiguration;
import com.ruoyi.ai.domain.SysAiModel;
import com.ruoyi.common.core.domain.AjaxResult;

import java.util.Map;

/**
 * LLM 模型连通性测试：用临时 DeepAgent 发送轻量 ping 提示词，验证
 * {@code api_base + api_key + model_name} 三者匹配可用。
 *
 * @author ruoyi
 */
@Service
public class AiModelConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(AiModelConnectionTester.class);

    /** 测试用提示词 */
    private static final String PING_PROMPT = "你好";

    private final AiConfiguration aiConfiguration;

    public AiModelConnectionTester(AiConfiguration aiConfiguration) {
        this.aiConfiguration = aiConfiguration;
    }

    /**
     * 测试表单中尚未保存的配置（前端"测试连通"按钮，避免先保存再测）。
     *
     * @param model 表单提交的模型配置
     * @return AjaxResult 含成功/失败 + 样例回复 + 耗时
     */
    public AjaxResult testByModel(SysAiModel model) {
        if (model == null) {
            return AjaxResult.error("模型配置为空");
        }
        long start = System.currentTimeMillis();
        // 用纳秒时间戳构造唯一 AGENT_ID，避免与缓存实例和并发测试冲突
        String tmpAgentId = "ruoyi_ai_test_" + System.nanoTime();
        DeepAgent tmp = null;
        try {
            tmp = aiConfiguration.buildDeepAgent(model, tmpAgentId);
            // DeepAgent 启用了多租户隔离，调用前必须设置 TenantContext
            TenantContext tenantCtx = TenantContext.builder().tenantId("ai_test").build();
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) tmp.invoke(Map.of("query", PING_PROMPT), tenantCtx);
            String reply = String.valueOf(r.getOrDefault("output", ""));
            long cost = System.currentTimeMillis() - start;
            // AgentCore 在 api_key 无效等情况下可能不抛异常，仅返回空 output；
            // 视空回复为连通失败，避免误导用户。
            if (reply.isBlank()) {
                log.warn("[ruoyi-ai] 模型连通测试空回复: model={}, cost={}ms", model.getModelName(), cost);
                return AjaxResult.error("连通失败（耗时 " + cost + "ms）：模型返回空回复，请检查 api_base / api_key / model_name 是否正确");
            }
            log.info("[ruoyi-ai] 模型连通测试成功: model={}, cost={}ms", model.getModelName(), cost);
            return AjaxResult.success("连通成功，耗时 " + cost + "ms").put("reply", reply);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[ruoyi-ai] 模型连通测试失败: model={}, cost={}ms, err={}",
                    model.getModelName(), cost, e.getMessage());
            return AjaxResult.error("连通失败（耗时 " + cost + "ms）：" + e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    tmp.close();
                } catch (Exception e) {
                    log.debug("[ruoyi-ai] 关闭测试实例失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 测试已保存的模型（按 id 取配置）。常用于列表页行内"测试连通"按钮。
     *
     * @param modelId 模型主键
     * @return AjaxResult
     */
    public AjaxResult testById(Long modelId, ISysAiModelService modelService) {
        if (modelId == null) {
            return AjaxResult.error("modelId 不能为空");
        }
        SysAiModel model = modelService.selectModelById(modelId);
        if (model == null) {
            return AjaxResult.error("模型不存在: " + modelId);
        }
        return testByModel(model);
    }
}
