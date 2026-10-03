package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 同步查询回调，业务方在此用连接执行SELECT
 */
@FunctionalInterface
public interface SqlQuery<T> {
    T apply(Connection connection) throws SQLException;
}
