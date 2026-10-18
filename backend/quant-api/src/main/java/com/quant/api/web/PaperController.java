package com.quant.api.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quant.api.service.PaperService;
import com.quant.api.web.dto.Dtos;

@RestController
@RequestMapping("/api/paper")
public class PaperController {

    private final PaperService paperService;

    public PaperController(PaperService paperService) {
        this.paperService = paperService;
    }

    /** 模拟账户总览（含近 90 日净值曲线）。 */
    @GetMapping("/summary")
    public Dtos.PaperSummary summary() throws Exception {
        return paperService.summary();
    }

    /** 模拟持仓（含止损/目标距离与预警）。 */
    @GetMapping("/positions")
    public List<Dtos.PositionDto> positions() throws Exception {
        return paperService.positions();
    }

    /** 模拟交易记录（倒序，默认 200 条）。 */
    @GetMapping("/trades")
    public List<Dtos.TradeDto> trades(
            @RequestParam(value = "limit", defaultValue = "200") int limit) throws Exception {
        return paperService.trades(limit);
    }
}
