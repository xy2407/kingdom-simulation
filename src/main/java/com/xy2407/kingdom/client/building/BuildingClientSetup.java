package com.xy2407.kingdom.client.building;

import com.mojang.blaze3d.platform.InputConstants;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.cityhall.CityHallScreen;
import com.xy2407.kingdom.client.farm.FarmPanelScreen;
import com.xy2407.kingdom.client.ranch.RanchPanelScreen;
import com.xy2407.kingdom.client.map.WorldMapClientData;
import com.xy2407.kingdom.common.network.building.BuildingPanelHooks;
import com.xy2407.kingdom.common.network.building.BuildingSiteHooks;
import com.xy2407.kingdom.common.network.cityhall.CityHallHooks;
import com.xy2407.kingdom.common.network.farm.FarmPanelHooks;
import com.xy2407.kingdom.common.network.map.WorldMapHooks;
import com.xy2407.kingdom.common.network.ranch.RanchPanelHooks;

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
 * 建筑放置客户端接线
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
        FarmPanelHooks.stateSink = FarmPanelScreen::accept;
        RanchPanelHooks.stateSink = RanchPanelScreen::accept;
        CityHallHooks.openSink = CityHallScreen::open;
        CityHallHooks.dataSink = CityHallScreen::accept;
        WorldMapHooks.dataSink = WorldMapClientData::accept;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BuildingSiteManager.clear();
        /* 地图数据是服务端下发的，换存档就没意义了，清掉免得画上一局的地表 */
        WorldMapClientData.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (CONFIRM_PLACE.consumeClick()) {
            BuildingPlacementManager.confirmAll();
        }
    }
}
