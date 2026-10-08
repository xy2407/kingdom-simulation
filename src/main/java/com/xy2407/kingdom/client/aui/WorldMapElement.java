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
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.map.WorldMapClientData;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.map.WorldMapRequestPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * {@code <world-map>}：AUI 页面里的世界地图元素，负责取数、铺像素表、拖动缩放与标记。
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(WorldMapElement.TAG_NAME)
public class WorldMapElement extends Element {

    public static final String TAG_NAME = "WORLD-MAP";

    private static final String ATTR_CENTER_X = "center-x";
    private static final String ATTR_CENTER_Z = "center-z";
    private static final String ATTR_RADIUS = "radius";
    private static final String ATTR_ZOOM = "zoom";
    private static final String ATTR_MARKER_X = "marker-x";
    private static final String ATTR_MARKER_Z = "marker-z";

    private static final String MARKER_TEXTURE = "textures/map/decorations/target_x.png";

    private static final int MARKER_SIZE = 32;

    private static final long MARKER_RETRY_NANOS = 5_000_000_000L;

    private static final int DEFAULT_RADIUS = 192;
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_PER_NOTCH = 1.25;

    private static final int DRAG_STEP = 3;

    private static final int UNEXPLORED_COLOR = 0xFF000000;

    private static final int CHUNK_SIZE = 16;

    private static final double MIN_CHUNK_GRID_PIXELS = 10.0;

    private static final int CHUNK_GRID_THICKNESS = 2;

    private static final int CHUNK_GRID_COLOR = 0x55FFFFFF;

    private static final long REQUEST_GAP_NANOS = 250_000_000L;

    private static final long STALE_DRAG_NANOS = 500_000_000L;

    private double zoom = 1.0;
    private double panX;
    private double panZ;

    private long drawnRevision = -1;

    private SpanSheet sheet;
    private String signature;

    private static boolean colorProbed;

    private static int[][] markerPixels;
    private static long markerLastTryNanos;
    private static boolean markerFailureLogged;

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
                probeColorOnce((int) Math.round(centerX()), (int) Math.round(centerZ()));
                SpanSheet.draw(poseStack, rect.getBodyRectPosition(), size, sheet(size));
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    private void ensureData() {
        int radius = radius();
        int size = radius * 2 + 1;
        int originX = (int) Math.round(centerX()) - radius;
        int originZ = (int) Math.round(centerZ()) - radius;
        long now = System.nanoTime();

        if (!WorldMapClientData.isPending() && now - lastRequestNanos >= REQUEST_GAP_NANOS) {
            int[] missing = WorldMapClientData.missingRect(originX, originZ, size);
            if (missing != null) {
                lastRequestNanos = now;
                int half = Math.max(1, missing[2] / 2);
                requestMap(missing[0] + half, missing[1] + half, half);
            }
        }

        if (WorldMapClientData.revision() != drawnRevision) {
            drawnRevision = WorldMapClientData.revision();
            sheet = null;
        }
    }

    private void requestMap(int centerX, int centerZ, int radius) {
        WorldMapClientData.markPending();
        PacketDistributor.sendToServer(new WorldMapRequestPacket(
                centerX, centerZ, radius, WorldMapRequestPacket.NO_REVISION));
    }

    private long lastRequestNanos;

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

    private static int mapArgb(int packed) {
        if (packed == WorldSurfaceSampler.UNEXPLORED) {
            return 0;
        }
        int abgr = MapColor.getColorFromPackedId(packed);
        return FastColor.ARGB32.color(
                FastColor.ARGB32.alpha(abgr),
                FastColor.ARGB32.blue(abgr),
                FastColor.ARGB32.green(abgr),
                FastColor.ARGB32.red(abgr));
    }

