package com.quant.api.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.quant.api.config.ApiProperties;
import com.quant.api.web.dto.Dtos;
import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.QuantDb;

/**
 * 策略配置：查询当前全量配置 / 保存新版本（下一交易日生效）/ 回滚。
 * 这是 API 唯一的写操作入口（仅写 strategy_config / strategy_config_version）。
 */
@Service
public class StrategyConfigService extends BaseService {

    public StrategyConfigService(ApiProperties props) {
        super(props);
    }

    public Dtos.StrategyConfigDto current() throws SQLException {
        return withDb(db -> {
            ConfigCenter cc = EngineWiring.ensuredConfigCenter(db);
            Map<String, String> full = new LinkedHashMap<>(StrategyConfig.defaults());
            full.putAll(cc.loadAll());
            int versionNo = cc.maxVersionNo();
            String effectiveDate = effectiveDateOf(db, versionNo);
            return new Dtos.StrategyConfigDto(versionNo, effectiveDate, full, listVersions(cc));
        });
    }

    /** 保存新版本（默认下一自然日生效，"下一交易日"的近似）。未知 key 直接 400。 */
    public Dtos.VersionResult save(Map<String, String> changes, String note) throws SQLException {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("changes 不能为空");
        }
        return withDb(db -> {
            ConfigCenter cc = EngineWiring.ensuredConfigCenter(db);
            int next = cc.saveVersion(changes, note == null ? "API 修改" : note);
            String effectiveDate = effectiveDateOf(db, next);
            return new Dtos.VersionResult(next, effectiveDate);
        });
    }

    /** 回滚：以目标版本快照生成新版本（审计链不断）。 */
    public Dtos.VersionResult rollback(int versionNo) throws SQLException {
        return withDb(db -> {
            ConfigCenter cc = EngineWiring.ensuredConfigCenter(db);
            int next = cc.rollbackTo(versionNo, LocalDate.now().plusDays(1));
            String effectiveDate = effectiveDateOf(db, next);
            return new Dtos.VersionResult(next, effectiveDate);
        });
    }

    private String effectiveDateOf(QuantDb db, int versionNo) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT effective_date FROM strategy_config_version WHERE version_no = ?")) {
            ps.setInt(1, versionNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private List<Dtos.VersionDto> listVersions(ConfigCenter cc) throws SQLException {
        List<Dtos.VersionDto> out = new ArrayList<>();
        for (ConfigCenter.VersionInfo v : cc.listVersions()) {
            out.add(new Dtos.VersionDto(v.versionNo(), v.createdAt(), v.effectiveDate(), v.changes()));
        }
        return out;
    }
}
