package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.element.Canvas;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.ImageDrawer;
import com.sighs.apricityui.render.Rect;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.map.ClientMapScanner;
import com.xy2407.kingdom.client.map.ClientMapStore;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * {@code <world-map>}：AUI 页面里的世界地图元素，把本地地图像素画进位图表面一次提交。
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(WorldMapElement.TAG_NAME)
public class WorldMapElement extends Canvas {

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

    private static final int MAX_RADIUS = 512;

    private static final int UNEXPLORED_COLOR = 0xFF000000;

    private static final int CHUNK_SIZE = 16;

    private static final double MIN_CHUNK_GRID_PIXELS = 10.0;

    private static final int CHUNK_GRID_THICKNESS = 2;

    private static final int CHUNK_GRID_COLOR = 0x55FFFFFF;

    private static final int MAX_SAMPLES = 400_000;

    private static final long CONTENT_REPAINT_NANOS = 300_000_000L;

    private static final long STALE_DRAG_NANOS = 500_000_000L;

    private static final long DRAG_SUPPRESS_NANOS = 250_000_000L;

    private static final double DRAG_PIXELS = 4.0;

    private static final int[] PALETTE = new int[256];
    private static boolean paletteReady;

    private int[] rasterBuffer;
    private BufferedImage rasterImage;

    private double zoom = 1.0;
    private double panX;
    private double panZ;

    private double layoutWidth;
    private double layoutHeight;
    private String paintedSignature;
    private long paintedRevision = -1;
    private long nextContentNanos;

    private static boolean colorProbed;

    private static BufferedImage markerImage;
    private static long markerLastTryNanos;
    private static boolean markerFailureLogged;

    private static long lastDragEndNanos;

    private boolean dragging;
    private double dragPixels;
    private double lastX;
    private double lastY;
    private long lastDragNanos;

