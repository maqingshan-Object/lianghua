package com.quant.api.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.quant.api.config.ApiProperties;
import com.quant.api.web.dto.Dtos;
import com.quant.engine.data.QuantDb;
import com.quant.paper.engine.PaperTrader;

/**
 * 每日任务：采集（调 Python 脚本）→ 选股 → 模拟调仓。
 *
 * 触发方式二选一：
 * 1. POST /api/jobs/daily（供系统 cron 调用，推荐；README 有生产 cron 示例）；
 * 2. @Scheduled（app.daily-job.enabled=true 时启用，默认关闭）。
 *
 * 并发保护：AtomicBoolean 防重入，一次只跑一个。
 */
@Service
public class DailyJobService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(DailyJobService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AtomicBoolean running = new AtomicBoolean(false);

    public DailyJobService(ApiProperties props) {
        super(props);
    }

    @Scheduled(cron = "${app.daily-job.cron:0 30 16 * * ?}")
    public void scheduledRun() {
        if (!props.getDailyJob().isEnabled()) {
            return;
        }
        try {
            runDaily();
        } catch (Exception e) {
            log.error("定时每日任务失败", e);
        }
    }

    public Dtos.DailyJobResult runDaily() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return new Dtos.DailyJobResult("already_running", null, Map.of(), Map.of());
        }
        try {
            Map<String, Object> collector = runCollector();
            LocalDate date = latestTradeDate();
            Map<String, Object> paper;
            if (date == null) {
                paper = Map.of("status", "skipped", "reason", "采集后仍无行情数据");
            } else {
                paper = runPaper(date);
            }
            return new Dtos.DailyJobResult("ok", date, collector, paper);
        } finally {
            running.set(false);
        }
    }

    /** 调 Python 采集脚本（M1），输出重定向到日志文件，超时则终止。 */
    private Map<String, Object> runCollector() throws Exception {
        Path logDir = Path.of(props.getReportsDir()).getParent().resolve("logs");
        Files.createDirectories(logDir);
        Path logFile = logDir.resolve("collector-" + LocalDateTime.now().format(TS) + ".log");

        ProcessBuilder pb = new ProcessBuilder(
                props.getCollectorPython(), props.getCollectorScript(),
                "--db", props.getDbPath());
        pb.redirectOutput(logFile.toFile());
        pb.redirectErrorStream(true);
        log.info("启动采集脚本: {} {} --db {}", props.getCollectorPython(),
                props.getCollectorScript(), props.getDbPath());
        Process p = pb.start();
        boolean done = p.waitFor(props.getDailyJob().getCollectorTimeoutMinutes(), TimeUnit.MINUTES);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!done) {
            p.destroyForcibly();
            out.put("status", "timeout");
            out.put("log", logFile.toString());
            log.error("采集脚本超时（{} 分钟），已终止", props.getDailyJob().getCollectorTimeoutMinutes());
            return out;
        }
        out.put("status", p.exitValue() == 0 ? "ok" : "failed");
        out.put("exitCode", p.exitValue());
        out.put("log", logFile.toString());
        out.put("logTail", tail(logFile, 20));
        return out;
    }

    private LocalDate latestTradeDate() throws SQLException {
        return withDb(db -> {
            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT MAX(trade_date) FROM daily_bars")) {
                if (rs.next() && rs.getString(1) != null) {
                    return LocalDate.parse(rs.getString(1));
                }
                return null;
            }
        });
    }

    /** 模拟调仓（装配方式与 PaperCommand 一致；runDay 内按运行日处理配置版本）。 */
    private Map<String, Object> runPaper(LocalDate date) throws Exception {
        return withDb(db -> {
            PaperTrader trader = EngineWiring.paperTrader(db, props.isAiMock());
            PaperTrader.DaySummary s = trader.runDay(date);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("skipped", s.skipped());
            out.put("nav", round2(s.nav()));
            out.put("cash", round2(s.cash()));
            out.put("dailyPnl", round2(s.dailyPnl()));
            out.put("filledBuys", s.filledBuys());
            out.put("filledSells", s.filledSells());
            out.put("newBuyOrders", s.newBuyOrders());
            out.put("newSellOrders", s.newSellOrders());
            out.put("riskStatus", s.riskStatus());
            out.put("versionNo", s.versionNo());
            return out;
        });
    }

    private String tail(Path file, int lines) {
        try {
            java.util.List<String> all = Files.readAllLines(file);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (Exception e) {
            return "(日志读取失败: " + e.getMessage() + ")";
        }
    }
}
