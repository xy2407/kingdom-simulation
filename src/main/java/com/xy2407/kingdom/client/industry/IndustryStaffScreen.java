package com.xy2407.kingdom.client.industry;

import java.util.List;

import com.xy2407.kingdom.common.network.industry.IndustryPanelRequestPacket;
import com.xy2407.kingdom.common.network.industry.IndustryStaffActionPacket;
import com.xy2407.kingdom.common.network.industry.IndustryStaffStatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工业用工面板，左侧招人进建筑，右侧把已雇的人分配到各矿坑或林地
 */
@OnlyIn(Dist.CLIENT)
public final class IndustryStaffScreen extends Screen {

    private static final int ROW_HEIGHT = 18;
    private static final int PAGE_SIZE = 8;
    private static final int COLUMN_WIDTH = 190;
    private static final int COLUMN_GAP = 16;

    private static IndustryStaffScreen active;

    private IndustryStaffStatePacket state;
    private int staffPage;
    private int candidatePage;

    private IndustryStaffScreen(IndustryStaffStatePacket state) {
        super(Component.literal("工业用工"));
        this.state = state;
    }

    public static void accept(IndustryStaffStatePacket packet) {
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
        IndustryStaffScreen screen = new IndustryStaffScreen(packet);
        minecraft.setScreen(screen);
        active = screen;
    }

    @Override
    protected void init() {
        int leftX = this.width / 2 - COLUMN_WIDTH - COLUMN_GAP / 2;
        int rightX = this.width / 2 + COLUMN_GAP / 2;
        int top = 56;
        int staffRows = Math.min(PAGE_SIZE, Math.max(0, state.staff().size() - staffPage * PAGE_SIZE));
        for (int i = 0; i < staffRows; i++) {
            IndustryStaffStatePacket.Worker worker = state.staff().get(staffPage * PAGE_SIZE + i);
            int y = top + i * ROW_HEIGHT;
            String label = worker.name() + (worker.assignedName().isBlank()
                    ? " · 待分配" : " · " + worker.assignedName());
            addRenderableWidget(Button.builder(Component.literal(label), button -> cycleAssignment(worker))
                    .bounds(rightX, y, COLUMN_WIDTH - 18, 16).build());
            addRenderableWidget(Button.builder(Component.literal("×"), button -> fire(worker))
                    .bounds(rightX + COLUMN_WIDTH - 16, y, 16, 16).build());
        }
        int candRows = Math.min(PAGE_SIZE, Math.max(0, state.candidates().size() - candidatePage * PAGE_SIZE));
        for (int i = 0; i < candRows; i++) {
            IndustryStaffStatePacket.Worker worker = state.candidates().get(candidatePage * PAGE_SIZE + i);
            int y = top + i * ROW_HEIGHT;
            String note = worker.note().isBlank() ? "" : "（" + worker.note() + "）";
            addRenderableWidget(Button.builder(Component.literal(worker.name() + note),
                    button -> hire(worker)).bounds(leftX, y, COLUMN_WIDTH, 16).build());
        }
        int pageY = top + PAGE_SIZE * ROW_HEIGHT + 4;
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> {
            staffPage = Math.max(0, staffPage - 1);
            rebuildWidgets();
        }).bounds(rightX, pageY, 24, 16).build());
        addRenderableWidget(Button.builder(Component.literal("›"), button -> {
            if ((staffPage + 1) * PAGE_SIZE < state.staff().size()) {
                staffPage++;
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
        addRenderableWidget(Button.builder(Component.literal("作业点设置 →"), button -> {
            PacketDistributor.sendToServer(new IndustryPanelRequestPacket());
            onClose();
        }).bounds(this.width / 2 - 60, pageY + 22, 120, 16).build());
    }

    private void hire(IndustryStaffStatePacket.Worker worker) {
        PacketDistributor.sendToServer(IndustryStaffActionPacket.hire(worker.id()));
    }

    private void fire(IndustryStaffStatePacket.Worker worker) {
        PacketDistributor.sendToServer(IndustryStaffActionPacket.fire(worker.id()));
    }

    private void cycleAssignment(IndustryStaffStatePacket.Worker worker) {
        List<IndustryStaffStatePacket.Plot> plots = state.plots();
        if (plots.isEmpty()) {
            return;
        }
        if (worker.assignedTo() == null) {
            PacketDistributor.sendToServer(
                    IndustryStaffActionPacket.assign(worker.id(), plots.get(0).id()));
            return;
        }
        int index = -1;
        for (int i = 0; i < plots.size(); i++) {
            if (plots.get(i).id().equals(worker.assignedTo())) {
                index = i;
                break;
            }
        }
        if (index < 0 || index + 1 >= plots.size()) {
            PacketDistributor.sendToServer(IndustryStaffActionPacket.unassign(worker.id()));
            return;
        }
        PacketDistributor.sendToServer(
                IndustryStaffActionPacket.assign(worker.id(), plots.get(index + 1).id()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font,
                Component.literal(state.name() + "  Lv." + state.level()),
                centerX, 22, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.literal("工位 " + state.staff().size() + " / " + state.capacity()),
                centerX, 36, 0xFFD479);

        int leftX = centerX - COLUMN_WIDTH - COLUMN_GAP / 2;
        int rightX = centerX + COLUMN_GAP / 2;
        graphics.drawString(this.font, "可雇市民", leftX, 45, 0xA0A0A0);
        graphics.drawString(this.font, "本建筑工人（点击切换所属作业点）", rightX, 45, 0xA0A0A0);

        int listBottom = 56 + PAGE_SIZE * ROW_HEIGHT + 8;
        if (state.plots().isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("还没有作业点，先从「作业点设置」里框选范围"),
                    centerX, listBottom + 6, 0x808080);
        } else {
            StringBuilder line = new StringBuilder("矿坑：");
            for (IndustryStaffStatePacket.Plot plot : state.plots()) {
                line.append(plot.name()).append("(").append(plot.workers()).append(")  ");
            }
            graphics.drawCenteredString(this.font, Component.literal(line.toString()),
                    centerX, listBottom + 6, 0xC0C0C0);
        }
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
