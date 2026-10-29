package com.xy2407.kingdom.server.shop;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.block.ModBlocks;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.ShopSchema;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 商铺内存表：营业开关与开业扫描出的货架坐标，落库后供面板与营业员查询
 */
public final class ShopStore {

    public static final ShopStore INSTANCE = new ShopStore();

    public static final int MIN_SHELVES = 3;

    private static final int LOAD_RETRY_DELAY_TICKS = 200;

    private static final int MAX_SCAN = 32768;

    private static final String SHELF_SEPARATOR = ";";

    private final Map<UUID, ShopRecord> byBuilding = new HashMap<>();

    private boolean loaded;
    private int loadRetryCooldown;

    private ShopStore() {
    }

    public record ShopRecord(boolean open, String dimension, List<BlockPos> shelves) {

        public ShopRecord {
            dimension = dimension == null ? "" : dimension;
            shelves = shelves == null ? List.of() : List.copyOf(shelves);
        }
    }

    public void reset() {
        byBuilding.clear();
        loaded = false;
        loadRetryCooldown = 0;
    }

    public void tick(MinecraftServer server) {
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

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isOpen(UUID buildingId) {
        ShopRecord record = buildingId == null ? null : byBuilding.get(buildingId);
        return record != null && record.open();
    }

    public List<BlockPos> shelvesOf(UUID buildingId) {
        ShopRecord record = buildingId == null ? null : byBuilding.get(buildingId);
        return record == null ? List.of() : record.shelves();
    }

    public int setOpen(MinecraftServer server, BuildingRecord building, boolean open) {
        if (server == null || building == null) {
            return 0;
        }
        if (!loaded && KingdomPersistence.isReady()) {
            load();
        }
        ShopRecord previous = byBuilding.get(building.id());
        String dimension = building.dimension();
        List<BlockPos> shelves = previous == null ? List.of() : previous.shelves();
        if (open) {
            ServerLevel level = levelOf(server, dimension);
            shelves = level == null ? List.of() : scan(level, building);
            if (shelves.size() < MIN_SHELVES) {
                return -1;
            }
        }
        ShopRecord record = new ShopRecord(open, dimension, shelves);
        byBuilding.put(building.id(), record);
        KingdomPersistence.submit(ShopSchema.TABLE_SHOP, ShopSchema.COL_BUILDING_ID,
                building.id().toString(), toValues(building.id(), record));
        return shelves.size();
    }

    private static List<BlockPos> scan(ServerLevel level, BuildingRecord building) {
        List<BlockPos> found = new ArrayList<>();
        int scanned = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = building.min().getY(); y <= building.max().getY() && scanned < MAX_SCAN; y++) {
            for (int x = building.min().getX(); x <= building.max().getX() && scanned < MAX_SCAN; x++) {
                for (int z = building.min().getZ(); z <= building.max().getZ() && scanned < MAX_SCAN; z++) {
                    scanned++;
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    if (level.getBlockState(cursor).is(ModBlocks.SHOP_SHELF.get())) {
                        found.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return found;
    }

    private void load() {
        try {
            List<Map.Entry<UUID, ShopRecord>> rows = KingdomPersistence.querySync(connection -> {
                List<Map.Entry<UUID, ShopRecord>> result = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + ShopSchema.columns()
                             + " FROM " + ShopSchema.TABLE_SHOP)) {
                    while (rs.next()) {
                        Map.Entry<UUID, ShopRecord> row = readRow(rs);
                        if (row != null) {
                            result.add(row);
                        }
                    }
                }
                return result;
            });
            byBuilding.clear();
            for (Map.Entry<UUID, ShopRecord> row : rows) {
                byBuilding.put(row.getKey(), row.getValue());
            }
            loaded = true;
            ModMain.LOGGER.info("已加载 {} 家商铺", rows.size());
        } catch (Exception exception) {
            loadRetryCooldown = LOAD_RETRY_DELAY_TICKS;
            ModMain.LOGGER.error("加载商铺数据失败", exception);
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    private static Map<String, Object> toValues(UUID buildingId, ShopRecord record) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(ShopSchema.COL_BUILDING_ID, buildingId.toString());
        values.put(ShopSchema.COL_IS_OPEN, record.open() ? 1 : 0);
        values.put(ShopSchema.COL_DIMENSION, record.dimension());
        values.put(ShopSchema.COL_SHELVES, encodeShelves(record.shelves()));
        values.put(ShopSchema.COL_UPDATED_AT, System.currentTimeMillis());
        return values;
    }

    private static Map.Entry<UUID, ShopRecord> readRow(ResultSet rs) throws SQLException {
        String buildingId = rs.getString(ShopSchema.COL_BUILDING_ID);
        if (buildingId == null || buildingId.isBlank()) {
            return null;
        }
        ShopRecord record = new ShopRecord(rs.getInt(ShopSchema.COL_IS_OPEN) != 0,
                rs.getString(ShopSchema.COL_DIMENSION),
                decodeShelves(rs.getString(ShopSchema.COL_SHELVES)));
        return Map.entry(UUID.fromString(buildingId), record);
    }

    private static String encodeShelves(List<BlockPos> shelves) {
        StringBuilder builder = new StringBuilder();
        for (BlockPos pos : shelves) {
            if (builder.length() > 0) {
                builder.append(SHELF_SEPARATOR);
            }
            builder.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        return builder.toString();
    }

    private static List<BlockPos> decodeShelves(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<BlockPos> result = new ArrayList<>();
        for (String part : raw.split(SHELF_SEPARATOR)) {
            String[] digits = part.split(",");
            if (digits.length != 3) {
                continue;
            }
            try {
                result.add(new BlockPos(Integer.parseInt(digits[0].trim()),
                        Integer.parseInt(digits[1].trim()), Integer.parseInt(digits[2].trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }
}
