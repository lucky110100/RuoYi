package com.ruoyi.ai.controller;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.SysAiModel;
import com.ruoyi.ai.service.AiChatService;
import com.ruoyi.ai.service.ISysAiModelService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ShiroUtils;

/**
 * AI 辅助模块 Controller。
 * <p>
 * 入口菜单：AI辅助 -> AI问答
 *
 * @author ruoyi
 */
@Controller
@RequestMapping("/ai/chat")
public class AiChatController extends BaseController {

    private static final String PREFIX = "ai/chat";

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private ISysAiModelService modelService;

    /**
     * AI 辅助对话页面
     */
    @RequiresPermissions("ai:chat:view")
    @GetMapping
    public String chat() {
        return PREFIX + "/chat";
    }

    /**
     * 获取启用的模型列表（用于问答页面下拉）
     */
    @RequiresPermissions("ai:chat:view")
    @GetMapping("/models")
    @ResponseBody
    public AjaxResult listEnabledModels() {
        List<SysAiModel> models = modelService.selectEnabledModels();
        // 精简返回字段，避免泄露 api_key
        List<Map<String, Object>> data = models.stream().map(m -> {
            Map<String, Object> item = new HashMap<>();
            item.put("modelId", m.getModelId());
            item.put("modelName", m.getModelName());
            item.put("modelVersion", m.getModelVersion());
            item.put("modelType", m.getModelType());
            item.put("isDefault", m.getIsDefault());
            return item;
        }).toList();
        return AjaxResult.success(data);
    }

    /**
     * 发送对话请求
     *
     * @param query          用户提问
     * @param conversationId 会话 ID（可选，支持多轮对话）
     * @param modelId        模型主键（必填）
     */
    @RequiresPermissions("ai:chat:send")
    @PostMapping("/send")
    @ResponseBody
    public AjaxResult send(@RequestParam("query") String query,
                           @RequestParam(value = "conversationId", required = false) String conversationId,
                           @RequestParam("modelId") Long modelId) {
        if (query == null || query.isBlank()) {
            return AjaxResult.error("提问内容不能为空");
        }
        if (modelId == null) {
            return AjaxResult.error("请选择模型");
        }
        String username = ShiroUtils.getLoginName();
        String reply = aiChatService.chat(username, conversationId, query, modelId);
        AjaxResult result = AjaxResult.success(reply);
        String convId = (conversationId == null || conversationId.isBlank())
                ? "ruoyi_ai_" + username + "_" + System.currentTimeMillis()
                : conversationId;
        result.put("conversationId", convId);
        result.put("modelId", modelId);
        return result;
    }

