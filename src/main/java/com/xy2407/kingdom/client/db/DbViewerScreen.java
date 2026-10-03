package com.xy2407.kingdom.client.db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xy2407.kingdom.common.network.db.DbViewerDataPacket;
import com.xy2407.kingdom.common.network.db.DbViewerRequestPacket;
import com.xy2407.kingdom.server.db.DbViewerService;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 数据库查看器界面，分页切换表并滚动浏览全部字段
 */
public final class DbViewerScreen extends Screen {
    private static final int ROW_HEIGHT = 14;
    private static final int HEADER_HEIGHT = 16;
    private static final int COLUMN_WIDTH = 130;
    private static final int REFRESH_INTERVAL = 60;
    private static final int SLIDER_WIDTH = 8;

    private static final Map<String, DbViewerDataPacket> CACHE = new HashMap<>();
    private static final Map<String, List<List<String>>> PENDING_ROWS = new HashMap<>();
    private static final Map<String, List<String>> PENDING_COLUMNS = new HashMap<>();

    private final List<String> tables;
    private String table;
    private int refreshIn;
    private double scrollX;
    private double scrollY;
    private boolean draggingX;
    private boolean draggingY;
    private int tableX;
    private int tableY;
    private int tableWidth;
    private int tableHeight;
    private int refreshButtonX;
    private double maxScrollX;
    private double maxScrollY;

    public DbViewerScreen() {
        super(Component.literal("数据库查看器"));
        tables = DbViewerService.TABLES;
        table = DbViewerService.TABLE_NPC;
    }

    public static void accept(DbViewerDataPacket packet) {
        String key = packet.table();
        if (packet.chunkIndex() == 0) {
            PENDING_COLUMNS.put(key, packet.columns());
            PENDING_ROWS.put(key, new ArrayList<>());
        }
        List<List<String>> rows = PENDING_ROWS.get(key);
        List<String> columns = PENDING_COLUMNS.get(key);
        if (rows == null || columns == null) {
            return;
        }
        rows.addAll(packet.rows());
        if (packet.chunkIndex() >= packet.chunkTotal() - 1) {
            CACHE.put(key, new DbViewerDataPacket(key, 0, 1, columns, rows));
            PENDING_ROWS.remove(key);
            PENDING_COLUMNS.remove(key);
        }
    }

    @Override
    protected void init() {
        layout();
        refreshIn = 0;
    }

    @Override
    public void tick() {
        if (--refreshIn <= 0) {
            request();
            refreshIn = REFRESH_INTERVAL;
        }
    }

    private void request() {
        DbViewerRequestPacket packet = new DbViewerRequestPacket(table);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
    }

    private void layout() {
        tableX = 8;
        tableY = 46;
        tableWidth = Math.max(120, this.width - 30);
        tableHeight = Math.max(60, this.height - tableY - 26);
    }

