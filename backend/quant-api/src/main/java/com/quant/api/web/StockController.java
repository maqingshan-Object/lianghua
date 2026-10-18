package com.quant.api.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quant.api.service.StockService;
import com.quant.api.web.dto.Dtos;

@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    /**
     * 个股详情：K 线 + 均线 + 近 60 日信号事件 + 基本面 + 打分拆解 + 规则结论。
     * date 缺省取该标的最新有行情的日期。
     */
    @GetMapping("/{symbol}")
    public Dtos.StockDetail detail(
            @PathVariable("symbol") String symbol,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws Exception {
        return stockService.detail(symbol.trim().toUpperCase(), date);
    }
}