    private static void probeColorOnce(int centerX, int centerZ) {
        if (colorProbed || !WorldMapClientData.hasAnyChunk()) {
            return;
        }
        colorProbed = true;
        int packed = WorldMapClientData.packedAt(centerX, centerZ);
        int colorId = (packed & 0xFF) >> 2;
        int brightness = packed & 3;
        int argb = mapArgb(packed);
        int woodPacked = MapColor.WOOD.getPackedId(MapColor.Brightness.HIGH) & 0xFF;
        ModMain.LOGGER.info("[地图] 取色探针 center=({}, {}) packed={} colorId={} brightness={} argb=#{} 未探索={}",
                centerX, centerZ, packed, colorId, brightness,
                Integer.toHexString(argb), WorldSurfaceSampler.UNEXPLORED);
        ModMain.LOGGER.info("[地图] 自检 MapColor.WOOD：col=#{} 经 mapArgb 还原=#{}（两者应一致）",
                Integer.toHexString(MapColor.WOOD.col & 0xFFFFFF),
                Integer.toHexString(mapArgb(woodPacked) & 0xFFFFFF));
    }

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

    private SpanSheet build(int width, int height, int centerX, int centerZ, int step) {
        SpanSheet result = new SpanSheet(width, height);

        result.fill(0, 0, width, height, UNEXPLORED_COLOR);

        double blocksAcross = radius() * 2.0 + 1.0;
        double scale = width / blocksAcross / zoom;
        if (scale <= 0.0) {
            return result;
        }

        if (WorldMapClientData.hasAnyChunk()) {
            int px = Math.max(1, (int) Math.ceil(scale * step));
            int unexplored = WorldSurfaceSampler.UNEXPLORED;

            for (int py = 0; py < height; py += px) {
                int wz = centerZ + (int) Math.round((py + px / 2.0 - height / 2.0) / scale);
                for (int pxi = 0; pxi < width; pxi += px) {
                    int wx = centerX + (int) Math.round((pxi + px / 2.0 - width / 2.0) / scale);
                    int packed = WorldMapClientData.packedAt(wx, wz);
                    if (packed == unexplored) {
                        continue;
                    }
                    result.fill(pxi, py,
                            Math.min(width, pxi + px), Math.min(height, py + px),
                            mapArgb(packed));
                }
            }

            drawChunkGrid(result, width, height, centerX, centerZ, scale, step);
        }

        drawMarker(result, width, height, centerX, centerZ, scale, step);

        return result;
    }

    private static void drawChunkGrid(SpanSheet sheet, int width, int height,
                                      int centerX, int centerZ, double scale, int step) {
        if (scale <= 0.0 || scale * CHUNK_SIZE < MIN_CHUNK_GRID_PIXELS) {
            return;
        }
        int thickness = Math.max(CHUNK_GRID_THICKNESS, step);

        int leftWorld = centerX + (int) Math.floor(-width / 2.0 / scale);
        int rightWorld = centerX + (int) Math.ceil(width / 2.0 / scale);
        for (int worldX = Math.floorDiv(leftWorld, CHUNK_SIZE) * CHUNK_SIZE;
             worldX <= rightWorld; worldX += CHUNK_SIZE) {
            int px = (int) Math.round(width / 2.0 + (worldX - centerX) * scale);
            sheet.fill(px, 0, px + thickness, height, CHUNK_GRID_COLOR);
        }

        int topWorld = centerZ + (int) Math.floor(-height / 2.0 / scale);
        int bottomWorld = centerZ + (int) Math.ceil(height / 2.0 / scale);
        for (int worldZ = Math.floorDiv(topWorld, CHUNK_SIZE) * CHUNK_SIZE;
             worldZ <= bottomWorld; worldZ += CHUNK_SIZE) {
            int py = (int) Math.round(height / 2.0 + (worldZ - centerZ) * scale);
            sheet.fill(0, py, width, py + thickness, CHUNK_GRID_COLOR);
        }
    }

