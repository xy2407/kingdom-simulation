package com.xy2407.kingdom;

import com.xy2407.kingdom.common.block.ModBlocks;
import com.xy2407.kingdom.common.block.ModCreativeTabs;
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.persistence.BuildingSchema;
import com.xy2407.kingdom.common.persistence.BuildingStorageSchema;
import com.xy2407.kingdom.common.persistence.SnapshotUnitSchema;
import com.xy2407.kingdom.common.persistence.FarmLandSchema;
import com.xy2407.kingdom.common.persistence.FarmPlotSchema;
import com.xy2407.kingdom.common.persistence.GovernmentAffairSchema;
import com.xy2407.kingdom.common.persistence.RoadSchema;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NationSchema;
import com.xy2407.kingdom.common.persistence.NpcSchema;
import com.xy2407.kingdom.common.persistence.PlayerProfileSchema;
import com.xy2407.kingdom.common.persistence.MiningPlotSchema;
import com.xy2407.kingdom.common.persistence.RegionTaskSchema;
import com.xy2407.kingdom.common.persistence.RestaurantOrderSchema;
import com.xy2407.kingdom.common.persistence.RestaurantSeatSchema;
import com.xy2407.kingdom.common.persistence.ShopSchema;
import com.xy2407.kingdom.common.persistence.TerritoryTreasurySchema;
import com.xy2407.kingdom.common.persistence.MoneyLedgerSchema;
import com.xy2407.kingdom.common.persistence.PlayerGrantSchema;
import com.xy2407.kingdom.common.persistence.TerritoryVitalsSchema;
import com.xy2407.kingdom.common.persistence.RanchSchema;
import com.xy2407.kingdom.common.persistence.TerritorySchema;
import com.xy2407.kingdom.server.ServerLifecycle;
import com.xy2407.kingdom.server.building.BuildingServerTick;
import com.xy2407.kingdom.server.date.DateServerTick;
import com.xy2407.kingdom.server.npc.NpcChunkEvents;
import com.xy2407.kingdom.server.npc.NpcJoinWorldHandler;
import com.xy2407.kingdom.server.npc.NpcServerTick;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * 模组主入口，注册数据迁移、事件总线与实体方块物品
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
        KingdomPersistence.registerMigration(new NationSchema());
        KingdomPersistence.registerMigration(new TerritorySchema());
        KingdomPersistence.registerMigration(new PlayerProfileSchema());
        KingdomPersistence.registerMigration(new TerritoryVitalsSchema());
        KingdomPersistence.registerMigration(new BuildingStorageSchema());
        KingdomPersistence.registerMigration(new MiningPlotSchema());
        KingdomPersistence.registerMigration(new SnapshotUnitSchema());
        KingdomPersistence.registerMigration(new RestaurantOrderSchema());
        KingdomPersistence.registerMigration(new RestaurantSeatSchema());
        KingdomPersistence.registerMigration(new ShopSchema());
        KingdomPersistence.registerMigration(new TerritoryTreasurySchema());
        KingdomPersistence.registerMigration(new MoneyLedgerSchema());
        KingdomPersistence.registerMigration(new PlayerGrantSchema());
        KingdomPersistence.registerMigration(new com.xy2407.kingdom.common.persistence.RtsTeamSchema());
        KingdomPersistence.registerMigration(new com.xy2407.kingdom.common.persistence.RtsTeamMemberSchema());
        KingdomPersistence.registerMigration(new com.xy2407.kingdom.common.persistence.WildStructureSchema());
        KingdomPersistence.registerMigration(new GovernmentAffairSchema());
        KingdomPersistence.registerMigration(new RoadSchema());
        KingdomPersistence.registerMigration(new RegionTaskSchema());
        KingdomPersistence.registerMigration(new com.xy2407.kingdom.common.persistence.PlotAreaSchema());
        KingdomPersistence.registerMigration(
                new com.xy2407.kingdom.common.persistence.BuildingTaskSchema());
        KingdomPersistence.registerMigration(
                new com.xy2407.kingdom.common.persistence.ForgeTaskSchema());
        KingdomPersistence.registerMigration(
                new com.xy2407.kingdom.common.persistence.FreightOrderSchema());
        KingdomPersistence.registerMigration(
                new com.xy2407.kingdom.common.persistence.TouristSchema());
        KingdomPersistence.registerMigration(
                new com.xy2407.kingdom.common.persistence.WarehouseLinkSchema());
        NeoForge.EVENT_BUS.register(ServerLifecycle.class);
        NeoForge.EVENT_BUS.register(DateServerTick.class);
        NeoForge.EVENT_BUS.register(NpcJoinWorldHandler.class);
        NeoForge.EVENT_BUS.register(NpcChunkEvents.class);
        NeoForge.EVENT_BUS.register(NpcServerTick.class);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        com.xy2407.kingdom.common.block.ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        modEventBus.addListener(FMLCommonSetupEvent.class,
                event -> event.enqueueWork(
                        com.xy2407.kingdom.common.building.BlueprintDeployer::deploy));
    }
}


