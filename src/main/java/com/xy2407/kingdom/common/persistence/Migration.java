package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 表结构迁移步骤：按 version 递增逐级应用，每次都要求可重复执行。
 */
public interface Migration {

    int version();

    void apply(Connection connection) throws SQLException;

    default String tableName() {
        return null;
    }

    default String primaryKeyColumn() {
        return null;
    }
}
