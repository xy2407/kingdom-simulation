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
 * 住宅床位解析：扫出建筑范围内的床（只取床头），并负责"人 ↔ 床"的占用反查与失效判定。
 * 绑定关系本身存在 NPC 上（{@code home_id} + {@code home_x/y/z}），这里不额外维护表。
 */
public final class HomeBeds {

    /** 单次扫描的方块上限，与牧场同一档，避免超大建筑卡主线程。 */
    private static final int MAX_SCAN = 32768;

    /** 建筑整体迁移期间置位，见 {@link #beginMigration()}。 */
    private static boolean migrating;

    private HomeBeds() {
    }

    /** 建筑范围内所有床头坐标。只认 {@link BedPart#HEAD}，避免双人床被算成两张。 */
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

    /** 该住宅已被占用的床位数（从所有 NPC 的归属反查，不依赖额外存储）。 */
    public static int occupied(BuildingRecord building) {
        return takenBeds(building).size();
    }

    /** 挑一张没人占的床；住满了返回 null。 */
    public static BlockPos assignFree(ServerLevel level, BuildingRecord building) {
        Set<BlockPos> taken = takenBeds(building);
        for (BlockPos bed : beds(level, building)) {
            if (!taken.contains(bed)) {
                return bed;
            }
        }
        return null;
    }

    /**
     * NPC 名下那张床；床已被拆（或还没分配）返回 null。
     * 区块未加载时按"仍然有效"处理，免得人一离开视线就被解绑。
     */
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

    /** 解除住宅关联：NPC 随即变成无家可归，等待玩家重新分配。 */
    public static void unbind(NpcData data) {
        data.setHomeId(null);
        data.setHomeX(0);
        data.setHomeY(0);
        data.setHomeZ(0);
    }

    /**
     * 床被移除时的即时解绑，由 {@code BedBlock#onRemove} 的 Mixin 调用。
     * 只认床头那一格（双格床的另一半被移除时不动），按"床坐标 == 住户记录的床坐标"匹配，
     * 不需要再去反查住宅建筑。
     */
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

    /** 建筑迁移开始：搬家期间方块是被主动搬走的，不能顺手把住户全解绑（床马上会在新位置重建）。 */
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
