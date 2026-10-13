package com.xy2407.kingdom.client.rts;

import com.xy2407.kingdom.client.building.BuildingPlacementManager;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * RTS 的 HUD 图层，每帧推进框选、相机拖拽与建筑投影跟随
 */
@OnlyIn(Dist.CLIENT)
public final class RtsHudLayer implements LayeredDraw.Layer {

    public static final RtsHudLayer INSTANCE = new RtsHudLayer();
    public static final String ID = "rts_hud";

    private RtsHudLayer() {
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!RtsModeManager.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (RtsModeManager.getSubMode() == RtsSubMode.ROAD_TERRITORY) {
            RtsModeManager.onMiddleDrag();
            RtsRoadState.tick();
            return;
        }
        RtsModeManager.onLeftMouseDrag();
        RtsModeManager.onMiddleDrag();
        RtsWaypointState.pollKey();
        BuildingPlacementManager.tickRtsFollow();
        BuildingPlacementManager.tickPendingDrag();
    }
}
