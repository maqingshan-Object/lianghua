package com.quant.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置（application.yml 的 app.* 前缀）。
 * 全部可用启动参数覆盖，例如：--app.db-path=/data/quant.db
 */
@ConfigurationProperties(prefix = "app")
public class ApiProperties {

    /** SQLite 库路径（M1 采集 + M2/M3/M4 读写同一库）。 */
    private String dbPath = System.getProperty("user.home") + "/workspace/quant/data/quant.db";

    /** Vue 前端构建产物目录（M5b 产出 dist 后自动托管；不存在则 / 返回 API 状态）。 */
    private String frontendDist = "../frontend/dist";

    /** Python 采集脚本（M1 collector/collect.py），每日任务用 ProcessBuilder 调用。 */
    private String collectorPython = System.getProperty("user.home") + "/workspace/quant/venv/bin/python";
    private String collectorScript = System.getProperty("user.home") + "/workspace/quant/collector/collect.py";

    /** 回测 HTML 报告落盘目录（通过 /api/backtest/report/{file} 提供下载）。 */
    private String reportsDir = System.getProperty("user.home") + "/workspace/quant/backend/quant-api/reports";

    /** AI 通道：true 用 MockAISignalStrategy（联调）；false 用真实 DeepSeek（Key 从环境变量 DEEPSEEK_API_KEY 读取，无 Key 自动降级）。 */
    private boolean aiMock = false;

    /**
     * DeepSeek API Key：只从环境变量 DEEPSEEK_API_KEY 读取
     * （Spring relaxed binding 自动映射），绝不写进代码、文档或日志。
     */
    private String deepSeekApiKey;

    private final DailyJob dailyJob = new DailyJob();

    public String getDbPath() { return dbPath; }
    public void setDbPath(String dbPath) { this.dbPath = dbPath; }

    public String getFrontendDist() { return frontendDist; }
    public void setFrontendDist(String frontendDist) { this.frontendDist = frontendDist; }

    public String getCollectorPython() { return collectorPython; }
    public void setCollectorPython(String collectorPython) { this.collectorPython = collectorPython; }

    public String getCollectorScript() { return collectorScript; }
    public void setCollectorScript(String collectorScript) { this.collectorScript = collectorScript; }

    public String getReportsDir() { return reportsDir; }
    public void setReportsDir(String reportsDir) { this.reportsDir = reportsDir; }

    public boolean isAiMock() { return aiMock; }
    public void setAiMock(boolean aiMock) { this.aiMock = aiMock; }

    public String getDeepSeekApiKey() { return deepSeekApiKey; }
    public void setDeepSeekApiKey(String deepSeekApiKey) { this.deepSeekApiKey = deepSeekApiKey; }

    public DailyJob getDailyJob() { return dailyJob; }

    public static class DailyJob {
        /** 总开关（默认关闭，用 cron/手动触发 POST /api/jobs/daily）。 */
        private boolean enabled = false;
        /** Spring cron 表达式（默认每个交易日 16:30；非交易日 runDay 会自行跳过）。 */
        private String cron = "0 30 16 * * ?";
        /** 采集子进程超时（分钟）。 */
        private long collectorTimeoutMinutes = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public long getCollectorTimeoutMinutes() { return collectorTimeoutMinutes; }
        public void setCollectorTimeoutMinutes(long v) { this.collectorTimeoutMinutes = v; }
    }
}
