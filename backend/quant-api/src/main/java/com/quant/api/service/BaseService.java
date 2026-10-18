package com.quant.api.service;

import java.sql.SQLException;

import com.quant.api.config.ApiProperties;
import com.quant.engine.data.QuantDb;

/**
 * Service 基类：每个请求独立打开一个 SQLite 连接（try-with-resources），
 * 避免多线程共享单连接；读多写少场景下足够。
 */
public abstract class BaseService {

    protected final ApiProperties props;

    protected BaseService(ApiProperties props) {
        this.props = props;
    }

    @FunctionalInterface
    protected interface DbWork<T> {
        T run(QuantDb db) throws Exception;
    }

    protected <T> T withDb(DbWork<T> work) throws SQLException {
        try (QuantDb db = new QuantDb(props.getDbPath())) {
            return work.run(db);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("DB 操作失败: " + e.getMessage(), e);
        }
    }

    protected static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    protected static Double round2OrNull(Double v) {
        return v == null ? null : round2(v);
    }
}
