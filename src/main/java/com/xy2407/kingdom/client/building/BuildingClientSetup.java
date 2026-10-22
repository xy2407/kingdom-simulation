package com.xy2407.kingdom.client.building;

import com.mojang.blaze3d.platform.InputConstants;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.cityhall.CityHallScreen;
import com.xy2407.kingdom.client.builder.BuilderBoardScreen;
import com.xy2407.kingdom.client.farm.FarmPanelScreen;
import com.xy2407.kingdom.client.path.NpcPathDebugRenderer;
import com.xy2407.kingdom.client.ranch.RanchPanelScreen;
import com.xy2407.kingdom.client.industry.IndustryPanelScreen;
import com.xy2407.kingdom.client.map.MapOverlayStore;
import com.xy2407.kingdom.client.map.RoadClientStore;
import com.xy2407.kingdom.client.rts.ClientBuildingStore;
import com.xy2407.kingdom.client.structure.StructureDebugKeybind;
import com.xy2407.kingdom.common.network.debug.NpcPathDebugHooks;
import com.xy2407.kingdom.common.network.npc.NpcRosterHooks;
import com.xy2407.kingdom.common.network.industry.IndustryPanelHooks;
import com.xy2407.kingdom.common.network.mining.MiningPanelHooks;
import com.xy2407.kingdom.common.network.building.BuildingMoveHooks;
import com.xy2407.kingdom.common.network.building.BuildingPanelHooks;
import com.xy2407.kingdom.common.network.building.BuildingSiteHooks;
import com.xy2407.kingdom.common.network.building.ResidentHooks;
import com.xy2407.kingdom.common.network.building.RestaurantHooks;
import com.xy2407.kingdom.common.network.builder.BuilderHooks;
import com.xy2407.kingdom.common.network.cityhall.CityHallHooks;
import com.xy2407.kingdom.common.network.mapoverlay.MapOverlayHooks;
import com.xy2407.kingdom.common.network.farm.FarmPanelHooks;
import com.xy2407.kingdom.common.network.ranch.RanchPanelHooks;
import com.xy2407.kingdom.common.network.road.RoadHooks;

import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 建筑放置的客户端接线：注册确认键并挂上各面板的回调。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class BuildingClientSetup {
    public static final KeyMapping CONFIRM_PLACE = new KeyMapping(
            "key.xy2407_kingdom.building_place", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, "key.categories.xy2407_kingdom");

    private BuildingClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CONFIRM_PLACE);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        BuildingSiteHooks.siteSink = BuildingSiteManager::accept;
        BuildingPanelHooks.stateSink = BuildingPanelClient::accept;
        BuilderHooks.openSink = BuilderBoardScreen::acceptOpen;
        BuilderHooks.dataSink = BuilderBoardScreen::accept;
        ResidentHooks.listSink = BuildingPanelClient::acceptResidents;
        RestaurantHooks.menuSink = BuildingPanelClient::acceptRestaurantMenu;
        BuildingMoveHooks.syncSink = ClientBuildingStore::accept;
        IndustryPanelHooks.stateSink = IndustryPanelScreen::accept;
        MiningPanelHooks.setStateSink(IndustryPanelScreen::acceptMines);
        FarmPanelHooks.stateSink = FarmPanelScreen::accept;
        RanchPanelHooks.stateSink = RanchPanelScreen::accept;
        CityHallHooks.openSink = CityHallScreen::open;
        CityHallHooks.dataSink = CityHallScreen::accept;
        MapOverlayHooks.dataSink = MapOverlayStore::accept;
        NpcPathDebugHooks.sink = NpcPathDebugRenderer::accept;
        NpcRosterHooks.rosterSink = StructureDebugKeybind::acceptRoster;
        RoadHooks.loadedSink = RoadClientStore::setLoadedChunks;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BuildingSiteManager.clear();
        NpcPathDebugRenderer.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (CONFIRM_PLACE.consumeClick()) {
            BuildingPlacementManager.confirmAll();
        }
    }
}