    public WorldMapElement(Document document) {
        super(document);
        this.tagName = TAG_NAME;
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
                repaintIfNeeded(size);
                drawCanvas(poseStack, rect);
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    @Override
    protected void drawCanvas(PoseStack poseStack, Rect rectRenderer) {
        if (textureLocation == null) {
            syncTexture();
        }
        if (textureLocation == null || layoutWidth <= 0 || layoutHeight <= 0) {
            return;
        }
        ImageDrawer.draw(poseStack, textureLocation,
                (float) rectRenderer.getContentPosition().x,
                (float) rectRenderer.getContentPosition().y,
                (float) layoutWidth, (float) layoutHeight, true);
    }

    private void ensureData() {
        int viewX = (int) Math.round(centerX() + panX);
        int viewZ = (int) Math.round(centerZ() + panZ);
        int half = (int) Math.ceil((radius() * 2.0 + 1.0) / zoom / 2.0);
        ClientMapScanner.requestVisible(viewX - half, viewZ - half, viewX + half, viewZ + half);
    }

    private void repaintIfNeeded(Size size) {
        int surfaceWidth = Math.max(16, (int) Math.round(size.width()));
        int surfaceHeight = Math.max(16, (int) Math.round(size.height()));
        int viewX = (int) Math.round(centerX() + panX);
        int viewZ = (int) Math.round(centerZ() + panZ);
        double blocksX = (radius() * 2.0 + 1.0) / zoom;
        double pixelsPerBlock = surfaceWidth / blocksX;
        int step = sampleStep(surfaceWidth, surfaceHeight, pixelsPerBlock);
        if (dragging) {
            step *= DRAG_STEP;
        }
        int renderStep = step;

        long now = System.nanoTime();
        String signature = surfaceWidth + "|" + surfaceHeight + "|" + viewX + "|" + viewZ
                + "|" + blocksX + "|" + step;
        boolean geometry = !signature.equals(paintedSignature);
        long revision = ClientMapStore.revision();
        if (!geometry && (revision == paintedRevision || now < nextContentNanos)) {
            return;
        }

        paintedSignature = signature;
        paintedRevision = revision;
        nextContentNanos = now + CONTENT_REPAINT_NANOS;
        layoutWidth = surfaceWidth;
        layoutHeight = surfaceHeight;
        if (surfaceWidth != getWidth() || surfaceHeight != getHeight()) {
            resizeSurface(surfaceWidth, surfaceHeight, false);
        }

        double blocksY = surfaceHeight / pixelsPerBlock;
        int originX = viewX - (int) Math.round(blocksX / 2.0);
        int originZ = viewZ - (int) Math.round(blocksY / 2.0);
        renderOperation(graphics -> paint(graphics, surfaceWidth, surfaceHeight,
                originX, originZ, pixelsPerBlock, renderStep));
        syncTexture();
    }

    private static int sampleStep(int surfaceWidth, int surfaceHeight, double pixelsPerBlock) {
        int step = Math.max(1, (int) Math.ceil(1.0 / Math.max(1e-6, pixelsPerBlock)));
        double samples = (double) (surfaceWidth / step) * (surfaceHeight / step);
        if (samples > MAX_SAMPLES) {
            step = (int) Math.ceil(step * Math.sqrt(samples / MAX_SAMPLES));
        }
        return step;
    }

    private void paint(Graphics2D graphics, int surfaceWidth, int surfaceHeight,
                       int originX, int originZ, double pixelsPerBlock, int step) {
        int[] raster = rasterOf(surfaceWidth, surfaceHeight, originX, originZ, pixelsPerBlock, step);
        if (rasterImage == null || rasterImage.getWidth() != surfaceWidth
                || rasterImage.getHeight() != surfaceHeight) {
            rasterImage = new BufferedImage(surfaceWidth, surfaceHeight, BufferedImage.TYPE_INT_ARGB);
        }
        rasterImage.setRGB(0, 0, surfaceWidth, surfaceHeight, raster, 0, surfaceWidth);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(rasterImage, 0, 0, null);
        drawChunkGrid(graphics, surfaceWidth, surfaceHeight, originX, originZ, pixelsPerBlock);
        drawMarker(graphics, surfaceWidth, surfaceHeight, originX, originZ, pixelsPerBlock);
    }

    private int[] rasterOf(int surfaceWidth, int surfaceHeight, int originX, int originZ,
                           double pixelsPerBlock, int step) {
        int cells = surfaceWidth * surfaceHeight;
        if (rasterBuffer == null || rasterBuffer.length < cells) {
            rasterBuffer = new int[cells];
        }
        int[] raster = rasterBuffer;
        Arrays.fill(raster, 0, cells, UNEXPLORED_COLOR);
        if (!ClientMapStore.hasAnyChunk()) {
            return raster;
        }
        for (int py = 0; py < surfaceHeight; py += step) {
            int worldZ = originZ + (int) Math.round(py / pixelsPerBlock);
            int chunkZ = worldZ >> 4;
            int offsetInChunk = (worldZ & 15) * CHUNK_SIZE;
            int rowEnd = Math.min(surfaceHeight, py + step);
            byte[] cell = null;
            int loadedChunkX = Integer.MIN_VALUE;
            for (int px = 0; px < surfaceWidth; px += step) {
                int worldX = originX + (int) Math.round(px / pixelsPerBlock);
                int chunkX = worldX >> 4;
                if (chunkX != loadedChunkX) {
                    loadedChunkX = chunkX;
                    cell = ClientMapStore.chunk(chunkX, chunkZ);
                }
                if (cell == null) {
                    continue;
                }
                int packed = cell[offsetInChunk + (worldX & 15)] & 0xFF;
                if (packed == 0) {
                    continue;
                }
                int color = mapArgb(packed);
                int columnEnd = Math.min(surfaceWidth, px + step);
                for (int y = py; y < rowEnd; y++) {
                    int row = y * surfaceWidth;
                    Arrays.fill(raster, row + px, row + columnEnd, color);
                }
            }
        }
        return raster;
    }

    private static void drawChunkGrid(Graphics2D graphics, int surfaceWidth, int surfaceHeight,
                                      int originX, int originZ, double pixelsPerBlock) {
        if (pixelsPerBlock * CHUNK_SIZE < MIN_CHUNK_GRID_PIXELS) {
            return;
        }
        graphics.setColor(new Color(CHUNK_GRID_COLOR, true));
        int rightWorld = originX + (int) Math.ceil(surfaceWidth / pixelsPerBlock);
        for (int worldX = Math.floorDiv(originX, CHUNK_SIZE) * CHUNK_SIZE;
             worldX <= rightWorld; worldX += CHUNK_SIZE) {
            int px = (int) Math.round((worldX - originX) * pixelsPerBlock);
            graphics.fillRect(px, 0, CHUNK_GRID_THICKNESS, surfaceHeight);
        }
        int bottomWorld = originZ + (int) Math.ceil(surfaceHeight / pixelsPerBlock);
        for (int worldZ = Math.floorDiv(originZ, CHUNK_SIZE) * CHUNK_SIZE;
             worldZ <= bottomWorld; worldZ += CHUNK_SIZE) {
            int py = (int) Math.round((worldZ - originZ) * pixelsPerBlock);
            graphics.fillRect(0, py, surfaceWidth, CHUNK_GRID_THICKNESS);
        }
    }

    private void drawMarker(Graphics2D graphics, int surfaceWidth, int surfaceHeight,
                            int originX, int originZ, double pixelsPerBlock) {
        BufferedImage marker = markerImage();
        if (marker == null) {
            return;
        }
        String rawX = getAttribute(ATTR_MARKER_X);
        String rawZ = getAttribute(ATTR_MARKER_Z);
        if (rawX == null || rawX.isBlank() || rawZ == null || rawZ.isBlank()) {
            return;
        }
        int markerX;
        int markerZ;
        try {
            markerX = (int) Math.round(Double.parseDouble(rawX.trim()));
            markerZ = (int) Math.round(Double.parseDouble(rawZ.trim()));
        } catch (NumberFormatException invalid) {
            return;
        }
        int left = (int) Math.round((markerX - originX) * pixelsPerBlock) - MARKER_SIZE / 2;
        int top = (int) Math.round((markerZ - originZ) * pixelsPerBlock) - MARKER_SIZE / 2;
        if (left + MARKER_SIZE < 0 || top + MARKER_SIZE < 0
                || left >= surfaceWidth || top >= surfaceHeight) {
            return;
        }
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(marker, left, top, MARKER_SIZE, MARKER_SIZE, null);
    }

    private static BufferedImage markerImage() {
        if (markerImage != null) {
            return markerImage;
        }
        long now = System.nanoTime();
        if (now - markerLastTryNanos < MARKER_RETRY_NANOS) {
            return null;
        }
        markerLastTryNanos = now;
        markerImage = loadMarker();
        if (markerImage == null && !markerFailureLogged) {
            markerFailureLogged = true;
            ModMain.LOGGER.warn("[地图] 读不到标记图标 {}，地图上不会画标记",
                    MARKER_TEXTURE);
        }
        return markerImage;
    }

    private static BufferedImage loadMarker() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return null;
        }
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                "minecraft", MARKER_TEXTURE);
        try (InputStream stream = minecraft.getResourceManager()
                .getResourceOrThrow(location).open()) {
            return ImageIO.read(stream);
        } catch (Exception missing) {
            return null;
        }
    }

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
        return Math.max(16, Math.min(MAX_RADIUS, value));
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
        if (packed < 0 || packed > 255) {
            return 0;
        }
        if (!paletteReady) {
            for (int index = 0; index < PALETTE.length; index++) {
                int abgr = MapColor.getColorFromPackedId(index);
                PALETTE[index] = FastColor.ARGB32.color(
                        FastColor.ARGB32.alpha(abgr),
                        FastColor.ARGB32.blue(abgr),
                        FastColor.ARGB32.green(abgr),
                        FastColor.ARGB32.red(abgr));
            }
            paletteReady = true;
        }
        return PALETTE[packed];
    }

    private static void probeColorOnce(int centerX, int centerZ) {
        if (colorProbed || !ClientMapStore.hasAnyChunk()) {
            return;
        }
        colorProbed = true;
        int packed = ClientMapStore.packedAt(centerX, centerZ);
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

    private void onMouseDown(Event event) {
        dragging = true;
        dragPixels = 0.0;
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
        dragPixels += Math.abs(dx) + Math.abs(dy);

        int width = Math.max(24, (int) Math.round(Rect.of(this).getBodyRectSize().width()));
        double blocksAcross = (radius() * 2.0 + 1.0) / zoom;
        double scale = Math.max(1e-6, width / blocksAcross);

        panX -= dx / scale;
        panZ -= dy / scale;
        clearStaleDrag();
    }

    private void onMouseUp(Event event) {
        dragging = false;
        if (dragPixels > DRAG_PIXELS) {
            lastDragEndNanos = System.nanoTime();
        }
        commitPan();
    }

    public static boolean draggedRecently() {
        return System.nanoTime() - lastDragEndNanos < DRAG_SUPPRESS_NANOS;
    }

    private void commitPan() {
        if (Math.abs(panX) < 1.0d && Math.abs(panZ) < 1.0d) {
            panX = 0.0d;
            panZ = 0.0d;
            return;
        }
        int nextX = (int) Math.round(centerX() + panX);
        int nextZ = (int) Math.round(centerZ() + panZ);
        setAttribute(ATTR_CENTER_X, String.valueOf(nextX));
        setAttribute(ATTR_CENTER_Z, String.valueOf(nextZ));
        ClientMapStore.setFocusedCenter(nextX, nextZ);
        panX = 0.0d;
        panZ = 0.0d;
    }

    private void onWheel(Event event) {
        MouseEvent e = (MouseEvent) event;
        if (e.scrollDelta == 0.0) {
            return;
        }
        event.preventDefault();
        double next = clampZoom(zoom * (e.scrollDelta > 0.0 ? ZOOM_PER_NOTCH : 1.0 / ZOOM_PER_NOTCH));
        if (Math.abs(next - zoom) > 1e-6) {
            zoom = next;
            setAttribute(ATTR_ZOOM, String.valueOf(next));
        }
    }

    private void clearStaleDrag() {
        if (dragging && System.nanoTime() - lastDragNanos > STALE_DRAG_NANOS) {
            dragging = false;
        }
    }

    public void resetView() {
        panX = 0.0d;
        panZ = 0.0d;
        zoom = 1.0;
        setAttribute(ATTR_ZOOM, "1");
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
