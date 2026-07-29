package com.ruoyi.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模块配置属性（对应 application.yml 中 ai.* 配置）。
 * <p>
 * 注：原 {@code ai.llm.*} 配置已迁移到数据库 {@code sys_ai_model} 表，由 Models 菜单管理。
 *
 * @author ruoyi
 */
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /**
     * DeepAgent 运行相关配置
     */
    private Agent agent = new Agent();

    /**
     * 多租户工作根目录
     */
    private String tenantDataRoot = "/home/luffy/ruoyi/ai/workspace";

    /**
     * 技能根目录（运行期文件系统路径）
     */
    private String skillsDir = "/home/luffy/ruoyi/ai/skills";

    /**
     * Redis 检查点存储配置（用于 DeepAgent 内置 RedisCheckpointer）。
     * 不配置 host 时回退到 InMemoryCheckpointer。
     */
    private Redis redis = new Redis();

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public String getTenantDataRoot() {
        return tenantDataRoot;
    }

    public void setTenantDataRoot(String tenantDataRoot) {
        this.tenantDataRoot = tenantDataRoot;
    }

    public String getSkillsDir() {
        return skillsDir;
    }

    public void setSkillsDir(String skillsDir) {
        this.skillsDir = skillsDir;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    /**
     * DeepAgent 运行参数
     */
    public static class Agent {
        /**
         * 最大推理轮数
         */
        private int maxIterations = 30;

        /**
         * 系统提示词
         */
        private String systemPrompt = "你是一个乐于助人的 AI 助手。当需要查询实时信息（例如天气）时，"
                + "请使用 executeCmd 工具运行 shell 命令获取数据（如：curl -s \"https://wttr.in/Shenzhen?format=3&lang=zh\" 查询天气）。"
                + "回答时使用中文。";

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }
    }

    /**
     * Redis 检查点存储参数
     */
    public static class Redis {
        /**
         * Redis 主机。留空则禁用 RedisCheckpointer，回退到 InMemoryCheckpointer。
         */
        private String host;

        /**
         * Redis 端口
         */
        private int port = 6379;

        /**
         * 默认 TTL（分钟）。null 表示不设置 TTL（永久保留）。
         */
        private Double defaultTtlMinutes;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public Double getDefaultTtlMinutes() {
            return defaultTtlMinutes;
        }

        public void setDefaultTtlMinutes(Double defaultTtlMinutes) {
            this.defaultTtlMinutes = defaultTtlMinutes;
        }
    }
}
