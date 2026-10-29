package com.xy2407.kingdom.server.faction;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.faction.WildStructureCatalog;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NpcSchema;
import com.xy2407.kingdom.common.persistence.WildStructureSchema;
import com.xy2407.kingdom.server.mapoverlay.MapOverlayCollector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * 野生领地服务：区块加载时发现名单内结构 → 用结构 piece 反推完整区块集合 → 全加载且封闭后落盘成领地 → 延迟收编无主市民
 */
public final class WildTerritoryService {

    public static final WildTerritoryService INSTANCE = new WildTerritoryService();

    private static final int SCAN_INTERVAL_TICKS = 200;
    private static final int MAX_SCANS_PER_ROUND = 2;
    private static final int NPC_ASSIGN_DELAY_TICKS = 20;

    private final ArrayDeque<PendingScan> pending = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Set<String> completed = new HashSet<>();
    private final Map<String, String> persisted = new LinkedHashMap<>();
    private final ArrayDeque<DelayedAssign> delayed = new ArrayDeque<>();

    private long lastScanTick;

    private WildTerritoryService() {
    }

    private record PendingScan(ServerLevel level, ResourceLocation structureId,
                               String instanceKey, StructureStart start) {
    }

    private record DelayedAssign(long executeAtTick, ServerLevel level, String territoryId,
                                 Set<Long> chunks) {
    }

    public void reset() {
        pending.clear();
        queued.clear();
        completed.clear();
        persisted.clear();
        delayed.clear();
        lastScanTick = 0L;
    }

    public void load(ServerLevel level) {
        if (level == null) {
            return;
        }
        try {
            KingdomPersistence.querySync(connection -> {
                try (java.sql.Statement st = connection.createStatement();
                     java.sql.ResultSet rs = st.executeQuery("SELECT "
                             + WildStructureSchema.COL_INSTANCE_KEY + ","
                             + WildStructureSchema.COL_TERRITORY_ID + " FROM "
                             + WildStructureSchema.TABLE_WILD)) {
                    while (rs.next()) {
                        String key = rs.getString(1);
                        if (key == null) {
                            continue;
                        }
                        completed.add(key);
                        String territoryId = rs.getString(2);
                        if (territoryId != null && !territoryId.isBlank()) {
                            persisted.put(key, territoryId);
                        }
                    }
                }
                return Boolean.TRUE;
            });
        } catch (Exception exception) {
            ModMain.LOGGER.warn("野生领地：读取已落盘实例失败", exception);
        }
    }

