package com.xy2407.kingdom.common.persistence;

import java.util.Collections;
import java.util.Map;

/**
 * 幂等写操作封装，UPSERT与DELETE两类
 */
public final class WriteOp {
    public enum Type { UPSERT, DELETE }

    private final Type type;
    private final String table;
    private final String keyColumn;
    private final Object key;
    private final Map<String, Object> values;

    private WriteOp(Type type, String table, String keyColumn, Object key, Map<String, Object> values) {
        this.type = type;
        this.table = table;
        this.keyColumn = keyColumn;
        this.key = key;
        this.values = values;
    }

    public static WriteOp upsert(String table, String keyColumn, Object key, Map<String, Object> values) {
        return new WriteOp(Type.UPSERT, table, keyColumn, key, values);
    }

    public static WriteOp delete(String table, String keyColumn, Object key) {
        return new WriteOp(Type.DELETE, table, keyColumn, key, Collections.emptyMap());
    }

    public Type getType() {
        return type;
    }

    public String getTable() {
        return table;
    }

    public String getKeyColumn() {
        return keyColumn;
    }

    public Object getKey() {
        return key;
    }

    public Map<String, Object> getValues() {
        return values;
    }
}