    private void drawMarker(SpanSheet sheet, int width, int height,
                            int centerX, int centerZ, double scale, int step) {
        int[][] marker = markerPixels();
        if (marker == null || marker.length == 0 || marker[0].length == 0) {
            return;
        }

        int markerX;
        int markerZ;
        try {
            String rawX = getAttribute(ATTR_MARKER_X);
            String rawZ = getAttribute(ATTR_MARKER_Z);
            if (rawX == null || rawX.isBlank() || rawZ == null || rawZ.isBlank()) {
                return;
            }
            markerX = (int) Math.round(Double.parseDouble(rawX.trim()));
            markerZ = (int) Math.round(Double.parseDouble(rawZ.trim()));
        } catch (NumberFormatException invalid) {
            return;
        }

        int centerPx = width / 2 + (int) Math.round((markerX - centerX) * scale);
        int centerPy = height / 2 + (int) Math.round((markerZ - centerZ) * scale);
        int left = centerPx - MARKER_SIZE / 2;
        int top = centerPy - MARKER_SIZE / 2;

        if (left + MARKER_SIZE < 0 || top + MARKER_SIZE < 0
                || left >= width || top >= height) {
            return;
        }

        int rows = marker.length;
        int cols = marker[0].length;
        int stride = Math.max(1, step);
        for (int y = 0; y < MARKER_SIZE; y += stride) {
            int srcY = y * rows / MARKER_SIZE;
            if (srcY < 0 || srcY >= rows) {
                continue;
            }
            for (int x = 0; x < MARKER_SIZE; x += stride) {
                int srcX = x * cols / MARKER_SIZE;
                if (srcX < 0 || srcX >= cols) {
                    continue;
                }
                int argb = marker[srcY][srcX];
                if ((argb >>> 24) == 0) {
                    continue;
                }
                sheet.fill(left + x, top + y,
                        Math.min(left + x + stride, left + MARKER_SIZE),
                        Math.min(top + y + stride, top + MARKER_SIZE), argb);
            }
        }
    }

    private static int[][] markerPixels() {
        if (markerPixels != null) {
            return markerPixels;
        }
        long now = System.nanoTime();
        if (now - markerLastTryNanos < MARKER_RETRY_NANOS) {
            return null;
        }
        markerLastTryNanos = now;
        markerPixels = loadMarker();
        if (markerPixels == null && !markerFailureLogged) {
            markerFailureLogged = true;
            ModMain.LOGGER.warn("[地图] 读不到标记图标 {}，地图上不会画标记",
                    MARKER_TEXTURE);
        }
        return markerPixels;
    }

    private static int[][] loadMarker() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return null;
        }
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                "minecraft", MARKER_TEXTURE);
        try (InputStream stream = minecraft.getResourceManager()
                .getResourceOrThrow(location).open()) {
            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[][] pixels = new int[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y][x] = image.getRGB(x, y);
                }
            }
            return pixels;
        } catch (Exception missing) {
            return null;
        }
    }

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

        int width = Math.max(24, (int) Math.round(Rect.of(this).getBodyRectSize().width()));
        double blocksAcross = radius() * 2.0 + 1.0;
        double scale = Math.max(1e-6, width / blocksAcross / zoom);

        panX -= dx / scale;
        panZ -= dy / scale;
        clearStaleDrag();
    }

    private void onMouseUp(Event event) {
        dragging = false;
        commitPan();
    }

    private void commitPan() {
        if (Math.abs(panX) < 1.0d && Math.abs(panZ) < 1.0d) {
            panX = 0.0d;
            panZ = 0.0d;
            return;
        }
        setAttribute(ATTR_CENTER_X, String.valueOf((int) Math.round(centerX() + panX)));
        setAttribute(ATTR_CENTER_Z, String.valueOf((int) Math.round(centerZ() + panZ)));
        panX = 0.0d;
        panZ = 0.0d;
        sheet = null;
    }

    private void onWheel(Event event) {
        MouseEvent e = (MouseEvent) event;
        if (e.scrollDelta == 0.0) {
            return;
        }
        double next = clampZoom(zoom * (e.scrollDelta > 0.0 ? ZOOM_PER_NOTCH : 1.0 / ZOOM_PER_NOTCH));
        if (Math.abs(next - zoom) > 1e-6) {
            zoom = next;
            setAttribute(ATTR_ZOOM, String.valueOf(next));
            sheet = null;
        }
    }

    private void clearStaleDrag() {
        if (dragging && System.nanoTime() - lastDragNanos > STALE_DRAG_NANOS) {
            dragging = false;
        }
    }

    public double zoom() {
        return zoom;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "WorldMapElement[zoom=%.2f, pan=%.1f,%.1f]",
                zoom, panX, panZ);
    }
}
