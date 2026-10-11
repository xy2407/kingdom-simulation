package com.xy2407.kingdom.client.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.network.building.BuildingMovePacket;
import com.xy2407.kingdom.common.network.building.BuildingSyncPacket;
import com.xy2407.kingdom.common.network.building.BuildingSyncRequestPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * RTS 建筑迁移的客户端状态：建筑清单缓存与正在搬迁的临时状态
 */
@OnlyIn(Dist.CLIENT)
public final class ClientBuildingStore {

    public record Entry(UUID id, String name, BlockPos min, BlockPos max) {

        public Vec3i size() {
            return new Vec3i(max.getX() - min.getX() + 1,
                    max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        }

        public boolean covers(double x, double z) {
            return x >= min.getX() && x <= max.getX() + 1.0D
                    && z >= min.getZ() && z <= max.getZ() + 1.0D;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static UUID movingId;
    private static String movingName = "";
    private static Vec3i movingSize = Vec3i.ZERO;
    private static BlockPos anchorMin;
    private static BlockPos ghostMin;

    private ClientBuildingStore() {
    }

    public static void accept(BuildingSyncPacket packet) {
        ENTRIES.clear();
        for (BuildingSyncPacket.Entry entry : packet.entries()) {
            ENTRIES.add(new Entry(entry.id(), entry.name(), entry.min(), entry.max()));
        }
        if (movingId != null) {
            Entry live = byId(movingId);
            if (live == null) {
                clear();
            } else {
                anchorMin = live.min();
                movingSize = live.size();
                if (ghostMin == null) {
                    ghostMin = live.min();
                }
            }
        }
    }

    public static void requestSync() {
        PacketDistributor.sendToServer(new BuildingSyncRequestPacket());
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Entry byId(UUID id) {
        if (id == null) {
            return null;
        }
        for (Entry entry : ENTRIES) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public static Entry pickAt(double x, double z) {
        Entry best = null;
        for (Entry entry : ENTRIES) {
            if (!entry.covers(x, z)) {
                continue;
            }
            if (best == null || entry.min().getY() > best.min().getY()) {
                best = entry;
            }
        }
        return best;
    }

    public static boolean isMoving() {
        return movingId != null;
    }

    public static UUID movingId() {
        return movingId;
    }

    public static String movingName() {
        return movingName;
    }

    public static BlockPos anchorMin() {
        return anchorMin;
    }

    public static BlockPos ghostMin() {
        return ghostMin;
    }

    public static Vec3i movingSize() {
        return movingSize;
    }

    public static boolean startMove(Entry entry) {
        if (entry == null) {
            return false;
        }
        movingId = entry.id();
        movingName = entry.name();
        movingSize = entry.size();
        anchorMin = entry.min();
        ghostMin = entry.min();
        return true;
    }

    public static void dragTo(BlockPos target) {
        if (movingId == null || target == null) {
            return;
        }
        ghostMin = target;
    }

    public static void nudge(int dx, int dy, int dz) {
        if (movingId == null || ghostMin == null) {
            return;
        }
        ghostMin = ghostMin.offset(dx, dy, dz);
    }

    public static void confirm() {
        if (movingId == null || ghostMin == null) {
            return;
        }
        if (ghostMin.equals(anchorMin)) {
            clear();
            return;
        }
        PacketDistributor.sendToServer(new BuildingMovePacket(movingId, ghostMin));
        clear();
    }

    public static void cancel() {
        clear();
    }

    public static BlockPos delta() {
        if (anchorMin == null || ghostMin == null) {
            return BlockPos.ZERO;
        }
        return ghostMin.subtract(anchorMin);
    }

    private static void clear() {
        movingId = null;
        movingName = "";
        movingSize = Vec3i.ZERO;
        anchorMin = null;
        ghostMin = null;
    }

    public static boolean sameBuilding(BuildingRecord record) {
        return record != null && movingId != null && movingId.equals(record.id());
    }
}
