package com.quant.api.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.api.service.BacktestService;
import com.quant.api.web.dto.Dtos;

@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    /**
     * 运行回测（同步）：在源库临时拷贝上回放，不污染线上模拟账户。
     * 长区间可能耗时数分钟，请设足客户端超时。
     * Body: {"from":"2024-01-01","to":"2024-12-31","version":1}
     */
    @PostMapping
    public Dtos.BacktestResponse run(@RequestBody Map<String, Object> body) throws Exception {
        LocalDate from = LocalDate.parse(String.valueOf(body.get("from")));
        LocalDate to = LocalDate.parse(String.valueOf(body.get("to")));
        Object v = body.get("version");
        if (v == null) {
            throw new IllegalArgumentException("缺少 version（策略版本号）");
        }
        int version = Integer.parseInt(String.valueOf(v));
        return backtestService.run(from, to, version);
    }

    /** 下载回测 HTML 报告。 */
    @GetMapping("/report/{file}")
    public ResponseEntity<Resource> report(@PathVariable String file) throws Exception {
        Path p = backtestService.reportFile(file);
        byte[] bytes = Files.readAllBytes(p);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + p.getFileName() + "\"")
                .contentType(MediaType.TEXT_HTML)
                .body(new ByteArrayResource(bytes));
    }
}
