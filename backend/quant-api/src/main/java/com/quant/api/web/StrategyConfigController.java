package com.quant.api.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.api.service.StrategyConfigService;
import com.quant.api.web.dto.Dtos;

/**
 * 策略配置（API 唯一的写操作入口，仅写配置版本表）。
 * 保存/回滚均生成新版本，默认下一自然日生效（"下一交易日"的近似）。
 */
@RestController
@RequestMapping("/api/strategy/config")
public class StrategyConfigController {

    private final StrategyConfigService configService;

    public StrategyConfigController(StrategyConfigService configService) {
        this.configService = configService;
    }

    /** 当前全量配置 + 版本历史。 */
    @GetMapping
    public Dtos.StrategyConfigDto current() throws Exception {
        return configService.current();
    }

    /** 保存新版本。Body: {"changes":{"fusion.threshold":"55"},"note":"调低阈值"} */
    @PutMapping
    public Dtos.VersionResult save(@RequestBody Dtos.SaveConfigRequest req) throws Exception {
        return configService.save(req.changes(), req.note());
    }

    /** 回滚到历史版本（生成新版本，审计链不断）。Body: {"versionNo":3} */
    @PostMapping("/rollback")
    public Dtos.VersionResult rollback(@RequestBody Dtos.RollbackRequest req) throws Exception {
        return configService.rollback(req.versionNo());
    }
}
