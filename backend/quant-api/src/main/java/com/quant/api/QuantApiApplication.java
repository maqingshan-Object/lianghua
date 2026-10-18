package com.quant.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.quant.api.config.ApiProperties;

/**
 * quant-api 启动入口（M5a）。
 *
 * 纯展示/配置层：所有接口只读 SQLite 行情与模拟账户数据；
 * 写操作仅限策略配置版本表（strategy_config / strategy_config_version）。
 * 零券商/交易 API 依赖，编译期就不具备实盘能力。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ApiProperties.class)
public class QuantApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuantApiApplication.class, args);
    }
}
