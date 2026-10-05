package com.xy2407.kingdom.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 表结构迁移步骤，按version递增逐级应用。
 *
 * <p>{@link Database#migrate} 现在**每次启动都会把所有迁移跑一遍**（{@code apply()} 都是幂等的），
 * 所以这里的方法只要保证"重复执行不出错"即可。</p>
 */
public interface Migration {

    int version();

    void apply(Connection connection) throws SQLException;

    /**
     * 这张表的名字；返回 {@code null}（默认）表示不参与"废行清理"。
     *
     * <p>与 {@link #primaryKeyColumn()} 配对使用：两个都返回非空时，
     * {@code Database} 会在 {@code apply()} 之后执行一次</p>
     * <pre>DELETE FROM 表 WHERE 主键 IS NULL OR 主键 = ''</pre>
     *
     * <p>清理对象的由来见 {@code Database#executeOp(UPSERT)} 上的说明——
     * 历史上写入端漏写主键，攒下了一堆主键为 NULL 的废行，
     * 它们在读取端会让整表加载失败。</p>
     */
    default String tableName() {
        return null;
    }

    /** 主键列名；与 {@link #tableName()} 配对（见那里的说明）。 */
    default String primaryKeyColumn() {
        return null;
    }
}
