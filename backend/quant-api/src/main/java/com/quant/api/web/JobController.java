package com.quant.api.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.api.config.ApiProperties;
import com.quant.api.service.DailyJobService;
import com.quant.api.web.dto.Dtos;

@RestController
public class JobController {

    private final DailyJobService dailyJobService;

    public JobController(DailyJobService dailyJobService) {
        this.dailyJobService = dailyJobService;
    }

    /**
     * 手动触发每日任务：采集 → 选股 → 模拟调仓。
     * 生产环境建议用系统 cron 定时调用本端点（见 README），而非依赖 @Scheduled。
     */
    @PostMapping("/api/jobs/daily")
    public Dtos.DailyJobResult runDaily() throws Exception {
        return dailyJobService.runDaily();
    }
}

/** 根路径：前端 dist 存在则返回 index.html，否则返回 API 状态（JSON）。 */
@RestController
class RootController {

    private final ApiProperties props;

    RootController(ApiProperties props) {
        this.props = props;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<?> index() throws Exception {
        Path idx = Paths.get(props.getFrontendDist()).resolve("index.html");
        if (Files.isRegularFile(idx)) {
            String html = Files.readString(idx);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "ok",
                        "message", "quant-api running (frontend dist not built yet)",
                        "api", "/api/health"));
    }
}
