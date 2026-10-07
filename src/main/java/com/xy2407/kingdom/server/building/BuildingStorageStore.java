package com.xy2407.kingdom.server.building;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.sighs.apricityui.config.ApricitySavedData;
import com.xy2407.kingdom.common.persistence.BuildingStorageSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 建筑仓库的存储管理器：**SQLite 是真源，AUI 的 SavedData 只是这次会话的载体**。
 *
 * <h2>为什么要有这一层</h2>
 * 界面的槽位、拖拽、shift-click 全部由 AUI 的容器屏负责，它只认四种数据源
 * （{@code player} / {@code saveddata} / {@code blockEntity} / {@code entity}）。
 * 建筑没有实体、也不该为它造方块实体，所以走 {@code saveddata}：
 * AUI 会用 {@code ApricitySavedData} 里的一格 {@link ItemStackHandler} 当容器，
 * 每个建筑一份（dataName 里带建筑 id，见 {@link #dataName}）。
 *
 * <p>但 SavedData 落在世界存档的 {@code .dat} 里，而项目要求物品**进数据库**。
 * 于是这里做一层搬运：</p>
 * <ul>
 *   <li><b>进</b>：{@link #handlerOf} 第一次拿到某个建筑的容器时，从
 *       {@code building_storage} 表读 SNBT 灌进去；</li>
 *   <li><b>出</b>：{@link #tick} 每 {@value #FLUSH_INTERVAL_TICKS} tick 扫一遍，
 *       内容与上次写库时不同才提交（逐条比较 SNBT，没动过的建筑零开销）。</li>
 * </ul>
 *
 * <p>这样即使 SavedData 那份被删掉，下次打开也会从数据库原样恢复；
 * 反过来数据库里也始终是最新的一份。</p>
 */
public final class BuildingStorageStore {

    public static final BuildingStorageStore INSTANCE = new BuildingStorageStore();

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * AUI 侧的容器 key 是写死的：{@code BindingBuilder.saveddata(...)} 声明的
     * containerId 恒为 {@code "saved_data"}，而 {@code DataSourceFactory} 直接拿
     * containerId 当 inventoryKey。所以这里必须跟它对齐。
     */
    private static final String AUI_INVENTORY_KEY = "saved_data";

    /** 每个建筑一份 SavedData，名字里带建筑 id 才能互不干扰。 */
    private static final String DATA_NAME_PREFIX = "kingdom_building_storage_";

    /** 写库间隔：与市民落库一个节奏，肉眼无感的延迟。 */
    private static final int FLUSH_INTERVAL_TICKS = 60;

    private final Map<UUID, ItemStackHandler> handlers = new ConcurrentHashMap<>();
    /** 上次写进数据库的 SNBT，用来判断"这次要不要写"。 */
    private final Map<UUID, String> lastWritten = new ConcurrentHashMap<>();
    private int tickCounter;

    private BuildingStorageStore() {
    }

    public void reset() {
        handlers.clear();
        lastWritten.clear();
        tickCounter = 0;
    }

    /** AUI 的 {@code saveddata(dataName, capacity)} 必须用同一个名字，才能落到同一份容器上。 */
    public static String dataName(UUID buildingId) {
        return DATA_NAME_PREFIX + buildingId.toString().replace("-", "");
    }

    /**
     * 取这座建筑的仓库容器，没有就建一个（容量按 {@code capacity}），
     * 并在首次拿到时把数据库里的那份灌进去。
     *
     * <p>容量变了会按新容量重建（AUI 的 {@code getOrCreate} 也是截断策略：
     * 装得下的保留，多出来的丢弃），此时不再从库里灌——旧的那份已经是另一套格数了。</p>
     */
    public ItemStackHandler handlerOf(MinecraftServer server, UUID buildingId, int capacity) {
        if (server == null || buildingId == null) {
            return null;
        }
        int slots = Math.max(1, capacity);
        ItemStackHandler cached = handlers.get(buildingId);
        if (cached != null && cached.getSlots() == slots) {
            return cached;
        }

        ApricitySavedData data = ApricitySavedData.get(server, dataName(buildingId));
        ItemStackHandler handler = data.getOrCreate(AUI_INVENTORY_KEY, slots);
        if (cached == null) {
            String snbt = readSnbt(buildingId);
            if (snbt != null && !snbt.isBlank()) {
                readSnbtInto(handler, server.registryAccess(), snbt);
            }
            lastWritten.put(buildingId, snbt == null ? "" : snbt);
        }
        handlers.put(buildingId, handler);
        return handler;
    }

    /** 已建过的那些容器里，内容变了的写回数据库。 */
    public void tick(MinecraftServer server) {
        if (server == null || handlers.isEmpty() || !KingdomPersistence.isReady()) {
            return;
        }
        if (++tickCounter < FLUSH_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        flushAll(server);
    }

    /** 服务器停止前把还没落盘的一次性写完（{@link #flushAll} 是幂等的）。 */
    public void flushAll(MinecraftServer server) {
        if (server == null || handlers.isEmpty() || !KingdomPersistence.isReady()) {
            return;
        }
        for (Map.Entry<UUID, ItemStackHandler> entry : handlers.entrySet()) {
            flush(server, entry.getKey(), entry.getValue());
        }
    }

    private void flush(MinecraftServer server, UUID buildingId, ItemStackHandler handler) {
        String snbt = toSnbt(handler, server.registryAccess());
        String previous = lastWritten.get(buildingId);
        if (snbt.equals(previous == null ? "" : previous)) {
            /* 没动过就不写库——拖动界面时这条会被调用很多次，省掉全部无谓的 UPSERT */
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(BuildingStorageSchema.COL_INVENTORY, snbt);
        values.put(BuildingStorageSchema.COL_CAPACITY, handler.getSlots());
        values.put(BuildingStorageSchema.COL_UPDATED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(BuildingStorageSchema.TABLE_BUILDING_STORAGE,
                BuildingStorageSchema.COL_BUILDING_ID, buildingId.toString(), values);
        lastWritten.put(buildingId, snbt);
    }

    private static String readSnbt(UUID buildingId) {
        try {
            return KingdomPersistence.querySync(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT " + BuildingStorageSchema.COL_INVENTORY
                                + " FROM " + BuildingStorageSchema.TABLE_BUILDING_STORAGE
                                + " WHERE " + BuildingStorageSchema.COL_BUILDING_ID + " = ?")) {
                    ps.setString(1, buildingId.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
        } catch (SQLException failure) {
            /* 读不出来就当作空仓库：新建筑本来也没有行，不该因此打不开界面 */
            LOGGER.warn("读取建筑仓库失败 building={}", buildingId, failure);
            return null;
        }
    }

    private static String toSnbt(ItemStackHandler handler, HolderLookup.Provider registries) {
        CompoundTag tag = handler.serializeNBT(registries);
        return tag == null || tag.isEmpty() ? "" : tag.toString();
    }

    /** 解析失败就留空，绝不抛出去——一份坏数据不该让界面打不开。 */
    private static void readSnbtInto(ItemStackHandler handler, HolderLookup.Provider registries, String snbt) {
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            handler.deserializeNBT(registries, tag);
        } catch (Exception invalid) {
            LOGGER.warn("建筑仓库数据解析失败，按空仓库处理", invalid);
        }
    }
}
