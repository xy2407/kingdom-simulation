package com.xy2407.kingdom.common.home;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * 住宅床位解析与占用反查：扫建筑内的床头，判定人床绑定是否失效
 */
public final class HomeBeds {

    private static final int MAX_SCAN = 32768;

    private static boolean migrating;

    private HomeBeds() {
    }

    public static List<BlockPos> beds(ServerLevel level, BuildingRecord building) {
        List<BlockPos> found = new ArrayList<>();
        int scanned = 0;
        for (int y = building.min().getY(); y <= building.max().getY() && scanned < MAX_SCAN; y++) {
            for (int x = building.min().getX(); x <= building.max().getX() && scanned < MAX_SCAN; x++) {
                for (int z = building.min().getZ(); z <= building.max().getZ() && scanned < MAX_SCAN; z++) {
                    scanned++;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!(state.getBlock() instanceof BedBlock)) {
                        continue;
                    }
                    if (state.getValue(BedBlock.PART) != BedPart.HEAD) {
                        continue;
                    }
                    found.add(pos.immutable());
                }
            }
        }
        return found;
    }

    public static int occupied(BuildingRecord building) {
        return takenBeds(building).size();
    }

    public static BlockPos assignFree(ServerLevel level, BuildingRecord building) {
        Set<BlockPos> taken = takenBeds(building);
        for (BlockPos bed : beds(level, building)) {
            if (!taken.contains(bed)) {
                return bed;
            }
        }
        return null;
    }

    public static BlockPos assignedBed(ServerLevel level, NpcData data) {
        if (data.getHomeId() == null) {
            return null;
        }
        BlockPos bed = new BlockPos(data.getHomeX(), data.getHomeY(), data.getHomeZ());
        if (!level.isLoaded(bed)) {
            return bed;
        }
        BlockState state = level.getBlockState(bed);
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return bed;
        }
        return null;
    }

    public static void unbind(NpcData data) {
        data.setHomeId(null);
        data.setHomeX(0);
        data.setHomeY(0);
        data.setHomeZ(0);
    }

    public static void onBedRemoved(ServerLevel level, BlockPos pos, BlockState removed) {
        if (migrating || level == null || pos == null || removed == null) {
            return;
        }
        if (!(removed.getBlock() instanceof BedBlock)
                || removed.getValue(BedBlock.PART) != BedPart.HEAD) {
            return;
        }
        String dimension = level.dimension().location().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || npc.getHomeId() == null
                    || npc.getHomeX() != pos.getX() || npc.getHomeY() != pos.getY()
                    || npc.getHomeZ() != pos.getZ()) {
                continue;
            }
            if (!dimension.equals(npc.getDimension())) {
                continue;
            }
            unbind(npc);
        }
    }

    public static void beginMigration() {
        migrating = true;
    }

    public static void endMigration() {
        migrating = false;
    }

    private static Set<BlockPos> takenBeds(BuildingRecord building) {
        Set<BlockPos> taken = new HashSet<>();
        String id = building.id().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !id.equals(npc.getHomeId())) {
                continue;
            }
            taken.add(new BlockPos(npc.getHomeX(), npc.getHomeY(), npc.getHomeZ()));
        }
        return taken;
    }
}
