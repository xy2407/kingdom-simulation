package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.Rect;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * 身份卡片的像素格子进度条元素，按 type 取色阶并缓存像素表。
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(NpcBarElement.TAG_NAME)
public class NpcBarElement extends Element {

    public static final String TAG_NAME = "NPC-BAR";

    public static final String ATTRIBUTE_VALUE = "value";
    public static final String ATTRIBUTE_MAX = "max";

    private static final int SLOT_COLOR = 0xFF3A2814;
    private static final int EMPTY_BASE = 0xFF6A4A20;
    private static final int EMPTY_DARK = 0xFF3A2814;
    private static final int HEALTH_BASE = 0xFFB8281C;
    private static final int HEALTH_HIGHLIGHT = 0xFFE85848;
    private static final int HEALTH_DARK = 0xFF601008;
    private static final int HUNGER_BASE = 0xFFB87A20;
    private static final int HUNGER_HIGHLIGHT = 0xFFE0A848;
    private static final int HUNGER_DARK = 0xFF604010;
    private static final int GOLD_BASE = 0xFFC9A227;
    private static final int GOLD_HIGHLIGHT = 0xFFF5E08A;
    private static final int GOLD_DARK = 0xFF8A6D17;
    private static final int MANA_BASE = 0xFF1F4A6A;
    private static final int MANA_HIGHLIGHT = 0xFF4A7AA0;
    private static final int MANA_DARK = 0xFF0E2A3E;

    private SpanSheet sheet;
    private String signature;

    public NpcBarElement(Document document) {
        super(document, TAG_NAME);
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
        Rect rect = Rect.of(this);
        switch (phase) {
            case SHADOW -> rect.drawShadow(poseStack);
            case BODY -> {
                rect.drawBody(poseStack);
                Size size = rect.getBodyRectSize();
                if (size.width() <= 0 || size.height() <= 0) {
                    return;
                }
                SpanSheet.draw(poseStack, rect.getBodyRectPosition(), size, sheet(size));
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    private SpanSheet sheet(Size size) {
        int width = Math.max(20, (int) Math.round(size.width()));
        int height = Math.max(10, (int) Math.round(size.height()));
        double fill = fillRatio();
        String type = type();
        String current = width + "|" + height + "|" + fill + "|" + type;
        if (sheet != null && current.equals(signature)) {
            return sheet;
        }
        signature = current;
        sheet = build(width, height, fill, type);
        return sheet;
    }

    private double fillRatio() {
        double max = number(ATTRIBUTE_MAX);
        if (max > 0.0) {
            return clamp(number(ATTRIBUTE_VALUE) / max);
        }
        return clamp(number("fill"));
    }

    private static double clamp(double ratio) {
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    private double number(String attribute) {
        String raw = getAttribute(attribute);
        if (raw == null || raw.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException invalid) {
            return 0.0;
        }
    }

    private String type() {
        String raw = getAttribute("type");
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static int[] palette(String type) {
        return switch (type) {
            case "hunger" -> new int[] {HUNGER_BASE, HUNGER_HIGHLIGHT, HUNGER_DARK};
            case "gold" -> new int[] {GOLD_BASE, GOLD_HIGHLIGHT, GOLD_DARK};
            case "mana" -> new int[] {MANA_BASE, MANA_HIGHLIGHT, MANA_DARK};
            default -> new int[] {HEALTH_BASE, HEALTH_HIGHLIGHT, HEALTH_DARK};
        };
    }

    private static SpanSheet build(int width, int height, double fillRatio, String type) {
        SpanSheet sheet = new SpanSheet(width, height);
        int rows = Math.max(1, (int) Math.round(height / 9.0));
        int cell = (int) Math.round((double) height / rows);
        int inner = Math.max(3, cell - 2);
        int columns = Math.max(1, (int) Math.floor((double) width / cell));
        int filledColumns = (int) Math.round(columns * fillRatio);

        int[] colors = palette(type);
        int base = colors[0];
        int highlight = colors[1];
        int dark = colors[2];

        sheet.fill(0, 0, width, height, SLOT_COLOR);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int x = column * cell;
                int y = row * cell;
                sheet.fill(x, y, x + cell, y + cell, EMPTY_DARK);
                if (column < filledColumns) {
                    sheet.fill(x + 1, y + 1, x + 1 + inner, y + 1 + inner, base);
                    sheet.fill(x + 1, y + 1, x + 1 + inner, y + 2, highlight);
                    sheet.fill(x + 1, y + 1, x + 2, y + 1 + inner, highlight);
                    sheet.fill(x + inner, y + 1, x + inner + 1, y + 1 + inner, dark);
                    sheet.fill(x + 1, y + inner, x + 1 + inner, y + inner + 1, dark);
                } else {
                    sheet.fill(x + 1, y + 1, x + 1 + inner, y + 1 + inner, EMPTY_BASE);
                    sheet.fill(x + 1, y + 1, x + 1 + inner, y + 2, EMPTY_DARK);
                    sheet.fill(x + 1, y + 1, x + 2, y + 1 + inner, EMPTY_DARK);
                }
            }
        }
        return sheet;
    }
}
