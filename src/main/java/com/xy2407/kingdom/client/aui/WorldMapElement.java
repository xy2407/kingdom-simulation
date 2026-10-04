package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.Rect;
import com.xy2407.kingdom.client.map.WorldMapClientData;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.map.WorldMapRequestPacket;

import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/**
 * {@code <world-map>}：在 AUI 页面里绘制世界地图。
 *
 * <p>像素来自服务端采样（原版 {@code MapColor} 调色板，未探索为 0），
 * 绘制方式是**整张像素表一次上屏**——用 {@link SpanSheet} 铺一遍，
 * 而不是逐格提交顶点。这跟原版地图「一次 blit 画完整张」是同一个思路，
 * 差别只在于原版走的是纹理，这里走 AUI 自己的像素表面。</p>
 *
 * <p>属性：</p>
 * <ul>
 *   <li>{@code center-x} / {@code center-z} —— 地图中心的世界坐标。页面里写的是占位，
 *       打开界面时由 {@code CityHallScreen} 按市政厅方块的实际位置覆盖。</li>
 *   <li>{@code radius} —— 向服务端请求的采样半径（方块），默认 192。</li>
 *   <li>{@code zoom} —— 初始缩放，默认 1。</li>
 * </ul>
 *
 * <p>交互：左键拖动平移，滚轮缩放。拖动期间像素表按 {@link #DRAG_STEP} 降采样重建，
 * 松手后回到全质量——不然每帧重铺几万个像素会掉帧。</p>
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(WorldMapElement.TAG_NAME)
public class WorldMapElement extends Element {

    public static final String TAG_NAME = "WORLD-MAP";

    private static final String ATTR_CENTER_X = "center-x";
    private static final String ATTR_CENTER_Z = "center-z";
    private static final String ATTR_RADIUS = "radius";
    private static final String ATTR_ZOOM = "zoom";

    private static final int DEFAULT_RADIUS = 192;
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_PER_NOTCH = 1.25;

    /** 拖动时的降采样步长。 */
    private static final int DRAG_STEP = 3;

    /** 未探索区块的颜色，与原版一致：纯黑。 */
    private static final int UNEXPLORED_COLOR = 0xFF000000;

    private static final long STALE_DRAG_NANOS = 500_000_000L;

    private double zoom = 1.0;
    private double panX;
    private double panZ;

    /** 已经发过请求；收到数据后由 revision 变化重置。 */
    private boolean requested;
    private long drawnRevision = -1;

    private SpanSheet sheet;
    private String signature;

    private boolean dragging;
    private double lastX;
    private double lastY;
    private long lastDragNanos;

    public WorldMapElement(Document document) {
        super(document, TAG_NAME);
        addInternalEventListener("mousedown", this::onMouseDown);
        addInternalEventListener("mousemove", this::onMouseMove);
        addInternalEventListener("mouseup", this::onMouseUp);
        addInternalEventListener("wheel", this::onWheel);
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
                applyAttributeOverrides();
                ensureData();
                SpanSheet.draw(poseStack, rect.getBodyRectPosition(), size, sheet(size));
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    /* ------------------------------------------------------------------ 数据 */

    /** 没数据就向服务端要一块；换过数据就把像素表作废，下次重铺。 */
    private void ensureData() {
        if (!WorldMapClientData.isComplete()) {
            if (!requested) {
                requested = true;
                WorldMapClientData.markPending();
                PacketDistributor.sendToServer(
                        new WorldMapRequestPacket((int) Math.round(centerX()),
                                (int) Math.round(centerZ()), radius()));
            }
            return;
        }
        if (WorldMapClientData.revision() != drawnRevision) {
            drawnRevision = WorldMapClientData.revision();
            requested = false;
            sheet = null;
        }
    }

    /** 属性变化（比如打开界面时 Java 填的坐标）要重铺。 */
    private void applyAttributeOverrides() {
        double attributeZoom = number(ATTR_ZOOM, zoom);
        if (Math.abs(attributeZoom - zoom) > 1e-6 && !dragging) {
            zoom = clampZoom(attributeZoom);
        }
    }

    private double centerX() {
        return number(ATTR_CENTER_X, 0.0);
    }

    private double centerZ() {
        return number(ATTR_CENTER_Z, 0.0);
    }

    private int radius() {
        int value = (int) Math.round(number(ATTR_RADIUS, DEFAULT_RADIUS));
        return Math.max(16, Math.min(WorldMapRequestPacket.MAX_RADIUS, value));
    }

    private double number(String attribute, double fallback) {
        String raw = getAttribute(attribute);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private double clampZoom(double value) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }

    /* ------------------------------------------------------------------ 绘制 */

    private SpanSheet sheet(Size size) {
        int width = Math.max(24, (int) Math.round(size.width()));
        int height = Math.max(24, (int) Math.round(size.height()));
        int step = dragging ? DRAG_STEP : 1;
        int cx = (int) Math.round(centerX() + panX);
        int cz = (int) Math.round(centerZ() + panZ);
        String current = width + "|" + height + "|" + cx + "|" + cz + "|"
                + zoom + "|" + step + "|" + radius() + "|" + WorldMapClientData.revision();
        if (sheet != null && current.equals(signature)) {
            return sheet;
        }
        signature = current;
        sheet = build(width, height, cx, cz, step);
        return sheet;
    }

    /**
     * 铺一张像素表。
     *
     * <p>按屏幕像素逐块铺满整块画布——不按「该画几格」去算范围，
     * 那样一旦算错或容器比例变化就会留下没画到的边。</p>
     */
    private SpanSheet build(int width, int height, int centerX, int centerZ, int step) {
        SpanSheet result = new SpanSheet(width, height);

        /* 整块先涂黑：未探索区就是这个底 */
        result.fill(0, 0, width, height, UNEXPLORED_COLOR);

        if (!WorldMapClientData.isComplete()) {
            return result;
        }

        /* 每世界方块占几个屏幕像素：视野横向覆盖 radius × 2 + 1 格 */
        double blocksAcross = radius() * 2.0 + 1.0;
        double scale = width / blocksAcross / zoom;
        if (scale <= 0.0) {
            return result;
        }

        int px = Math.max(1, (int) Math.ceil(scale * step));
        int unexplored = WorldSurfaceSampler.UNEXPLORED & 0xFF;

        for (int py = 0; py < height; py += px) {
            int wz = centerZ + (int) Math.round((py + px / 2.0 - height / 2.0) / scale);
            for (int pxi = 0; pxi < width; pxi += px) {
                int wx = centerX + (int) Math.round((pxi + px / 2.0 - width / 2.0) / scale);
                int packed = WorldMapClientData.packedAt(wx, wz);
                if (packed == unexplored) {
                    continue;   // 未探索，留着黑底
                }
                /* packed 已按无符号展开，这里直接交给原版调色板 */
                result.fill(pxi, py,
                        Math.min(width, pxi + px), Math.min(height, py + px),
                        MapColor.getColorFromPackedId(packed));
            }
        }
        return result;
    }

    /* ------------------------------------------------------------------ 交互 */

    private void onMouseDown(Event event) {
        dragging = true;
        MouseEvent e = (MouseEvent) event;
        lastX = e.offsetX;
        lastY = e.offsetY;
        lastDragNanos = System.nanoTime();
    }

    private void onMouseMove(Event event) {
        if (!dragging) {
            return;
        }
        lastDragNanos = System.nanoTime();
        MouseEvent e = (MouseEvent) event;
        double dx = e.offsetX - lastX;
        double dy = e.offsetY - lastY;
        lastX = e.offsetX;
        lastY = e.offsetY;

        /* 屏幕位移换算成世界格数：一格 = scale 个像素 */
        int width = Math.max(24, (int) Math.round(Rect.of(this).getBodyRectSize().width()));
        double blocksAcross = radius() * 2.0 + 1.0;
        double scale = Math.max(1e-6, width / blocksAcross / zoom);

        panX -= dx / scale;
        panZ -= dy / scale;
        clearStaleDrag();
    }

    private void onMouseUp(Event event) {
        dragging = false;
    }

    private void onWheel(Event event) {
        MouseEvent e = (MouseEvent) event;
        if (e.scrollDelta == 0.0) {
            return;
        }
        double next = clampZoom(zoom * (e.scrollDelta > 0.0 ? ZOOM_PER_NOTCH : 1.0 / ZOOM_PER_NOTCH));
        if (Math.abs(next - zoom) > 1e-6) {
            zoom = next;
            /* 缩放时把平移量也按比例缩放，观感上像是绕中心拉近拉远 */
            sheet = null;
        }
    }

    /** 鼠标在别处松开时收不到 mouseup，靠时间戳兜底，免得一直是拖动状态。 */
    private void clearStaleDrag() {
        if (dragging && System.nanoTime() - lastDragNanos > STALE_DRAG_NANOS) {
            dragging = false;
        }
    }

    /* ------------------------------------------------------------------ 调试 */

    /** 当前缩放，供宿主读取（调试用）。 */
    public double zoom() {
        return zoom;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "WorldMapElement[zoom=%.2f, pan=%.1f,%.1f]",
                zoom, panX, panZ);
    }
}
