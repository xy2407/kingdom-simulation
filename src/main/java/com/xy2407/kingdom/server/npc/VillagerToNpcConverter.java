package com.xy2407.kingdom.server.npc;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcNameGenerator;
import com.xy2407.kingdom.common.npc.NpcTalent;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 村民转市民：原版村民进世界瞬间被替换为 NPC，建档入库并继承所在区块的领地与阵营
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class VillagerToNpcConverter {

    private static final String DEFAULT_JOB = "村民";

    private VillagerToNpcConverter() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = villager.blockPosition();
        float yRot = villager.getYRot();
        float xRot = villager.getXRot();
        event.setCanceled(true);
        level.getServer().execute(() -> spawnNpc(level, pos, yRot, xRot));
    }

    private static void spawnNpc(ServerLevel level, BlockPos pos, float yRot, float xRot) {
        if (!level.isLoaded(pos) || hasNpcAt(level, pos)) {
            return;
        }
        EntityType<NpcEntity> type = ModEntities.NPC.get();
        NpcEntity npc = type.create(level);
        if (npc == null) {
            return;
        }
        npc.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, yRot, xRot);
        npc.setPersistenceRequired();

        NpcData data = new NpcData(UUID.randomUUID());
        data.setGender(level.random.nextBoolean() ? "MALE" : "FEMALE");
        data.setName(NpcNameGenerator.generate(data.getGender()));
        NpcTalent.roll(data, level.random);
        data.setJob(DEFAULT_JOB);
        String dimension = level.dimension().location().toString();
        data.setDimension(dimension);
        data.setEntityUuid(npc.getUUID());

        Territory territory = TerritoryRegistry.INSTANCE.byChunk(dimension,
                pos.getX() >> 4, pos.getZ() >> 4);
        if (territory != null) {
            data.setTerritoryId(territory.id());
            data.setNationId(territory.nationId());
        }

        NpcManager.INSTANCE.registerNpc(data);
        npc.setCitizenId(data.getId());
        level.addFreshEntity(npc);
    }

    private static boolean hasNpcAt(ServerLevel level, BlockPos pos) {
        for (Entity entity : level.getEntities((Entity) null, new AABB(pos).inflate(0.5D),
                candidate -> candidate instanceof NpcEntity)) {
            return entity != null;
        }
        return false;
    }
}
