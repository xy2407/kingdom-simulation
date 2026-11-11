package com.xy2407.kingdom.client.military;

import com.xy2407.kingdom.common.network.military.MilitaryPanelActionPacket;
import com.xy2407.kingdom.common.network.military.MilitaryPanelStatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 军事建筑的驻军面板：左边征召市民当兵，右边看驻军，底部一键从仓库发装备。
 */
@OnlyIn(Dist.CLIENT)
public final class MilitaryPanelScreen extends Screen {

    private static final int ROW_HEIGHT = 18;
    private static final int PAGE_SIZE = 9;
    private static final int COLUMN_WIDTH = 190;
    private static final int COLUMN_GAP = 16;

    private static MilitaryPanelScreen active;

    private MilitaryPanelStatePacket state;
    private int soldierPage;
    private int candidatePage;

    private MilitaryPanelScreen(MilitaryPanelStatePacket state) {
        super(Component.literal("驻军"));
        this.state = state;
    }

    public static void accept(MilitaryPanelStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (!packet.inside()) {
            if (active != null) {
                active.onClose();
                active = null;
            }
            return;
        }
        if (active != null && active.state.buildingId() != null
                && active.state.buildingId().equals(packet.buildingId())) {
            active.state = packet;
            active.rebuildWidgets();
            return;
        }
        MilitaryPanelScreen screen = new MilitaryPanelScreen(packet);
        minecraft.setScreen(screen);
        active = screen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int leftX = centerX - COLUMN_WIDTH - COLUMN_GAP / 2;
        int rightX = centerX + COLUMN_GAP / 2;
        int top = 58;

        int soldierRows = Math.min(PAGE_SIZE, Math.max(0, state.soldiers().size() - soldierPage * PAGE_SIZE));
        for (int i = 0; i < soldierRows; i++) {
            MilitaryPanelStatePacket.Member member = state.soldiers().get(soldierPage * PAGE_SIZE + i);
            int y = top + i * ROW_HEIGHT;
            String label = member.name() + (member.note().isBlank() ? "" : " · " + member.note());
            addRenderableWidget(Button.builder(Component.literal(label), button -> dismiss(member))
                    .bounds(rightX, y, COLUMN_WIDTH, 16).build());
        }

        int candidateRows = Math.min(PAGE_SIZE,
                Math.max(0, state.candidates().size() - candidatePage * PAGE_SIZE));
        for (int i = 0; i < candidateRows; i++) {
            MilitaryPanelStatePacket.Member member =
                    state.candidates().get(candidatePage * PAGE_SIZE + i);
            int y = top + i * ROW_HEIGHT;
            String note = member.note().isBlank() ? "" : "（" + member.note() + "）";
            addRenderableWidget(Button.builder(Component.literal(member.name() + note),
                    button -> hire(member)).bounds(leftX, y, COLUMN_WIDTH, 16).build());
        }

        int pageY = top + PAGE_SIZE * ROW_HEIGHT + 4;
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> {
            soldierPage = Math.max(0, soldierPage - 1);
            rebuildWidgets();
        }).bounds(rightX, pageY, 24, 16).build());
        addRenderableWidget(Button.builder(Component.literal("›"), button -> {
            if ((soldierPage + 1) * PAGE_SIZE < state.soldiers().size()) {
                soldierPage++;
                rebuildWidgets();
            }
        }).bounds(rightX + 28, pageY, 24, 16).build());
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> {
            candidatePage = Math.max(0, candidatePage - 1);
            rebuildWidgets();
        }).bounds(leftX, pageY, 24, 16).build());
        addRenderableWidget(Button.builder(Component.literal("›"), button -> {
            if ((candidatePage + 1) * PAGE_SIZE < state.candidates().size()) {
                candidatePage++;
                rebuildWidgets();
            }
        }).bounds(leftX + 28, pageY, 24, 16).build());
        addRenderableWidget(Button.builder(Component.literal("一键穿戴"), button -> {
            PacketDistributor.sendToServer(MilitaryPanelActionPacket.autoEquip());
        }).bounds(centerX - 60, pageY + 24, 120, 18).build());
    }

    private void hire(MilitaryPanelStatePacket.Member member) {
        PacketDistributor.sendToServer(MilitaryPanelActionPacket.hire(member.id()));
    }

    private void dismiss(MilitaryPanelStatePacket.Member member) {
        PacketDistributor.sendToServer(MilitaryPanelActionPacket.dismiss(member.id()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font,
                Component.literal(state.name() + "  Lv." + state.level()), centerX, 22, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.literal("驻军 " + state.soldiers().size() + " / " + state.capacity()),
                centerX, 36, 0xFFD479);

        int leftX = centerX - COLUMN_WIDTH - COLUMN_GAP / 2;
        int rightX = centerX + COLUMN_GAP / 2;
        graphics.drawString(this.font, "可征召市民", leftX, 47, 0xA0A0A0);
        graphics.drawString(this.font, "驻军（点击遣散）", rightX, 47, 0xA0A0A0);

        int bottom = 58 + PAGE_SIZE * ROW_HEIGHT + 8;
        graphics.drawCenteredString(this.font,
                Component.literal("仓库：食物 " + state.foodCount() + " · 装备 " + state.gearCount()),
                centerX, bottom + 26, 0xC0C0C0);
    }

    @Override
    public void onClose() {
        if (active == this) {
            active = null;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
