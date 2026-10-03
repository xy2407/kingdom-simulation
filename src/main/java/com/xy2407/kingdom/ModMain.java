package com.xy2407.kingdom;

import com.xy2407.kingdom.common.block.ModBlocks;
import com.xy2407.kingdom.common.block.ModCreativeTabs;
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.persistence.BuildingSchema;
import com.xy2407.kingdom.common.persistence.FarmPlotSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NpcSchema;
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


