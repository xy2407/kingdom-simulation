package com.xy2407.kingdom.client.structure;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.ModMain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 建筑预览调试叠加层，把调试行与实时状态画在 AUI 页面之上
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class StructureDebugOverlay {

    private static final int LINE_HEIGHT = 10;
    private static final int MAX_TEXT = 150;
    private static final int HEADER_COLOR = 0xFF7CFC9A;
    private static final int TEXT_COLOR = 0xFFF2F25C;
    private static final int BACKDROP_COLOR = 0xB4000000;

    private StructureDebugOverlay() {
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !(event.getScreen() instanceof ApricityScreen)) {
            return;
        }
        List<String> shown = new ArrayList<>();
        shown.add(header());
        for (String line : StructureDebugChat.lines()) {
            shown.add(line.length() > MAX_TEXT ? line.substring(0, MAX_TEXT) + "…" : line);
        }
        int width = 0;
        for (String line : shown) {
            width = Math.max(width, minecraft.font.width(line));
        }
        GuiGraphics graphics = event.getGuiGraphics();
        RenderSystem.disableScissor();
        graphics.fill(2, 2, 10 + width, 8 + shown.size() * LINE_HEIGHT, BACKDROP_COLOR);
        int y = 5;
        for (int index = 0; index < shown.size(); index++) {
            graphics.drawString(minecraft.font, shown.get(index), 6, y,
                    index == 0 ? HEADER_COLOR : TEXT_COLOR, false);
            y += LINE_HEIGHT;
        }
    }

    private static String header() {
        return String.format("建筑=%s 方块=%d 档位=%s 段=%d FBO=%s 存活=%s",
                StructurePreviewTarget.currentStructureId(),
                StructurePreviewTarget.currentBlockCount(),
                StructurePreviewTarget.currentLod(),
                StructurePreviewTarget.currentSectionCount(),
                StructurePreviewTarget.isReady() ? "就绪" : "未创建",
                StructurePreviewTarget.isAlive() ? "是" : "否");
    }
}