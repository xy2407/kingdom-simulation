package com.xy2407.kingdom.server.farm;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.persistence.FarmLandSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 开垦登记表：内存按维度存坐标，落库只做一次增量 UPSERT。
 */
public final class FarmLandRegistry {

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private static final Map<String, Set<Long>> TILLED = new ConcurrentHashMap<>();
    private static volatile boolean loaded;
    private static int loadRetryCooldown;

    private FarmLandRegistry() {
    }

    public static void reset() {
        TILLED.clear();
        loaded = false;
        loadRetryCooldown = 0;
    }

    public static void tick(MinecraftServer server) {
        if (loaded) {
            return;
        }
        if (loadRetryCooldown > 0) {
            loadRetryCooldown--;
            return;
        }
        if (KingdomPersistence.isReady()) {
            load();
        }
    }

    private static void load() {
        try {
            int[] count = {0};
            TILLED.clear();
            KingdomPersistence.querySync(connection -> {
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + FarmLandSchema.COL_DIMENSION + ", "
                             + FarmLandSchema.COL_X + ", " + FarmLandSchema.COL_Y + ", "
                             + FarmLandSchema.COL_Z + " FROM " + FarmLandSchema.TABLE_FARM_LAND)) {
                    while (rs.next()) {
                        long packed = BlockPos.asLong(rs.getInt(FarmLandSchema.COL_X),
                                rs.getInt(FarmLandSchema.COL_Y), rs.getInt(FarmLandSchema.COL_Z));
                        TILLED.computeIfAbsent(rs.getString(FarmLandSchema.COL_DIMENSION),
                                key -> ConcurrentHashMap.newKeySet()).add(packed);
                        count[0]++;
                    }
                }
                return null;
            });
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 格开垦记录", count[0]);
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载开垦记录失败", exception);
        }
    }

    public static boolean isTilled(ResourceKey<Level> dimension, BlockPos pos) {
        if (TILLED.isEmpty() || dimension == null || pos == null) {
            return false;
        }
        Set<Long> positions = TILLED.get(dimensionKey(dimension));
        return positions != null && positions.contains(pos.asLong());
    }

    public static void mark(ServerLevel level, BlockPos pos, int day) {
        if (level == null || pos == null) {
            return;
        }
        String dimension = level.dimension().location().toString();
        if (!TILLED.computeIfAbsent(dimensionKey(level.dimension()), key -> ConcurrentHashMap.newKeySet())
                .add(pos.asLong())) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(FarmLandSchema.COL_DIMENSION, dimension);
        values.put(FarmLandSchema.COL_X, pos.getX());
        values.put(FarmLandSchema.COL_Y, pos.getY());
        values.put(FarmLandSchema.COL_Z, pos.getZ());
        values.put(FarmLandSchema.COL_CREATED_DAY, day);
        values.put(FarmLandSchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(FarmLandSchema.TABLE_FARM_LAND, FarmLandSchema.COL_ID,
                FarmLandSchema.landId(dimension, pos.getX(), pos.getY(), pos.getZ()), values);
    }

    private static String dimensionKey(ResourceKey<Level> dimension) {
        return dimension.location().toString();
    }
}
