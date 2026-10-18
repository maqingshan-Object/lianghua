package com.quant.api.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quant.api.service.CandidateService;
import com.quant.api.service.MarketService;
import com.quant.api.web.dto.Dtos;

@RestController
@RequestMapping("/api")
public class MarketController {

    private final MarketService marketService;
    private final CandidateService candidateService;

    public MarketController(MarketService marketService, CandidateService candidateService) {
        this.marketService = marketService;
        this.candidateService = candidateService;
    }

    /** 市场概况：指数涨跌 / 涨跌家数 / 成交额 / 热门行业。 */
    @GetMapping("/market/overview")
    public Dtos.MarketOverview overview() throws Exception {
        return marketService.overview();
    }

    /** 候选股：date 缺省取最近一次选股运行。 */
    @GetMapping("/candidates")
    public List<Dtos.CandidateDto> candidates(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws Exception {
        return candidateService.candidates(date);
    }

    /** 健康检查。 */
    @GetMapping("/health")
    public ResponseEntity<java.util.Map<String, String>> health() {
        return ResponseEntity.ok(java.util.Map.of("status", "ok"));
    }
}
