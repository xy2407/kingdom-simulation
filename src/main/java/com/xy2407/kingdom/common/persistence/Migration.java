package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 表结构迁移步骤，按version递增逐级应用
 */
public interface Migration {
    int version();

    void apply(Connection connection) throws SQLException;
}
