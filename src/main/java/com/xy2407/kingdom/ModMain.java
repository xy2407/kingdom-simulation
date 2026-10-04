package com.xy2407.kingdom;

import com.xy2407.kingdom.common.block.ModBlocks;
import com.xy2407.kingdom.common.block.ModCreativeTabs;
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.persistence.BuildingSchema;
import com.xy2407.kingdom.common.persistence.FarmLandSchema;
import com.xy2407.kingdom.common.persistence.FarmPlotSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NationSchema;
import com.xy2407.kingdom.common.persistence.NpcSchema;
import com.xy2407.kingdom.common.persistence.PlayerProfileSchema;
import com.xy2407.kingdom.common.persistence.RanchSchema;
import com.xy2407.kingdom.common.persistence.TerritorySchema;
import com.xy2407.kingdom.server.ServerLifecycle;
import com.xy2407.kingdom.server.building.BuildingServerTick;
import com.xy2407.kingdom.server.date.DateServerTick;
import com.xy2407.kingdom.server.npc.NpcJoinWorldHandler;
import com.xy2407.kingdom.server.npc.NpcServerTick;
import com.xy2407.kingdom.server.path.SnapshotEvents;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * 模组主入口，仅注册事件总线
 */
@Mod(ModMain.MOD_ID)
public final class ModMain {
    public static final String MOD_ID = "xy2407_kingdom";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ModMain(IEventBus modEventBus, ModContainer modContainer) {
        KingdomPersistence.registerMigration(new NpcSchema());
        KingdomPersistence.registerMigration(new BuildingSchema());
        KingdomPersistence.registerMigration(new FarmPlotSchema());
        KingdomPersistence.registerMigration(new FarmLandSchema());
        KingdomPersistence.registerMigration(new RanchSchema());
        /* 阵营与领地：版本号全局线性，接在现有最大值 11 之后 */
        KingdomPersistence.registerMigration(new NationSchema());
        KingdomPersistence.registerMigration(new TerritorySchema());
        KingdomPersistence.registerMigration(new PlayerProfileSchema());
        NeoForge.EVENT_BUS.register(ServerLifecycle.class);
        NeoForge.EVENT_BUS.register(DateServerTick.class);
        NeoForge.EVENT_BUS.register(NpcJoinWorldHandler.class);
        NeoForge.EVENT_BUS.register(NpcServerTick.class);
        NeoForge.EVENT_BUS.register(SnapshotEvents.class);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
    }
}


