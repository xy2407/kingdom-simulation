package com.xy2407.kingdom.client.forge;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.network.forge.ForgePanelActionPacket;
import com.xy2407.kingdom.common.network.forge.ForgePanelRequestPacket;
import com.xy2407.kingdom.common.network.forge.ForgePanelStatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 锻造坊面板，左侧搜索并选择产物，右侧看委托步骤进度，底部提交数量与订单
 */
@OnlyIn(Dist.CLIENT)
public final class ForgePanelScreen extends Screen {

    private static final int ROW_HEIGHT = 16;
    private static final int PAGE_SIZE = 8;
    private static final int LIST_WIDTH = 210;
    private static final int[] AMOUNTS = {1, 8, 16, 32, 64};

    private static ForgePanelScreen active;

    private final Button[] itemButtons = new Button[PAGE_SIZE];

    private ForgePanelStatePacket state;
    private EditBox search;
    private String filter = "";
    private String selected;
    private int page;
    private int amountIndex;

    private ForgePanelScreen(ForgePanelStatePacket state) {
        super(Component.literal("锻造坊"));
        this.state = state;
    }

    public static void accept(ForgePanelStatePacket packet) {
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
            active.refreshLabels();
            return;
        }
        ForgePanelScreen screen = new ForgePanelScreen(packet);
        minecraft.setScreen(screen);
        active = screen;
    }

    @Override
    protected void init() {
        int left = 24;
        int top = 40;
        if (search == null) {
            search = new EditBox(this.font, left, top, LIST_WIDTH, 16, Component.literal("搜索物品"));
            search.setValue(filter);
            search.setResponder(text -> {
                filter = text == null ? "" : text.toLowerCase();
                page = 0;
                refreshLabels();
            });
        }
        search.setX(left);
        search.setY(top);
        search.setWidth(LIST_WIDTH);
        addRenderableWidget(search);

        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            final int index = slot;
            itemButtons[slot] = Button.builder(Component.literal(""), button -> select(index))
                    .bounds(left, top + 22 + slot * ROW_HEIGHT, LIST_WIDTH, 14).build();
            addRenderableWidget(itemButtons[slot]);
        }
        int navY = top + 22 + PAGE_SIZE * ROW_HEIGHT + 4;
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> {
            if (page > 0) {
                page--;
                refreshLabels();
            }
        }).bounds(left, navY, 30, 14).build());
        addRenderableWidget(Button.builder(Component.literal("›"), button -> {
            if ((page + 1) * PAGE_SIZE < shownItems().size()) {
                page++;
                refreshLabels();
            }
        }).bounds(left + 36, navY, 30, 14).build());
        addRenderableWidget(Button.builder(Component.literal("×" + AMOUNTS[amountIndex]), button -> {
            amountIndex = (amountIndex + 1) % AMOUNTS.length;
            rebuildWidgets();
        }).bounds(left + LIST_WIDTH - 44, navY, 44, 14).build());
        addRenderableWidget(Button.builder(Component.literal("提交订单"), button -> submit())
                .bounds(left, navY + 20, LIST_WIDTH, 16).build());

        int right = left + LIST_WIDTH + 20;
        if (!state.tasks().isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("取消当前委托"), button ->
                    PacketDistributor.sendToServer(ForgePanelActionPacket.cancel()))
                    .bounds(right, navY + 20, 160, 16).build());
        }
        addRenderableWidget(Button.builder(Component.literal("刷新"), button ->
                PacketDistributor.sendToServer(new ForgePanelRequestPacket()))
                .bounds(right, navY, 60, 14).build());
        refreshLabels();
    }

    private void select(int slot) {
        List<String> shown = shownItems();
        int index = page * PAGE_SIZE + slot;
        if (index < shown.size()) {
            selected = shown.get(index);
            refreshLabels();
        }
    }

    private void refreshLabels() {
        List<String> shown = shownItems();
        int maxPage = Math.max(0, (shown.size() - 1) / PAGE_SIZE);
        if (page > maxPage) {
            page = maxPage;
        }
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            Button button = itemButtons[slot];
            if (button == null) {
                continue;
            }
            int index = page * PAGE_SIZE + slot;
            boolean visible = index < shown.size();
            button.visible = visible;
            button.active = visible;
            if (!visible) {
                continue;
            }
            String itemId = shown.get(index);
            button.setMessage(Component.literal(
                    (itemId.equals(selected) ? "§e" : "") + shortName(itemId)));
        }
    }

    private void submit() {
        if (selected == null || selected.isBlank()) {
            return;
        }
        PacketDistributor.sendToServer(ForgePanelActionPacket.submit(selected,
                AMOUNTS[amountIndex]));
    }

    private List<String> shownItems() {
        List<String> result = new ArrayList<>();
        for (String itemId : state.catalog()) {
            if (!filter.isBlank() && !itemId.toLowerCase().contains(filter)) {
                continue;
            }
            result.add(itemId);
        }
        return result;
    }

    private static String shortName(String itemId) {
        int colon = itemId.indexOf(':');
        return colon < 0 ? itemId : itemId.substring(colon + 1);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, "锻造坊  Lv." + state.level(), this.width / 2, 14,
                0xFFFFFF);
        graphics.drawString(this.font, state.name(), 24, 24, 0xA0A0A0, false);
        int right = 24 + LIST_WIDTH + 20;
        int y = 40;
        if (state.tasks().isEmpty()) {
            graphics.drawString(this.font, "当前没有委托", right, y, 0x909090, false);
            return;
        }
        for (ForgePanelStatePacket.Task task : state.tasks()) {
            graphics.drawString(this.font, "目标 " + shortName(task.target()) + " × "
                    + task.requested() + "  [" + task.state() + "]", right, y, 0xFFFFFF, false);
            y += 12;
            if (!task.missing().isBlank()) {
                graphics.drawString(this.font, "缺料 " + task.missing(), right, y, 0xFF8080, false);
                y += 12;
            }
            for (ForgePanelStatePacket.Step step : task.steps()) {
                String line = step.kind() + " " + shortName(step.output()) + "  "
                        + step.done() + "/" + step.total();
                graphics.drawString(this.font, line, right, y, 0xB0C0B0, false);
                y += 11;
            }
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
