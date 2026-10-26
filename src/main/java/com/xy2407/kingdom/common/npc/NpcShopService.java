package com.xy2407.kingdom.common.npc;

import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 商铺营业员：上班期间在店里随机走动待客，下班交给常规排班
 */
public final class NpcShopService {

    public static final String JOB_NAME = "营业员";

    private static final int WANDER_INTERVAL_TICKS = 60;

    private static final int ACTION_INTERVAL_TICKS = 20;

    private static final double WANDER_RADIUS = 4.0D;

    private NpcShopService() {
    }

    public static void tick(NpcEntity npc, NpcData data) {
        if (!(npc.level() instanceof ServerLevel)) {
            return;
        }
        if (npc.tickCount % ACTION_INTERVAL_TICKS != 0) {
            return;
        }
        BuildingRecord building = buildingOf(data);
        if (building == null) {
            return;
        }
        BlockPos center = centerOf(building);
        if (!isInside(building, npc)) {
            npc.setNavTarget(center);
            return;
        }
        if (npc.tickCount % WANDER_INTERVAL_TICKS != 0) {
            return;
        }
        npc.walkToRandomPoint(center, WANDER_RADIUS);
    }

    public static void assignWorkers(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead()) {
                continue;
            }
            BuildingRecord building = buildingOf(npc);
            boolean shop = building != null && isShop(building);
            NpcBrain brain = npc.getBrain();
            if (!shop) {
                if (brain.isShopControlled()) {
                    brain.setShopControlled(false);
                }
                continue;
            }
            if (brain.isRtsControlled() || brain.isConstructionControlled()) {
                continue;
            }
            brain.setShopControlled(true);
        }
    }

    private static BlockPos centerOf(BuildingRecord building) {
        return new BlockPos((building.min().getX() + building.max().getX()) / 2,
                building.min().getY(),
                (building.min().getZ() + building.max().getZ()) / 2);
    }

    private static BuildingRecord buildingOf(NpcData data) {
        String id = data.getWorkplaceId();
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return BuildingRegistry.INSTANCE.byId(UUID.fromString(id));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean isShop(BuildingRecord building) {
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.isWork(BuildingWork.SHOP);
    }

    private static boolean isInside(BuildingRecord building, NpcEntity npc) {
        String dimension = npc.level().dimension().location().toString();
        return building.contains(dimension, npc.getX(), npc.getY(), npc.getZ());
    }
}
