package com.quant.api.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源：托管前端构建产物（M5b 产出）。
 * 目录不存在时不注册，/api/** 由 Controller 处理，不受影响。
 */
@Configuration
public class StaticConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StaticConfig.class);
    private final ApiProperties props;

    public StaticConfig(ApiProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dist = Paths.get(props.getFrontendDist());
        if (Files.isDirectory(dist)) {
            String location = dist.toAbsolutePath().toUri().toString();
            // /api/** 由 Controller 优先处理（Controller 映射优先级高于资源处理器）
            registry.addResourceHandler("/**")
                    .addResourceLocations(location)
                    .setCachePeriod(0);
            log.info("托管前端静态资源: {}", location);
        } else {
            log.info("前端 dist 不存在（{}），跳过静态资源托管，/ 返回 API 状态", dist);
        }
    }
}
