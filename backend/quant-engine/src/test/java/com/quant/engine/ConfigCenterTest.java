package com.quant.engine;

import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 配置版本化测试：保存新版本、按日期取版本、回滚。 */
class ConfigCenterTest {
    private TestDb tdb;
    private ConfigCenter cc;
    private final LocalDate day1 = LocalDate.of(2025, 6, 30);
    private final LocalDate day2 = LocalDate.of(2025, 7, 1);

    @BeforeEach
    void setUp() throws Exception {
        tdb = new TestDb();
        cc = tdb.cc;
    }

    @AfterEach
    void tearDown() throws Exception {
        tdb.close();
    }

    @Test
    void 首次保存生成v1_缺省值齐全() throws Exception {
        int v = cc.saveVersion(Map.of(), "初始化", day1);
        assertThat(v).isEqualTo(1);
        StrategyConfig cfg = cc.configForDate(day1);
        assertThat(cfg.get(StrategyConfig.FUSION_MODE)).isEqualTo("ENGINE_ONLY");
        assertThat(cfg.getDouble(StrategyConfig.FUSION_THRESHOLD)).isEqualTo(60.0);
    }

    @Test
    void 新版本下一交易日生效_旧版本当日仍有效() throws Exception {
        cc.saveVersion(Map.of(), "v1", day1);
        cc.saveVersion(Map.of(StrategyConfig.FUSION_THRESHOLD, "80"), "调高阈值", day2);

        // day1 仍用 v1（阈值 60）
        assertThat(cc.versionForDate(day1)).isEqualTo(1);
        assertThat(cc.configForDate(day1).getDouble(StrategyConfig.FUSION_THRESHOLD)).isEqualTo(60.0);
        // day2 用 v2（阈值 80）
        assertThat(cc.versionForDate(day2)).isEqualTo(2);
        assertThat(cc.configForDate(day2).getDouble(StrategyConfig.FUSION_THRESHOLD)).isEqualTo(80.0);
    }

    @Test
    void 回滚生成新版本_内容等于目标版本() throws Exception {
        cc.saveVersion(Map.of(), "v1", day1);
        cc.saveVersion(Map.of(StrategyConfig.FUSION_THRESHOLD, "80"), "v2 调高", day2);
        int v3 = cc.rollbackTo(1, LocalDate.of(2025, 7, 2));

        assertThat(v3).isEqualTo(3);
        assertThat(cc.listVersions()).hasSize(3); // 审计链不断
        assertThat(cc.configForDate(LocalDate.of(2025, 7, 2)).getDouble(StrategyConfig.FUSION_THRESHOLD))
                .isEqualTo(60.0);
    }

    @Test
    void 未知配置项拒绝写入() throws Exception {
        assertThatThrownBy(() -> cc.saveVersion(Map.of("strategy.unknown_xxx", "1"), "typo", day1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知配置项");
    }

    @Test
    void 版本变更记录diff() throws Exception {
        cc.saveVersion(Map.of(), "v1", day1);
        cc.saveVersion(Map.of(StrategyConfig.SELECT_TOP_N, "5"), "只要5只", day2);
        List<ConfigCenter.VersionInfo> vs = cc.listVersions();
        assertThat(vs).hasSize(2);
        assertThat(vs.get(1).changes()).contains("select.top_n: 10 -> 5");
    }

    @Test
    void 无生效版本时抛异常() throws Exception {
        cc.saveVersion(Map.of(), "v1", day2); // effective day2
        assertThatThrownBy(() -> cc.configForDate(day1)) // day1 无版本生效
                .isInstanceOf(IllegalStateException.class);
    }
}
