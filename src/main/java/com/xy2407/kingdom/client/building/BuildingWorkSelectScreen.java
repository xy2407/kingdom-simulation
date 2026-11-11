package com.xy2407.kingdom.client.building;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.network.building.BuildingWorkSelectPacket;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建筑定型界面，玩家在这里为刚盖好的工业商业军事建筑选定具体工种
 */
@OnlyIn(Dist.CLIENT)
public final class BuildingWorkSelectScreen extends Screen {

    private static final int BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;

    private final UUID buildingId;
    private final String category;
    private final List<BuildingWork> works;

    public BuildingWorkSelectScreen(UUID buildingId, String category, List<String> workNames) {
        super(Component.literal("选择建筑用途"));
        this.buildingId = buildingId;
        this.category = category == null ? "" : category;
        List<BuildingWork> parsed = new ArrayList<>(workNames.size());
        for (String name : workNames) {
            BuildingWork work = BuildingWork.parse(name);
            if (work != null) {
                parsed.add(work);
            }
        }
        this.works = List.copyOf(parsed);
    }

    @Override
    protected void init() {
        int total = works.size() * BUTTON_HEIGHT + Math.max(0, works.size() - 1) * BUTTON_GAP;
        int startY = this.height / 2 - total / 2 + 10;
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        for (int i = 0; i < works.size(); i++) {
            BuildingWork work = works.get(i);
            int y = startY + i * (BUTTON_HEIGHT + BUTTON_GAP);
            addRenderableWidget(Button.builder(Component.literal(work.label()), button -> choose(work))
                    .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());
        }
    }

    private void choose(BuildingWork work) {
        PacketDistributor.sendToServer(new BuildingWorkSelectPacket(buildingId, work.name()));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 46, 0xFFFFFF);
        String hint = "这座" + category + "建筑还没定用途，选一个：";
        graphics.drawCenteredString(this.font, Component.literal(hint), this.width / 2,
                this.height / 2 - 30, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