    private DbViewerDataPacket snapshot() {
        return CACHE.get(table);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_GRAVE_ACCENT) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        layout();
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        renderTabs(graphics);
        DbViewerDataPacket snapshot = snapshot();
        String status = snapshot == null ? "加载中…"
                : String.format("%s：%d 列 × %d 行", table, snapshot.columns().size(), snapshot.rows().size());
        graphics.drawString(this.font, status, tableX, tableY - 14, 0xFFC8C8C8, false);
        graphics.renderOutline(tableX - 1, tableY - 1, tableWidth + 2, tableHeight + 2, 0xFF555555);
        graphics.enableScissor(tableX, tableY, tableX + tableWidth, tableY + tableHeight);
        renderTable(graphics, snapshot);
        graphics.disableScissor();
        renderSliders(graphics, snapshot);
    }

    private void renderTabs(GuiGraphics graphics) {
        int x = tableX;
        for (String name : tables) {
            int width = this.font.width(name) + 20;
            boolean active = name.equals(table);
            graphics.fill(x, 20, x + width, 40, active ? 0xFF33502F : 0xFF333333);
            graphics.renderOutline(x, 20, width, 20, active ? 0xFF8FBF6F : 0xFF666666);
            graphics.drawCenteredString(this.font, name, x + width / 2, 27, active ? 0xFFFFFF80 : 0xFFCCCCCC);
            x += width + 6;
        }
        refreshButtonX = x;
        graphics.fill(x, 20, x + 44, 40, 0xFF333333);
        graphics.renderOutline(x, 20, 44, 20, 0xFF666666);
        graphics.drawCenteredString(this.font, "刷新", x + 22, 27, 0xFFCCCCCC);
    }

    private void renderTable(GuiGraphics graphics, DbViewerDataPacket snapshot) {
        List<String> columns = snapshot == null ? List.of() : snapshot.columns();
        List<List<String>> rows = snapshot == null ? List.of() : snapshot.rows();
        int first = (int) (scrollY / ROW_HEIGHT);
        int visible = tableHeight / ROW_HEIGHT + 1;
        for (int i = first; i < rows.size() && i <= first + visible; i++) {
            int y = tableY + HEADER_HEIGHT + i * ROW_HEIGHT - (int) scrollY;
            if (y + ROW_HEIGHT < tableY + HEADER_HEIGHT || y > tableY + tableHeight) {
                continue;
            }
            if (i % 2 == 0) {
                graphics.fill(tableX, y, tableX + tableWidth, y + ROW_HEIGHT, 0xFF262626);
            }
            List<String> row = rows.get(i);
            for (int j = 0; j < row.size(); j++) {
                int x = tableX + j * COLUMN_WIDTH - (int) scrollX;
                if (x + COLUMN_WIDTH < tableX || x > tableX + tableWidth) {
                    continue;
                }
                graphics.drawString(this.font, clip(row.get(j)), x + 4, y + 3, 0xFFE0E0E0, false);
            }
        }
        for (int j = 0; j < columns.size(); j++) {
            int x = tableX + j * COLUMN_WIDTH - (int) scrollX;
            if (x + COLUMN_WIDTH < tableX || x > tableX + tableWidth) {
                continue;
            }
            graphics.fill(x, tableY, x + COLUMN_WIDTH, tableY + HEADER_HEIGHT, 0xFF2B2B2B);
            graphics.drawString(this.font, clip(columns.get(j)), x + 4, tableY + 4, 0xFFE0C060, false);
        }
    }

    private String clip(String text) {
        return this.font.plainSubstrByWidth(text == null ? "" : text, COLUMN_WIDTH - 8);
    }

    private void renderSliders(GuiGraphics graphics, DbViewerDataPacket snapshot) {
        int columns = snapshot == null ? 0 : snapshot.columns().size();
        int rows = snapshot == null ? 0 : snapshot.rows().size();
        maxScrollY = Math.max(0.0, rows * (double) ROW_HEIGHT - (tableHeight - HEADER_HEIGHT));
        maxScrollX = Math.max(0.0, columns * (double) COLUMN_WIDTH - tableWidth);
        scrollY = Math.min(Math.max(scrollY, 0), maxScrollY);
        scrollX = Math.min(Math.max(scrollX, 0), maxScrollX);
        int trackX = tableX + tableWidth + 4;
        graphics.fill(trackX, tableY, trackX + SLIDER_WIDTH, tableY + tableHeight, 0xFF222222);
        if (maxScrollY > 0) {
            int thumbHeight = thumb(tableHeight, rows * (double) ROW_HEIGHT + HEADER_HEIGHT);
            int thumbY = tableY + (int) ((tableHeight - thumbHeight) * (scrollY / maxScrollY));
            graphics.fill(trackX, thumbY, trackX + SLIDER_WIDTH, thumbY + thumbHeight, draggingY ? 0xFF9FBF9F : 0xFF6F8F6F);
        }
        int trackY = tableY + tableHeight + 4;
        graphics.fill(tableX, trackY, tableX + tableWidth, trackY + SLIDER_WIDTH, 0xFF222222);
        if (maxScrollX > 0) {
            int thumbWidth = thumb(tableWidth, columns * (double) COLUMN_WIDTH);
            int thumbX = tableX + (int) ((tableWidth - thumbWidth) * (scrollX / maxScrollX));
            graphics.fill(thumbX, trackY, thumbX + thumbWidth, trackY + SLIDER_WIDTH, draggingX ? 0xFF9FBF9F : 0xFF6F8F6F);
        }
    }

    private static int thumb(int track, double content) {
        if (content <= 0) {
            return track;
        }
        return Math.max(24, (int) (track * (track / content)));
    }

    private boolean inVerticalTrack(double mouseX, double mouseY) {
        return mouseX >= tableX + tableWidth && mouseX <= tableX + tableWidth + SLIDER_WIDTH
                && mouseY >= tableY && mouseY <= tableY + tableHeight;
    }

    private boolean inHorizontalTrack(double mouseX, double mouseY) {
        return mouseY >= tableY + tableHeight && mouseY <= tableY + tableHeight + SLIDER_WIDTH
                && mouseX >= tableX && mouseX <= tableX + tableWidth;
    }

    private void applyVerticalDrag(double mouseY) {
        scrollY = Math.min(Math.max((mouseY - tableY) / (double) tableHeight * maxScrollY, 0), maxScrollY);
    }

    private void applyHorizontalDrag(double mouseX) {
        scrollX = Math.min(Math.max((mouseX - tableX) / (double) tableWidth * maxScrollX, 0), maxScrollX);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && handleTopBar(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && inVerticalTrack(mouseX, mouseY)) {
            draggingY = true;
            applyVerticalDrag(mouseY);
            return true;
        }
        if (button == 0 && inHorizontalTrack(mouseX, mouseY)) {
            draggingX = true;
            applyHorizontalDrag(mouseX);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleTopBar(double mouseX, double mouseY) {
        if (mouseY < 20 || mouseY > 40) {
            return false;
        }
        int x = tableX;
        for (String name : tables) {
            int width = this.font.width(name) + 20;
            if (mouseX >= x && mouseX <= x + width) {
                if (!name.equals(table)) {
                    table = name;
                    scrollX = 0;
                    scrollY = 0;
                    request();
                }
                return true;
            }
            x += width + 6;
        }
        if (mouseX >= refreshButtonX && mouseX <= refreshButtonX + 44) {
            request();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingY) {
            applyVerticalDrag(mouseY);
            return true;
        }
        if (draggingX) {
            applyHorizontalDrag(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingY = false;
        draggingX = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double xAmount, double yAmount) {
        if (mouseX >= tableX && mouseX <= tableX + tableWidth + SLIDER_WIDTH
                && mouseY >= tableY && mouseY <= tableY + tableHeight + SLIDER_WIDTH) {
            scrollY = Math.min(Math.max(scrollY - yAmount * 40, 0), maxScrollY);
            scrollX = Math.min(Math.max(scrollX + xAmount * 40, 0), maxScrollX);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, xAmount, yAmount);
    }

}