    public int scanAround(ServerLevel level, BlockPos center, int radiusChunks) {
        if (level == null || center == null) {
            return 0;
        }
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        int added = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = chunkX + dx;
                int cz = chunkZ + dz;
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                int before = pending.size();
                onChunkLoad(level, new ChunkPos(cx, cz));
                if (pending.size() > before) {
                    added++;
                }
            }
        }
        return added;
    }

    public int pendingCount() {
        return pending.size();
    }

    public int forceProcess(MinecraftServer server, int budget) {
        if (server == null) {
            return 0;
        }
        int done = 0;
        while (budget-- > 0) {
            PendingScan scan = pending.pollFirst();
            if (scan == null) {
                break;
            }
            scanOne(scan);
            done++;
        }
        return done;
    }

    public void onChunkLoad(ServerLevel level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return;
        }
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) {
            return;
        }
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            ResourceLocation id = structureRegistry.getKey(entry.getKey());
            if (!WildStructureCatalog.matches(id)) {
                continue;
            }
            StructureStart start = entry.getValue();
            if (start == StructureStart.INVALID_START || !start.isValid()
                    || start.getPieces().isEmpty()) {
                continue;
            }
            String instanceKey = instanceKey(level, id, start);
            if (completed.contains(instanceKey) || queued.contains(instanceKey)) {
                continue;
            }
            queued.add(instanceKey);
            pending.addLast(new PendingScan(level, id, instanceKey, start));
        }
    }

    public void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long now = server.getTickCount();

        while (!delayed.isEmpty()) {
            DelayedAssign head = delayed.peekFirst();
            if (head == null || now < head.executeAtTick()) {
                break;
            }
            delayed.pollFirst();
            assignNpcs(server, head);
        }

        if (now - lastScanTick < SCAN_INTERVAL_TICKS) {
            return;
        }
        lastScanTick = now;
        int budget = MAX_SCANS_PER_ROUND;
        while (budget-- > 0) {
            PendingScan scan;
            synchronized (pending) {
                scan = pending.pollFirst();
            }
            if (scan == null) {
                return;
            }
            scanOne(scan);
        }
    }

    private void scanOne(PendingScan scan) {
        Set<Long> chunks = collectChunks(scan.start());
        if (chunks.isEmpty()) {
            finish(scan.instanceKey());
            return;
        }
        if (!fullyLoaded(scan.level(), chunks)) {
            queued.remove(scan.instanceKey());
            return;
        }
        commit(scan, chunks);
    }

    private static Set<Long> collectChunks(StructureStart start) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox box = piece.getBoundingBox();
            int minCX = box.minX() >> 4;
            int minCZ = box.minZ() >> 4;
            int maxCX = box.maxX() >> 4;
            int maxCZ = box.maxZ() >> 4;
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    chunks.add(Territory.chunkKey(cx, cz));
                }
            }
        }
        return chunks;
    }

    private static boolean fullyLoaded(ServerLevel level, Set<Long> chunks) {
        for (long key : chunks) {
            int cx = Territory.chunkKeyX(key);
            int cz = Territory.chunkKeyZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!level.hasChunk(cx + dx, cz + dz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void commit(PendingScan scan, Set<Long> chunks) {
        if (persisted.containsKey(scan.instanceKey())) {
            finish(scan.instanceKey());
            return;
        }
        ServerLevel level = scan.level();
        String dimension = level.dimension().location().toString();
        Set<Long> free = new LinkedHashSet<>();
        for (long key : chunks) {
            int cx = Territory.chunkKeyX(key);
            int cz = Territory.chunkKeyZ(key);
            if (TerritoryRegistry.INSTANCE.byChunk(dimension, cx, cz) != null) {
                continue;
            }
            free.add(key);
        }
        finish(scan.instanceKey());
        if (free.isEmpty()) {
            saveRow(scan, dimension, "", free);
            return;
        }
        String nation = WildStructureCatalog.nationFor(scan.structureId());
        UUID owner = WildStructureCatalog.ownerFor(scan.instanceKey());
        BlockPos center = centerOf(free);
        Territory territory = TerritoryRegistry.INSTANCE.createWithChunks(owner, nation,
                WildStructureCatalog.nameFor(level.random), dimension, center,
                DateSystem.totalDay(level), free);
        if (territory == null) {
            return;
        }
        saveRow(scan, dimension, territory.id(), free);
        persisted.put(scan.instanceKey(), territory.id());
        MapOverlayCollector.pushAll();
        delayed.addLast(new DelayedAssign(level.getServer().getTickCount()
                + NPC_ASSIGN_DELAY_TICKS, level, territory.id(), free));
        ModMain.LOGGER.info("野生领地落成：{}（{}，{} 区块，阵营 {}）",
                territory.name(), scan.structureId(), free.size(), nation);
    }

    private void finish(String instanceKey) {
        queued.remove(instanceKey);
        completed.add(instanceKey);
    }

    private void assignNpcs(MinecraftServer server, DelayedAssign assign) {
        Territory territory = TerritoryRegistry.INSTANCE.byId(assign.territoryId());
        if (territory == null) {
            return;
        }
        String dimension = assign.level().dimension().location().toString();
        int assigned = 0;
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc == null || npc.isDead() || npc.isTourist()) {
                continue;
            }
            if (!npc.getOwnerId().isBlank() || !npc.getTerritoryId().isBlank()
                    || !npc.getNationId().isBlank()) {
                continue;
            }
            if (!dimension.equals(npc.getDimension())) {
                continue;
            }
            BlockPos pos = NpcManager.INSTANCE.positionOf(npc);
            if (pos == null) {
                continue;
            }
            long key = Territory.chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
            if (!assign.chunks().contains(key)) {
                continue;
            }
            npc.setTerritoryId(territory.id());
            npc.setNationId(territory.nationId());
            KingdomPersistence.submit(NpcSchema.TABLE_NPC, NpcSchema.COL_UUID,
                    npc.getId().toString(), npc.toValues());
            assigned++;
        }
        if (assigned > 0) {
            ModMain.LOGGER.info("野生领地 {} 收编 {} 名无主市民", territory.name(), assigned);
        }
    }

    private static BlockPos centerOf(Set<Long> chunks) {
        int minCX = Integer.MAX_VALUE;
        int minCZ = Integer.MAX_VALUE;
        int maxCX = Integer.MIN_VALUE;
        int maxCZ = Integer.MIN_VALUE;
        for (long key : chunks) {
            int cx = Territory.chunkKeyX(key);
            int cz = Territory.chunkKeyZ(key);
            minCX = Math.min(minCX, cx);
            minCZ = Math.min(minCZ, cz);
            maxCX = Math.max(maxCX, cx);
            maxCZ = Math.max(maxCZ, cz);
        }
        return new BlockPos(((minCX + maxCX) / 2) << 4, 64, ((minCZ + maxCZ) / 2) << 4);
    }

    private static String instanceKey(ServerLevel level, ResourceLocation structureId,
                                      StructureStart start) {
        BoundingBox first = start.getPieces().get(0).getBoundingBox();
        return level.dimension().location() + "@" + structureId + "@"
                + first.minX() + "," + first.minY() + "," + first.minZ();
    }

    private void saveRow(PendingScan scan, String dimension, String territoryId, Set<Long> chunks) {
        int minCX = Integer.MAX_VALUE;
        int minCZ = Integer.MAX_VALUE;
        int maxCX = Integer.MIN_VALUE;
        int maxCZ = Integer.MIN_VALUE;
        StringBuilder text = new StringBuilder();
        for (long key : chunks) {
            int cx = Territory.chunkKeyX(key);
            int cz = Territory.chunkKeyZ(key);
            minCX = Math.min(minCX, cx);
            minCZ = Math.min(minCZ, cz);
            maxCX = Math.max(maxCX, cx);
            maxCZ = Math.max(maxCZ, cz);
            if (text.length() > 0) {
                text.append(';');
            }
            text.append(cx).append(',').append(cz);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(WildStructureSchema.COL_INSTANCE_KEY, scan.instanceKey());
        values.put(WildStructureSchema.COL_STRUCTURE_ID, scan.structureId().toString());
        values.put(WildStructureSchema.COL_DIMENSION, dimension);
        Territory created = territoryId.isBlank()
                ? null : TerritoryRegistry.INSTANCE.byId(territoryId);
        values.put(WildStructureSchema.COL_NATION_ID, created == null
                ? WildStructureCatalog.nationFor(scan.structureId())
                : created.nationId());
        values.put(WildStructureSchema.COL_TERRITORY_ID, territoryId);
        values.put(WildStructureSchema.COL_CHUNKS, text.toString());
        values.put(WildStructureSchema.COL_MIN_CHUNK_X, minCX == Integer.MAX_VALUE ? 0 : minCX);
        values.put(WildStructureSchema.COL_MIN_CHUNK_Z, minCZ == Integer.MAX_VALUE ? 0 : minCZ);
        values.put(WildStructureSchema.COL_MAX_CHUNK_X, maxCX == Integer.MIN_VALUE ? 0 : maxCX);
        values.put(WildStructureSchema.COL_MAX_CHUNK_Z, maxCZ == Integer.MIN_VALUE ? 0 : maxCZ);
        values.put(WildStructureSchema.COL_SCANNED_AT, System.currentTimeMillis());
        KingdomPersistence.submit(WildStructureSchema.TABLE_WILD,
                WildStructureSchema.COL_INSTANCE_KEY, scan.instanceKey(), values);
    }

}
