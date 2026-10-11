package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.element.Canvas;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.Box;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.Drawer;
import com.sighs.apricityui.render.ImageDrawer;
import com.sighs.apricityui.render.Rect;
import com.sighs.apricityui.ui.Tooltip;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.map.ClientMapScanner;
import com.xy2407.kingdom.client.map.ClientMapStore;
import com.xy2407.kingdom.client.map.MapOverlayStore;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.faction.Territory;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * {@code <world-map>}：AUI 页面里的世界地图元素。拖动期间按较低的采样密度整屏重绘，
 * 保证画面完整（放弃"平移绘制"因为新露出的边条会空白）。
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
    private static final String ATTR_SHOW_BUILDINGS = "show-buildings";

    private static final String MARKER_TEXTURE = "textures/map/decorations/target_x.png";

    private static final int MARKER_SIZE = 32;

    private static final long MARKER_RETRY_NANOS = 5_000_000_000L;

    private static final int DEFAULT_RADIUS = 192;
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_PER_NOTCH = 1.25;

    private static final int SURFACE_MARGIN = 256;

    private static final int MAX_RADIUS = 512;

    private static final int UNEXPLORED_COLOR = 0xFF000000;

    private static final int CHUNK_SIZE = 16;

    private static final double MIN_CHUNK_GRID_PIXELS = 32.0;

    private static final int CHUNK_GRID_THICKNESS = 2;

    private static final int CHUNK_GRID_COLOR = 0x24FFFFFF;

    private static final int MAX_SAMPLES = 600_000;

    private static final int EDGE_WIDTH = 2;

    private static final int RIGHT_BUTTON = 1;

    private static final int SELECTION_FILL = 0x66FFD24A;

    private static final int SELECTION_EDGE = 0xFFFFD24A;

    private static final int COLOR_SELF = 0x5500E060;
    private static final int COLOR_OTHER = 0x55E04040;
    private static final int COLOR_ALLY = 0x554080E0;
    private static final int BUILDING_FILL = 0x6640A0FF;
    private static final int BUILDING_EDGE = 0xFF2F7FD0;

    private static final double BUILDING_ICON_MIN_PIXELS_PER_BLOCK = 0.35;
    private static final int BUILDING_ICON_SIZE = 9;
    private static final int ICON_BORDER = 1;
    private static final int ICON_GAP_PIXELS = 12;
    private static final int ICON_EDGE = 0xFF202020;
    private static final int ICON_SELECTED_EDGE = 0xFFFFD24A;
    private static final int ICON_DEFAULT = 0xFF9A7A4A;

    private static final long CONTENT_REPAINT_NANOS = 300_000_000L;

    private static final long STALE_DRAG_NANOS = 500_000_000L;

    private static final long DRAG_SUPPRESS_NANOS = 250_000_000L;

    private static final double DRAG_PIXELS = 4.0;

    private static final long PAINT_STAT_NANOS = 10_000_000_000L;

    private static int failures;
    private static boolean loggedZeroInner;
    private static boolean loggedShortBuffer;
    private static boolean loggedBadSurface;
    private static long lastPaintStatNanos;
    private static long paintNanos;
    private static int paintCalls;
    private static long chunkPaintNanos;
    private static int chunkPaintCalls;

    private static final int[] PALETTE = new int[256];
    private static boolean paletteReady;

    private double zoom = 1.0;
    private double panX;
    private double panZ;

    private int paintedWidth = -1;
    private int paintedHeight = -1;
    private int paintedViewX;
    private int paintedViewZ;
    private int paintedStep = -1;
    private long paintedToken = Long.MIN_VALUE;
    private double paintedBlocks;
    private double paintedPixelsPerBlock;
    private int paintedOriginX;
    private int paintedOriginZ;
    private long paintedStamp;
    private long nextContentNanos;

    private static BufferedImage markerImage;
    private static Rectangle markerContentBounds;
    private static long markerLastTryNanos;
    private static boolean markerFailureLogged;

    private static long lastDragEndNanos;

    private static boolean selecting;

    private static boolean selectionDirty;

    private static int brushChunkX;

    private static int brushChunkZ;

    private static final Set<Long> SELECTION = new LinkedHashSet<>();

    private static SelectionListener selectionListener;

    public interface SelectionListener {
        void onSelection(Set<Long> chunks);
    }

    private boolean dragging;
    private double dragPixels;
    private double lastX;
    private double lastY;
    private long lastDragNanos;
    private double dragPixelX;
    private double dragPixelY;
    private double dragAnchorX;
    private double dragAnchorY;
    private boolean dragRebuild;
    private double hoverX;
    private double hoverY;

    private String hoveredBuildingId = "";

    private static final Tooltip.Options TIP_OPTIONS = new Tooltip.Options("aui-tooltip",
            "padding:4px 6px;font-size:10px;line-height:13px;", 14, 18, 200);

    public WorldMapElement(Document document) {
        super(document);
        this.tagName = TAG_NAME;
        addInternalEventListener("mousedown", this::onMouseDown);
        addInternalEventListener("mousemove", this::onMouseMove);
        addInternalEventListener("mouseup", this::onMouseUp);
        addInternalEventListener("mouseleave", this::onMouseLeave);
        addInternalEventListener("wheel", this::onWheel);
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
        Rect rect = Rect.of(this);
        switch (phase) {
            case SHADOW -> rect.drawShadow(poseStack);
            case BODY -> {
                rect.drawBody(poseStack);
                try {
                    Size inner = Box.of(this).innerSize();
                    if (inner.width() <= 0 || inner.height() <= 0) {
                        reportZeroInner(inner, rect.getBodyRectSize());
                    }
                } catch (Throwable failure) {
                    reportFailure("读取尺寸", failure);
                }
                try {
                    applyAttributeOverrides();
                    ensureData();
                    repaintIfNeeded();
                } catch (Throwable failure) {
                    reportFailure("准备绘制", failure);
                }
                try {
                    drawCanvas(poseStack, rect);
                } catch (Throwable failure) {
                    reportFailure("提交纹理", failure);
                }
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    @Override
    protected void drawCanvas(PoseStack poseStack, Rect rectRenderer) {
        syncTexture();
        if (textureLocation == null) {
            return;
        }
        Position contentPos = rectRenderer.getContentPosition();
        Size contentSize = Box.of(this).innerSize();
        if (contentSize.width() <= 0 || contentSize.height() <= 0) {
            return;
        }
        ImageDrawer.drawWithUvWindow(poseStack, textureLocation,
                (float) contentPos.x, (float) contentPos.y,
                (float) contentSize.width(), (float) contentSize.height(), true,
                getWidth(), getHeight(),
                (float) (SURFACE_MARGIN - dragPixelX), (float) (SURFACE_MARGIN - dragPixelY),
                (float) contentSize.width(), (float) contentSize.height());
    }

    private void ensureData() {
        int viewX = (int) Math.round(centerX() + panX);
        int viewZ = (int) Math.round(centerZ() + panZ);
        int half = (int) Math.ceil((radius() * 2.0 + 1.0) / zoom / 2.0);
        ClientMapScanner.requestVisible(viewX - half, viewZ - half, viewX + half, viewZ + half);
        MapOverlayStore.requestUpdate(viewX, viewZ, half);
    }

    private void repaintIfNeeded() {
        Size inner = Box.of(this).innerSize();
        double contentWidth = inner.width();
        double contentHeight = inner.height();
        if (contentWidth <= 0 || contentHeight <= 0) {
            reportZeroInner(inner, null);
            return;
        }
        int contentW = Math.max(16, (int) Math.round(contentWidth));
        int contentH = Math.max(16, (int) Math.round(contentHeight));
        int wantedWidth = contentW + SURFACE_MARGIN * 2;
        int wantedHeight = contentH + SURFACE_MARGIN * 2;
        boolean resized = wantedWidth != getWidth() || wantedHeight != getHeight();

        boolean forceRebuild = dragRebuild;
        dragRebuild = false;
        if (dragging && !resized && !forceRebuild) {
            return;
        }

        int viewX = (int) Math.round(centerX() + panX);
        int viewZ = (int) Math.round(centerZ() + panZ);
        double blocksX = (radius() * 2.0 + 1.0) / zoom;
        double pixelsPerBlock = (double) contentW / blocksX;
        int step = sampleStep(wantedWidth, wantedHeight, pixelsPerBlock);
        long token = ClientMapStore.focusToken();

        boolean geometry = resized || forceRebuild || paintedViewX != viewX || paintedViewZ != viewZ
                || paintedStep != step || paintedToken != token
                || Double.compare(paintedBlocks, blocksX) != 0 || selectionDirty;

        if (!geometry) {
            long now = System.nanoTime();
            if (now < nextContentNanos) {
                return;
            }
            nextContentNanos = now + CONTENT_REPAINT_NANOS;
            long since = paintedStamp;
            paintedStamp = ClientMapStore.stamp();
            if (paintedStamp == since) {
                return;
            }
            if (ClientMapStore.forEachDirtySince(since, this::repaintChunk)) {
                syncTexture();
                return;
            }
        }

        paintedWidth = wantedWidth;
        paintedHeight = wantedHeight;
        paintedViewX = viewX;
        paintedViewZ = viewZ;
        paintedStep = step;
        paintedToken = token;
        paintedBlocks = blocksX;
        paintedStamp = ClientMapStore.stamp();
        nextContentNanos = System.nanoTime() + CONTENT_REPAINT_NANOS;
        selectionDirty = false;
        if (resized) {
            resizeSurface(wantedWidth, wantedHeight, false);
        }
        int surfaceWidth = getWidth();
        int surfaceHeight = getHeight();
        paintedPixelsPerBlock = pixelsPerBlock;
        paintedOriginX = viewX - (int) Math.round((SURFACE_MARGIN + contentW / 2.0) / pixelsPerBlock);
        paintedOriginZ = viewZ - (int) Math.round((SURFACE_MARGIN + contentH / 2.0) / pixelsPerBlock);
        paintAll(surfaceWidth, surfaceHeight);
    }

    private static int sampleStep(int surfaceWidth, int surfaceHeight, double pixelsPerBlock) {
        if (!Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0.0) {
            ModMain.LOGGER.warn("[地图] 像素/方块非法（{}），按 step=1 处理", pixelsPerBlock);
            return 1;
        }
        int step = Math.max(1, (int) Math.ceil(1.0 / pixelsPerBlock));
        double samples = (double) (surfaceWidth / step) * (surfaceHeight / step);
        if (samples > MAX_SAMPLES) {
            step = (int) Math.ceil(step * Math.sqrt(samples / MAX_SAMPLES));
        }
        return step;
    }

    private void paintAll(int surfaceWidth, int surfaceHeight) {
        int[] pixels = surfacePixels();
        if (pixels == null) {
            return;
        }
        int needed = surfaceWidth * surfaceHeight;
        if (pixels.length < needed) {
            if (!loggedShortBuffer) {
                loggedShortBuffer = true;
                ModMain.LOGGER.error("[地图] 像素数组不足：pixels={} 需要={}（surface={}x{} bitmap={}x{}）",
                        pixels.length, needed, surfaceWidth, surfaceHeight, getWidth(), getHeight());
            }
            return;
        }
        long start = System.nanoTime();
        fillRaster(pixels, surfaceWidth, surfaceHeight, paintedOriginX, paintedOriginZ,
                paintedPixelsPerBlock, paintedStep, 0, 0, surfaceWidth, surfaceHeight);
        markSurfaceDirty();
        renderOperation(graphics -> {
            drawOverlay(graphics, surfaceWidth, surfaceHeight, 0, 0, surfaceWidth, surfaceHeight);
            drawChunkGrid(graphics, surfaceWidth, surfaceHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
            drawSelection(graphics, 0, 0, surfaceWidth, surfaceHeight);
            drawMarker(graphics, surfaceWidth, surfaceHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
        }, null);
        syncTexture();
        paintNanos += System.nanoTime() - start;
        paintCalls++;
        reportPaint();
    }

    private void repaintChunk(long key) {
        if (getWidth() != paintedWidth || getHeight() != paintedHeight) {
            return;
        }
        int chunkX = ChunkPos.getX(key);
        int chunkZ = ChunkPos.getZ(key);
        int surfaceWidth = paintedWidth;
        int surfaceHeight = paintedHeight;
        int x0 = Math.max(0, (int) Math.floor(
                (chunkX * CHUNK_SIZE - paintedOriginX) * paintedPixelsPerBlock));
        int y0 = Math.max(0, (int) Math.floor(
                (chunkZ * CHUNK_SIZE - paintedOriginZ) * paintedPixelsPerBlock));
        int x1 = Math.min(surfaceWidth, (int) Math.ceil(
                ((chunkX + 1) * CHUNK_SIZE - paintedOriginX) * paintedPixelsPerBlock));
        int y1 = Math.min(surfaceHeight, (int) Math.ceil(
                ((chunkZ + 1) * CHUNK_SIZE - paintedOriginZ) * paintedPixelsPerBlock));
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int[] pixels = surfacePixels();
        if (pixels == null) {
            return;
        }
        int needed = surfaceWidth * surfaceHeight;
        if (pixels.length < needed) {
            if (!loggedShortBuffer) {
                loggedShortBuffer = true;
                ModMain.LOGGER.error("[地图] 局部重绘像素数组不足：pixels={} 需要={}（surface={}x{}）",
                        pixels.length, needed, surfaceWidth, surfaceHeight);
            }
            return;
        }
        long start = System.nanoTime();
        fillRaster(pixels, surfaceWidth, surfaceHeight, paintedOriginX, paintedOriginZ,
                paintedPixelsPerBlock, 1, x0, y0, x1, y1);
        Rectangle2D bounds = new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0);
        renderOperation(graphics -> {
            graphics.setClip(x0, y0, x1 - x0, y1 - y0);
            drawOverlay(graphics, surfaceWidth, surfaceHeight, x0, y0, x1, y1);
            drawChunkGrid(graphics, surfaceWidth, surfaceHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
            drawSelection(graphics, x0, y0, x1, y1);
            drawMarker(graphics, surfaceWidth, surfaceHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
        }, bounds);
        chunkPaintNanos += System.nanoTime() - start;
        chunkPaintCalls++;
        reportPaint();
    }

    private void fillRaster(int[] pixels, int surfaceWidth, int surfaceHeight,
                            int originX, int originZ, double pixelsPerBlock, int step,
                            int clipX0, int clipY0, int clipX1, int clipY1) {
        if (pixelsPerBlock <= 0.0 || !Double.isFinite(pixelsPerBlock) || step <= 0) {
            ModMain.LOGGER.error("[地图] fillRaster 参数非法：像素/方块={} step={}",
                    pixelsPerBlock, step);
            return;
        }
        int left = Math.max(0, Math.min(clipX0, surfaceWidth));
        int right = Math.max(0, Math.min(clipX1, surfaceWidth));
        int top = Math.max(0, Math.min(clipY0, surfaceHeight));
        int bottom = Math.max(0, Math.min(clipY1, surfaceHeight));
        if (left >= right || top >= bottom) {
            return;
        }
        for (int y = top; y < bottom; y++) {
            int row = y * surfaceWidth;
            Arrays.fill(pixels, row + left, row + right, UNEXPLORED_COLOR);
        }
        if (!ClientMapStore.hasAnyChunk()) {
            return;
        }
        int startY = top - (top % step);
        for (int py = startY; py < bottom; py += step) {
            int worldZ = originZ + (int) Math.round(py / pixelsPerBlock);
            int chunkZ = worldZ >> 4;
            int offsetInChunk = (worldZ & 15) * CHUNK_SIZE;
            int rowStart = Math.max(py, top);
            int rowEnd = Math.min(py + step, bottom);
            byte[] cell = null;
            int loadedChunkX = Integer.MIN_VALUE;
            int startX = left - (left % step);
            for (int px = startX; px < right; px += step) {
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
                int columnStart = Math.max(px, left);
                int columnEnd = Math.min(px + step, right);
                for (int y = rowStart; y < rowEnd; y++) {
                    int row = y * surfaceWidth;
                    Arrays.fill(pixels, row + columnStart, row + columnEnd, color);
                }
            }
        }
    }

    private int[] surfacePixels() {
        BufferedImage surface = getSurface();
        if (surface == null) {
            if (!loggedBadSurface) {
                loggedBadSurface = true;
                ModMain.LOGGER.error("[地图] getSurface() 返回 null");
            }
            return null;
        }
        if (!(surface.getRaster().getDataBuffer() instanceof DataBufferInt buffer)) {
            if (!loggedBadSurface) {
                loggedBadSurface = true;
                ModMain.LOGGER.error("[地图] surface 像素缓冲不是 DataBufferInt，实际 {}（type={} {}x{}）",
                        surface.getRaster().getDataBuffer().getClass().getName(),
                        surface.getType(), surface.getWidth(), surface.getHeight());
            }
            return null;
        }
        return buffer.getData();
    }

    private void drawOverlay(Graphics2D graphics, int surfaceWidth, int surfaceHeight,
                             int clipX0, int clipY0, int clipX1, int clipY1) {
        drawTerritoryLayer(graphics, surfaceWidth, surfaceHeight, clipX0, clipY0, clipX1, clipY1);
        drawBuildingLayer(graphics, surfaceWidth, surfaceHeight, clipX0, clipY0, clipX1, clipY1);
        drawIconLayer(graphics, surfaceWidth, surfaceHeight, clipX0, clipY0, clipX1, clipY1);
    }

    private void drawTerritoryLayer(Graphics2D graphics, int surfaceWidth, int surfaceHeight,
                                    int clipX0, int clipY0, int clipX1, int clipY1) {
        List<MapOverlayStore.TerritoryView> territories = MapOverlayStore.territories();
        if (territories.isEmpty() || paintedPixelsPerBlock <= 0.0) {
            return;
        }
        String selected = MapOverlayStore.selectedId();
        for (int index = 0; index < territories.size(); index++) {
            MapOverlayStore.TerritoryView territory = territories.get(index);
            boolean active = territory.id().equals(selected);
            int fill = territoryFill(territory, active);
            int edge = (fill & 0xFFFFFF) | 0xFF000000;
            graphics.setColor(new Color(fill, true));
            for (long key : territory.chunks()) {
                int chunkX = Territory.chunkKeyX(key);
                int chunkZ = Territory.chunkKeyZ(key);
                if (!ClientMapStore.explored(chunkX, chunkZ)) {
                    continue;
                }
                int px0 = Math.max(pixelX(chunkX * CHUNK_SIZE), clipX0);
                int py0 = Math.max(pixelZ(chunkZ * CHUNK_SIZE), clipY0);
                int px1 = Math.min(pixelX((chunkX + 1) * CHUNK_SIZE), clipX1);
                int py1 = Math.min(pixelZ((chunkZ + 1) * CHUNK_SIZE), clipY1);
                if (px1 <= px0 || py1 <= py0) {
                    continue;
                }
                graphics.fillRect(px0, py0, px1 - px0, py1 - py0);
                graphics.setColor(new Color(edge, true));
                if (!territory.containsChunk(chunkX - 1, chunkZ)) {
                    graphics.fillRect(px0, py0, EDGE_WIDTH, py1 - py0);
                }
                if (!territory.containsChunk(chunkX + 1, chunkZ)) {
                    graphics.fillRect(px1 - EDGE_WIDTH, py0, EDGE_WIDTH, py1 - py0);
                }
                if (!territory.containsChunk(chunkX, chunkZ - 1)) {
                    graphics.fillRect(px0, py0, px1 - px0, EDGE_WIDTH);
                }
                if (!territory.containsChunk(chunkX, chunkZ + 1)) {
                    graphics.fillRect(px0, py1 - EDGE_WIDTH, px1 - px0, EDGE_WIDTH);
                }
                graphics.setColor(new Color(fill, true));
            }
        }
    }

    private void drawBuildingLayer(Graphics2D graphics, int surfaceWidth, int surfaceHeight,
                                   int clipX0, int clipY0, int clipX1, int clipY1) {
        MapOverlayStore.BuildingView building = MapOverlayStore.selectedBuilding();
        if (building == null || paintedPixelsPerBlock <= 0.0) {
            return;
        }
        int px0 = Math.max(pixelX(building.minX()), clipX0);
        int py0 = Math.max(pixelZ(building.minZ()), clipY0);
        int px1 = Math.min(pixelX(building.maxX() + 1), clipX1);
        int py1 = Math.min(pixelZ(building.maxZ() + 1), clipY1);
        if (px1 <= px0 || py1 <= py0) {
            return;
        }
        graphics.setColor(new Color(BUILDING_FILL, true));
        graphics.fillRect(px0, py0, px1 - px0, py1 - py0);
        graphics.setColor(new Color(BUILDING_EDGE, true));
        graphics.fillRect(px0, py0, px1 - px0, EDGE_WIDTH);
        graphics.fillRect(px0, py1 - EDGE_WIDTH, px1 - px0, EDGE_WIDTH);
        graphics.fillRect(px0, py0, EDGE_WIDTH, py1 - py0);
        graphics.fillRect(px1 - EDGE_WIDTH, py0, EDGE_WIDTH, py1 - py0);
    }

    private int pixelX(int worldX) {
        return (int) Math.round((worldX - paintedOriginX) * paintedPixelsPerBlock);
    }

    private int pixelZ(int worldZ) {
        return (int) Math.round((worldZ - paintedOriginZ) * paintedPixelsPerBlock);
    }

    private static int territoryFill(MapOverlayStore.TerritoryView territory, boolean active) {
        int base = territory.ally() ? COLOR_ALLY : (territory.self() ? COLOR_SELF : COLOR_OTHER);
        if (!active) {
            return base;
        }
        return (base & 0x00FFFFFF) | 0x88000000;
    }

    private boolean showBuildings() {
        String raw = getAttribute(ATTR_SHOW_BUILDINGS);
        return raw == null || raw.isBlank() || !"false".equalsIgnoreCase(raw.trim());
    }

    private void drawIconLayer(Graphics2D graphics, int surfaceWidth, int surfaceHeight,
                               int clipX0, int clipY0, int clipX1, int clipY1) {
        if (!showBuildings() || paintedPixelsPerBlock < BUILDING_ICON_MIN_PIXELS_PER_BLOCK) {
            return;
        }
        List<MapOverlayStore.BuildingView> buildings = MapOverlayStore.buildings();
        if (buildings.isEmpty()) {
            return;
        }
        String selected = MapOverlayStore.selectedId();
        int half = BUILDING_ICON_SIZE / 2;
        List<int[]> placed = new ArrayList<>();
        for (int index = 0; index < buildings.size(); index++) {
            MapOverlayStore.BuildingView building = buildings.get(index);
            int centerX = pixelX(building.centerBlockX());
            int centerY = pixelZ(building.centerBlockZ());
            int left = centerX - half;
            int top = centerY - half;
            if (left > clipX1 || top > clipY1
                    || left + BUILDING_ICON_SIZE < clipX0 || top + BUILDING_ICON_SIZE < clipY0) {
                continue;
            }
            if (tooDense(placed, centerX, centerY)) {
                continue;
            }
            placed.add(new int[] {centerX, centerY});
            graphics.setColor(new Color(buildingIconColor(building.type()), true));
            graphics.fillRect(left, top, BUILDING_ICON_SIZE, BUILDING_ICON_SIZE);
            drawBorder(graphics, left, top, BUILDING_ICON_SIZE, BUILDING_ICON_SIZE,
                    building.id().equals(selected) ? ICON_SELECTED_EDGE : ICON_EDGE);
        }
    }

    private static boolean tooDense(List<int[]> placed, int centerX, int centerY) {
        for (int index = 0; index < placed.size(); index++) {
            int[] point = placed.get(index);
            if (Math.abs(point[0] - centerX) < ICON_GAP_PIXELS
                    && Math.abs(point[1] - centerY) < ICON_GAP_PIXELS) {
                return true;
            }
        }
        return false;
    }

    private static void drawBorder(Graphics2D graphics, int x, int y, int width, int height, int color) {
        graphics.setColor(new Color(color, true));
        graphics.fillRect(x, y, width, ICON_BORDER);
        graphics.fillRect(x, y + height - ICON_BORDER, width, ICON_BORDER);
        graphics.fillRect(x, y, ICON_BORDER, height);
        graphics.fillRect(x + width - ICON_BORDER, y, ICON_BORDER, height);
    }

    private static int buildingIconColor(String type) {
        if (type == null) {
            return ICON_DEFAULT;
        }
        return switch (type) {
            case "RESIDENTIAL" -> 0xFFB89868;
            case "AGRICULTURE" -> 0xFF6FA83C;
            case "LIVESTOCK" -> 0xFFC89A4A;
            case "INDUSTRY" -> 0xFF8A7A6A;
            case "COMMERCIAL" -> 0xFFD8A227;
            case "MILITARY" -> 0xFFA84A3C;
            case "STORAGE" -> 0xFF7A6A4A;
            default -> ICON_DEFAULT;
        };
    }

    private void handleClick(double screenX, double screenY) {
        if (paintedPixelsPerBlock <= 0.0) {
            return;
        }
        if (showBuildings() && paintedPixelsPerBlock >= BUILDING_ICON_MIN_PIXELS_PER_BLOCK) {
            MapOverlayStore.BuildingView building = buildingAt(screenX, screenY);
            if (building != null) {
                MapOverlayStore.selectBuilding(building);
                return;
            }
        }
        MapOverlayStore.TerritoryView hall = hallAt(screenX, screenY);
        if (hall != null) {
            MapOverlayStore.selectTerritory(hall);
            return;
        }
        MapOverlayStore.TerritoryView territory = territoryAt(screenX, screenY);
        if (territory != null) {
            MapOverlayStore.selectTerritory(territory);
            return;
        }
        MapOverlayStore.clearSelection();
    }

    private MapOverlayStore.BuildingView buildingAt(double screenX, double screenY) {
        int half = BUILDING_ICON_SIZE / 2;
        double surfaceX = toSurfaceX(screenX);
        double surfaceY = toSurfaceY(screenY);
        List<MapOverlayStore.BuildingView> buildings = MapOverlayStore.buildings();
        MapOverlayStore.BuildingView best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int index = 0; index < buildings.size(); index++) {
            MapOverlayStore.BuildingView building = buildings.get(index);
            double centerX = pixelX(building.centerBlockX());
            double centerY = pixelZ(building.centerBlockZ());
            if (Math.abs(surfaceX - centerX) <= half && Math.abs(surfaceY - centerY) <= half) {
                double distance = Math.abs(surfaceX - centerX) + Math.abs(surfaceY - centerY);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = building;
                }
            }
        }
        return best;
    }

    private MapOverlayStore.TerritoryView hallAt(double screenX, double screenY) {
        int hallX = markerCoordinate(ATTR_MARKER_X);
        int hallZ = markerCoordinate(ATTR_MARKER_Z);
        if (hallX == Integer.MIN_VALUE || hallZ == Integer.MIN_VALUE) {
            return null;
        }
        double surfaceX = toSurfaceX(screenX);
        double surfaceY = toSurfaceY(screenY);
        int left = pixelX(hallX) - MARKER_SIZE / 2;
        int top = pixelZ(hallZ) - MARKER_SIZE / 2;
        if (surfaceX < left || surfaceY < top
                || surfaceX > left + MARKER_SIZE || surfaceY > top + MARKER_SIZE) {
            return null;
        }
        for (MapOverlayStore.TerritoryView territory : MapOverlayStore.territories()) {
            if (territory.centerBlockX() == hallX && territory.centerBlockZ() == hallZ) {
                return territory;
            }
        }
        return null;
    }

    private MapOverlayStore.TerritoryView territoryAt(double screenX, double screenY) {
        int worldX = paintedOriginX + (int) Math.round(toSurfaceX(screenX) / paintedPixelsPerBlock);
        int worldZ = paintedOriginZ + (int) Math.round(toSurfaceY(screenY) / paintedPixelsPerBlock);
        int chunkX = Math.floorDiv(worldX, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(worldZ, CHUNK_SIZE);
        for (MapOverlayStore.TerritoryView territory : MapOverlayStore.territories()) {
            if (territory.containsChunk(chunkX, chunkZ)) {
                return territory;
            }
        }
        return null;
    }

    private int markerCoordinate(String attribute) {
        String raw = getAttribute(attribute);
        if (raw == null || raw.isBlank()) {
            return Integer.MIN_VALUE;
        }
        try {
            return (int) Math.round(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException invalid) {
            return Integer.MIN_VALUE;
        }
    }

    private static boolean isHallSelected(int markerX, int markerZ) {
        MapOverlayStore.TerritoryView selected = MapOverlayStore.selectedTerritory();
        return selected != null
                && selected.centerBlockX() == markerX && selected.centerBlockZ() == markerZ;
    }

    private void drawChunkGrid(Graphics2D graphics, int surfaceWidth, int surfaceHeight,
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

    private void drawSelection(Graphics2D graphics, int clipX0, int clipY0, int clipX1, int clipY1) {
        if (SELECTION.isEmpty() || paintedPixelsPerBlock <= 0.0) {
            return;
        }
        graphics.setColor(new Color(SELECTION_FILL, true));
        for (long key : SELECTION) {
            int chunkX = Territory.chunkKeyX(key);
            int chunkZ = Territory.chunkKeyZ(key);
            int px0 = Math.max(pixelX(chunkX * CHUNK_SIZE), clipX0);
            int py0 = Math.max(pixelZ(chunkZ * CHUNK_SIZE), clipY0);
            int px1 = Math.min(pixelX((chunkX + 1) * CHUNK_SIZE), clipX1);
            int py1 = Math.min(pixelZ((chunkZ + 1) * CHUNK_SIZE), clipY1);
            if (px1 > px0 && py1 > py0) {
                graphics.fillRect(px0, py0, px1 - px0, py1 - py0);
            }
        }
        graphics.setColor(new Color(SELECTION_EDGE, true));
        for (long key : SELECTION) {
            int chunkX = Territory.chunkKeyX(key);
            int chunkZ = Territory.chunkKeyZ(key);
            int px0 = Math.max(pixelX(chunkX * CHUNK_SIZE), clipX0);
            int py0 = Math.max(pixelZ(chunkZ * CHUNK_SIZE), clipY0);
            int px1 = Math.min(pixelX((chunkX + 1) * CHUNK_SIZE), clipX1);
            int py1 = Math.min(pixelZ((chunkZ + 1) * CHUNK_SIZE), clipY1);
            if (px1 <= px0 || py1 <= py0) {
                continue;
            }
            if (!SELECTION.contains(Territory.chunkKey(chunkX - 1, chunkZ))) {
                graphics.fillRect(px0, py0, EDGE_WIDTH, py1 - py0);
            }
            if (!SELECTION.contains(Territory.chunkKey(chunkX + 1, chunkZ))) {
                graphics.fillRect(px1 - EDGE_WIDTH, py0, EDGE_WIDTH, py1 - py0);
            }
            if (!SELECTION.contains(Territory.chunkKey(chunkX, chunkZ - 1))) {
                graphics.fillRect(px0, py0, px1 - px0, EDGE_WIDTH);
            }
            if (!SELECTION.contains(Territory.chunkKey(chunkX, chunkZ + 1))) {
                graphics.fillRect(px0, py1 - EDGE_WIDTH, px1 - px0, EDGE_WIDTH);
            }
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
        if (isHallSelected(markerX, markerZ)) {
            drawBorder(graphics, left, top, MARKER_SIZE, MARKER_SIZE, ICON_SELECTED_EDGE);
        }
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
        markerContentBounds = null;
        if (markerImage == null && !markerFailureLogged) {
            markerFailureLogged = true;
            ModMain.LOGGER.warn("[地图] 读不到标记图标 {}，地图上不会画标记", MARKER_TEXTURE);
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

    private void updateTooltip(MouseEvent event) {
        MapOverlayStore.BuildingView building = hoveredBuilding(event.offsetX, event.offsetY);
        String id = building == null ? "" : building.id();
        if (id.equals(hoveredBuildingId)) {
            if (!id.isEmpty()) {
                Tooltip.moveActive(new Position(event.clientX, event.clientY));
            }
            return;
        }
        hoveredBuildingId = id;
        if (building == null) {
            Tooltip.hide(document);
            return;
        }
        BuildingType type = BuildingType.parse(building.type());
        String label = type == null ? building.type() : type.label();
        Tooltip.show(document, new Position(event.clientX, event.clientY),
                label + "：" + building.name(), TIP_OPTIONS);
    }

    private void onMouseLeave(Event event) {
        hideTooltip();
        dragging = false;
        dragPixelX = 0.0;
        dragPixelY = 0.0;
        dragRebuild = true;
        if (selecting) {
            selecting = false;
            selectionDirty = true;
        }
    }

    private void hideTooltip() {
        if (hoveredBuildingId.isEmpty()) {
            return;
        }
        hoveredBuildingId = "";
        Tooltip.hide(document);
    }

    private MapOverlayStore.BuildingView hoveredBuilding(double screenX, double screenY) {
        if (!showBuildings() || paintedPixelsPerBlock < BUILDING_ICON_MIN_PIXELS_PER_BLOCK) {
            return null;
        }
        return buildingAt(screenX, screenY);
    }

    private void onMouseDown(Event event) {
        MouseEvent e = (MouseEvent) event;
        hideTooltip();
        if (e.button == RIGHT_BUTTON) {
            selecting = true;
            SELECTION.clear();
            brushChunkX = chunkAtX(e.offsetX);
            brushChunkZ = chunkAtZ(e.offsetY);
            SELECTION.add(Territory.chunkKey(brushChunkX, brushChunkZ));
            selectionDirty = true;
            lastDragNanos = System.nanoTime();
            return;
        }
        dragging = true;
        dragPixels = 0.0;
        dragPixelX = 0.0;
        dragPixelY = 0.0;
        dragAnchorX = e.offsetX;
        dragAnchorY = e.offsetY;
        lastX = e.offsetX;
        lastY = e.offsetY;
        lastDragNanos = System.nanoTime();
    }

    private void onMouseMove(Event event) {
        MouseEvent e = (MouseEvent) event;
        hoverX = e.offsetX;
        hoverY = e.offsetY;
        if (selecting) {
            lastDragNanos = System.nanoTime();
            paintSelection(e.offsetX, e.offsetY);
            return;
        }
        if (!dragging) {
            updateTooltip(e);
            return;
        }
        lastDragNanos = System.nanoTime();
        double dx = e.offsetX - lastX;
        double dy = e.offsetY - lastY;
        lastX = e.offsetX;
        lastY = e.offsetY;
        dragPixels += Math.abs(dx) + Math.abs(dy);

        int width = Math.max(24, (int) Math.round(Box.of(this).innerSize().width()));
        double blocksAcross = (radius() * 2.0 + 1.0) / zoom;
        double scale = Math.max(1e-6, width / blocksAcross);

        panX -= dx / scale;
        panZ -= dy / scale;

        dragPixelX = e.offsetX - dragAnchorX;
        dragPixelY = e.offsetY - dragAnchorY;
        if (document != null) {
            document.markDirty(this, Drawer.REPAINT);
        }
        if (dragOutOfMargin()) {
            commitPan();
            dragAnchorX = e.offsetX;
            dragAnchorY = e.offsetY;
            dragPixelX = 0.0;
            dragPixelY = 0.0;
            dragRebuild = true;
        }
        clearStaleDrag();
    }

    private boolean dragOutOfMargin() {
        Size inner = Box.of(this).innerSize();
        double u = SURFACE_MARGIN - dragPixelX;
        double v = SURFACE_MARGIN - dragPixelY;
        return u < 0.0 || v < 0.0
                || u + inner.width() > getWidth() || v + inner.height() > getHeight();
    }

    private void onMouseUp(Event event) {
        MouseEvent e = (MouseEvent) event;
        if (selecting) {
            selecting = false;
            selectionDirty = true;
            if (e.button == RIGHT_BUTTON && selectionListener != null && !SELECTION.isEmpty()) {
                selectionListener.onSelection(Set.copyOf(SELECTION));
            }
            return;
        }
        dragging = false;
        dragPixelX = 0.0;
        dragPixelY = 0.0;
        if (dragPixels > DRAG_PIXELS) {
            lastDragEndNanos = System.nanoTime();
            dragRebuild = true;
            commitPan();
            return;
        }
        handleClick(e.offsetX, e.offsetY);
        selectionDirty = true;
        dragRebuild = true;
        commitPan();
    }

    public static boolean draggedRecently() {
        return System.nanoTime() - lastDragEndNanos < DRAG_SUPPRESS_NANOS;
    }

    public static void setSelectionListener(SelectionListener listener) {
        selectionListener = listener;
    }

    public static void clearSelection() {
        selecting = false;
        SELECTION.clear();
        selectionDirty = true;
    }

    public static void requestRepaint() {
        selectionDirty = true;
    }

    private void paintSelection(double screenX, double screenY) {
        int chunkX = chunkAtX(screenX);
        int chunkZ = chunkAtZ(screenY);
        if (chunkX == brushChunkX && chunkZ == brushChunkZ) {
            return;
        }
        int steps = Math.max(Math.abs(chunkX - brushChunkX), Math.abs(chunkZ - brushChunkZ));
        for (int step = 0; step <= steps; step++) {
            int x = brushChunkX + Math.round((float) (chunkX - brushChunkX) * step / steps);
            int z = brushChunkZ + Math.round((float) (chunkZ - brushChunkZ) * step / steps);
            SELECTION.add(Territory.chunkKey(x, z));
        }
        brushChunkX = chunkX;
        brushChunkZ = chunkZ;
        selectionDirty = true;
    }

    private int chunkAtX(double screenX) {
        if (paintedPixelsPerBlock <= 0.0) {
            return 0;
        }
        int worldX = paintedOriginX + (int) Math.round(toSurfaceX(screenX) / paintedPixelsPerBlock);
        return Math.floorDiv(worldX, CHUNK_SIZE);
    }

    private int chunkAtZ(double screenY) {
        if (paintedPixelsPerBlock <= 0.0) {
            return 0;
        }
        int worldZ = paintedOriginZ + (int) Math.round(toSurfaceY(screenY) / paintedPixelsPerBlock);
        return Math.floorDiv(worldZ, CHUNK_SIZE);
    }

    private double toSurfaceX(double elementX) {
        return elementX + SURFACE_MARGIN - dragPixelX;
    }

    private double toSurfaceY(double elementY) {
        return elementY + SURFACE_MARGIN - dragPixelY;
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

    private static void reportPaint() {
        long now = System.nanoTime();
        if (now - lastPaintStatNanos < PAINT_STAT_NANOS) {
            return;
        }
        lastPaintStatNanos = now;
        if (paintCalls > 0) {
            ModMain.LOGGER.info("[地图] 重绘统计：全量 {} 次 共 {} ms（均 {} ms）",
                    paintCalls,
                    fmt(paintNanos / 1_000_000.0),
                    fmt(paintNanos / 1_000_000.0 / paintCalls));
        }
        if (chunkPaintCalls > 0) {
            ModMain.LOGGER.info("[地图] 重绘统计：局部 {} 次 共 {} ms（均 {} ms）",
                    chunkPaintCalls,
                    fmt(chunkPaintNanos / 1_000_000.0),
                    fmt(chunkPaintNanos / 1_000_000.0 / chunkPaintCalls));
        }
        paintNanos = 0L;
        paintCalls = 0;
        chunkPaintNanos = 0L;
        chunkPaintCalls = 0;
    }

    private static void reportFailure(String stage, Throwable failure) {
        failures++;
        if (failures <= 8) {
            ModMain.LOGGER.error("[地图] " + stage + " 抛异常（第 " + failures + " 次）", failure);
        }
    }

    private static void reportZeroInner(Size inner, Size body) {
        if (loggedZeroInner) {
            return;
        }
        loggedZeroInner = true;
        String bodyText = body == null ? "未取" : (fmt(body.width()) + "x" + fmt(body.height()));
        ModMain.LOGGER.warn("[地图] 内容尺寸为 0：inner={}x{} bodyRect={} → AUI 的默认 drawCanvas 会直接 return，地图不会绘制",
                fmt(inner.width()), fmt(inner.height()), bodyText);
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
        