    /**
     * 流式发送对话请求（SSE）。
     * <p>
     * 响应 content-type 为 {@code text/event-stream}，事件类型：
     * <ul>
     *   <li>{@code meta} - 会话元信息（conversationId / modelId），首个事件</li>
     *   <li>{@code chunk} - LLM 输出增量文本，前端按到达顺序追加</li>
     *   <li>{@code error} - 异常信息，前端展示后终止</li>
     *   <li>{@code done} - 流结束标记</li>
     * </ul>
     * <p>
     * 用 Servlet 原生 {@link AsyncContext} 直接写 SSE 而非 Spring 的 SseEmitter：
     * SseEmitter 在 async dispatch 时会触发 Spring FrameworkServlet 的
     * publishRequestHandledEvent，进而调用 request.getSession()，而 Shiro 的
     * ShiroHttpServletRequest 在 dispatch 线程没有绑定 SecurityManager，会抛
     * UnavailableSecurityManagerException 污染日志。AsyncContext 直接写响应
     * 不会触发 Spring 的 async dispatch 回调，避开 Shiro + Spring async 的兼容问题。
     */
    @RequiresPermissions("ai:chat:send")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@RequestParam("query") String query,
                       @RequestParam(value = "conversationId", required = false) String conversationId,
                       @RequestParam("modelId") Long modelId,
                       HttpServletRequest request, HttpServletResponse response) {
        // 启用 Servlet 异步：释放容器线程，由工作线程持留连接写 SSE
        AsyncContext asyncCtx = request.startAsync();
        // 流式 LLM 单次通常 30~60s，留足超时余量（单位：毫秒）
        asyncCtx.setTimeout(180_000L);

        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        String username = ShiroUtils.getLoginName();
        Thread worker = new Thread(() -> {
            PrintWriter writer = null;
            AiChatService.StreamSession ss = null;
            try {
                writer = response.getWriter();
                if (query == null || query.isBlank()) {
                    writeSse(writer, "error", "提问内容不能为空");
                    flush(writer);
                    return;
                }
                if (modelId == null) {
                    writeSse(writer, "error", "请选择模型");
                    flush(writer);
                    return;
                }
                ss = aiChatService.streamSession(username, conversationId, query, modelId);
                // 首事件：下发会话元信息，前端据此更新 conversationId 支持多轮
                Map<String, Object> meta = new HashMap<>();
                meta.put("conversationId", ss.getConvId());
                meta.put("modelId", ss.getModelId());
                writeSse(writer, "meta", META_JSON.writeValueAsString(meta));

                java.util.Iterator<Object> it = ss.getIterator();
                boolean anyStreamed = false;
                while (it.hasNext()) {
                    // 已流式输出过 llm_output 增量时，跳过 answer 终态完整输出，避免重复展示
                    String chunk = AiChatService.extractStreamContent(it.next(), anyStreamed);
                    if (chunk != null) {
                        anyStreamed = true;
                        writeSse(writer, "chunk", chunk);
                        flush(writer);
                    }
                }
                writeSse(writer, "done", "");
                flush(writer);
            } catch (Exception e) {
                // 客户端断开：Broken pipe / AsyncRequestNotUsableException / ClientAbortException
                // 此时连接已死，直接结束工作线程，不再尝试写响应或触发 async dispatch
                if (isClientAbort(e)) {
                    logger.debug("[ruoyi-ai] 流式对话客户端断开: user={}, modelId={}", username, modelId);
                } else {
                    logger.error("[ruoyi-ai] 流式对话异常: user={}, modelId={}", username, modelId, e);
                    try {
                        if (writer != null) {
                            writeSse(writer, "error",
                                    "AI 处理失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                            flush(writer);
                        }
                    } catch (Exception ignore) {
                        // 客户端可能已断开
                    }
                }
            } finally {
                // 状态由 DeepAgent 内置 InMemoryCheckpointer 在迭代结束时自动保存
                // （effectiveSession.postRun() → postAgentExecute → agentStore.save），
                // 无需手动 complete。
                asyncCtx.complete();
            }
        }, "ai-chat-stream-" + (username == null ? "anon" : username));
        worker.setDaemon(true);
        worker.start();
    }

    private static final ObjectMapper META_JSON = new ObjectMapper();

    private static void writeSse(PrintWriter w, String eventName, String data) {
        // SSE 规范：data 内的换行需用多个 data: 行表达
        String[] lines = data == null ? new String[] {""} : data.split("\n", -1);
        w.write("event:");
        w.write(eventName);
        w.write("\n");
        for (String line : lines) {
            w.write("data:");
            if (!line.isEmpty()) {
                w.write(line);
            }
            w.write("\n");
        }
        w.write("\n");
    }

    private static void flush(PrintWriter w) {
        if (w != null) {
            w.flush();
        }
    }

    private static boolean isClientAbort(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            String name = cur.getClass().getSimpleName();
            if (cur instanceof java.io.IOException && cur.getMessage() != null
                    && cur.getMessage().toLowerCase().contains("broken pipe")) {
                return true;
            }
            if ("ClientAbortException".equals(name) || "AsyncRequestNotUsableException".equals(name)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
