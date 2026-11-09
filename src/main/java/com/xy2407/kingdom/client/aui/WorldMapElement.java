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
import com.sighs.apricityui.render.DevicePixelGrid;
import com.sighs.apricityui.render.Drawer;
import com.sighs.apricityui.render.ImageDrawer;
import com.sighs.apricityui.render.Rect;
import com.sighs.apricityui.ui.Tooltip;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.map.ClientMapScanner;
import com.xy2407.kingdom.client.map.ClientMapStore;
import com.xy2407.kingdom.client.map.MapOverlayStore;
import com.xy2407.kingdom.client.map.RoadClientStore;
import com.xy2407.kingdom.client.map.WorldRoadScreen;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.road.RoadPolyline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.BasicStroke;
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
import java.util.HashSet;
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

    private static final int UNEXPLORED_COLOR = 0xFF121A22;

    private static final int CHUNK_SIZE = 16;

    private static final int CHUNK_AREA = CHUNK_SIZE * CHUNK_SIZE;

    private static final float SHADE_HIGH = 1.16f;

    private static final float SHADE_LOW = 0.82f;

    private static final double MIN_CHUNK_GRID_PIXELS = 32.0;

    private static final int CHUNK_GRID_THICKNESS = 2;

    private static final int CHUNK_GRID_COLOR = 0x24FFFFFF;

    private static final int EDGE_WIDTH = 2;

    private static final int RIGHT_BUTTON = 1;

    private static final int SELECTION_FILL = 0x66FFD24A;

    private static final int SELECTION_EDGE = 0xFFFFD24A;

    private static final int COLOR_SELF = 0x5500E060;
    private static final int COLOR_OTHER = 0x55E04040;
    private static final int COLOR_ALLY = 0x554080E0;
    private static final int BUILDING_FILL = 0x6640A0FF;
    private static final int BUILDING_EDGE = 0xFF2F7FD0;

    private static final int ROAD_VALID_COLOR = 0xD8F5E08A;
    private static final int ROAD_INVALID_COLOR = 0xE0E04040;
    private static final int ROAD_HOVER_FILL = 0x66E04040;
    private static final int ROAD_HOVER_EDGE = 0xFFE04040;
    private static final int ROAD_COMMITTED_COLOR = 0x904A3218;

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

    private static int failures;
    private static boolean loggedZeroInner;
    private static boolean loggedShortBuffer;
    private static boolean loggedBadSurface;

    private double zoom = 1.0;
    private double panX;
    private double panZ;

    private int paintedWidth = -1;
    private int paintedHeight = -1;
    private int paintedViewX;
    private int paintedViewZ;
    private int paintedLevel = -1;
    private long paintedToken = Long.MIN_VALUE;
    private double paintedBlocks;
    private double paintedPixelsPerBlock;
    private int paintedOriginX;
    private int paintedOriginZ;
    private long paintedStamp;
    private long nextContentNanos;
    private long paintedPlayerChunk = NO_CHUNK;

    private static final long NO_CHUNK = Long.MIN_VALUE;

    private static final Color PLAYER_MARKER_OUTLINE = new Color(246, 240, 224);

    private static final Color PLAYER_MARKER_FILL = new Color(214, 48, 38);

    private static BufferedImage markerImage;
    private static Rectangle markerContentBounds;
    private static long markerLastTryNanos;
    private static boolean markerFailureLogged;

    private static long lastDragEndNanos;

    private static boolean selecting;

    private static boolean selectionDirty;

    private static boolean roadMode;

    private static boolean roadDrawing;

    private static double roadLastX = Double.NaN;

    private static double roadLastZ = Double.NaN;

    private static int roadHoverX;

    private static int roadHoverZ;

    private static boolean roadHoverInvalid;

    private static int brushChunkX;

    private static int brushChunkZ;

    private static final Set<Long> SELECTION = new LinkedHashSet<>();

    private static SelectionListener selectionListener;

    public interface SelectionListener {
        void onSelection(Set<Long> chunks);
    }

    private static RoadClickListener roadClickListener;

    public interface RoadClickListener {
        void onRoadChunk(int chunkX, int chunkZ);
    }

    public static void setRoadClickListener(RoadClickListener listener) {
        roadClickListener = listener;
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
        double scale = deviceScale();
        // 原点吸到设备像素网格：像素级地形图落在半像素上会整幅发虚并轻微偏移。
        ImageDrawer.drawWithUvWindow(poseStack, textureLocation,
                DevicePixelGrid.snap(contentPos.x, scale),
                DevicePixelGrid.snap(contentPos.y, scale),
                (float) contentSize.width(), (float) contentSize.height(), false,
                getBitmapWidth(), getBitmapHeight(),
                (float) ((SURFACE_MARGIN - dragPixelX) * scale),
                (float) ((SURFACE_MARGIN - dragPixelY) * scale),
                (float) (contentSize.width() * scale),
                (float) (contentSize.height() * scale));
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
        int level = selectLevel(pixelsPerBlock);
        long token = ClientMapStore.focusToken();

        boolean geometry = resized || forceRebuild || paintedViewX != viewX || paintedViewZ != viewZ
                || paintedLevel != level || paintedToken != token
                || Double.compare(paintedBlocks, blocksX) != 0 || selectionDirty;

        long playerKey = playerChunkKey();
        if (playerKey != paintedPlayerChunk) {
            long previous = paintedPlayerChunk;
            paintedPlayerChunk = playerKey;
            if (!geometry && previous != NO_CHUNK && paintedWidth > 0
                    && getWidth() == paintedWidth && getHeight() == paintedHeight) {
                repaintAroundPlayer(playerKey);
                if (previous != playerKey) {
                    repaintAroundPlayer(previous);
                }
                syncTexture();
                return;
            }
        }

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
        paintedLevel = level;
        paintedToken = token;
        paintedBlocks = blocksX;
        paintedStamp = ClientMapStore.stamp();
        nextContentNanos = System.nanoTime() + CONTENT_REPAINT_NANOS;
        selectionDirty = false;
        if (resized) {
            resizeSurface(wantedWidth, wantedHeight, false);
        }
        int surfaceWidth = getBitmapWidth();
        int surfaceHeight = getBitmapHeight();
        paintedPixelsPerBlock = pixelsPerBlock;
        paintedOriginX = viewX - (int) Math.round((SURFACE_MARGIN + contentW / 2.0) / pixelsPerBlock);
        paintedOriginZ = viewZ - (int) Math.round((SURFACE_MARGIN + contentH / 2.0) / pixelsPerBlock);
        paintAll(surfaceWidth, surfaceHeight);
    }

    private static int selectLevel(double pixelsPerBlock) {
        if (!Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0.0) {
            return 0;
        }
        int level = 0;
        double ppb = pixelsPerBlock;
        while (ppb < 1.0D && level < ClientMapStore.MAX_LEVEL) {
            ppb *= 2.0D;
            level++;
        }
        return level;
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
                ModMain.LOGGER.error("[地图] 像素数组不足：pixels={} 需要={}（raster={}x{} design={}x{}）",
                        pixels.length, needed, surfaceWidth, surfaceHeight,
                        getWidth(), getHeight());
            }
            return;
        }
        double scale = deviceScale();
        fillRaster(pixels, surfaceWidth, surfaceHeight, paintedOriginX, paintedOriginZ,
                paintedPixelsPerBlock * scale, paintedLevel, 0, 0, surfaceWidth, surfaceHeight);
        markSurfaceDirty();
        int designWidth = getWidth();
        int designHeight = getHeight();
        renderOperation(graphics -> {
            graphics.scale(scale, scale);
            drawOverlay(graphics, designWidth, designHeight, 0, 0, designWidth, designHeight);
            drawChunkGrid(graphics, designWidth, designHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
            drawSelection(graphics, 0, 0, designWidth, designHeight);
            drawMarker(graphics, designWidth, designHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
            drawPlayerMarker(graphics, designWidth, designHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
        }, null);
        syncTexture();
    }

    private double deviceScale() {
        double scale = getDeviceScale();
        return scale > 0.0D && Double.isFinite(scale) ? scale : 1.0D;
    }

    private void repaintChunk(long key) {
        if (getWidth() != paintedWidth || getHeight() != paintedHeight) {
            return;
        }
        int chunkX = ChunkPos.getX(key);
        int chunkZ = ChunkPos.getZ(key);
        double scale = deviceScale();
        int rasterWidth = getBitmapWidth();
        int rasterHeight = getBitmapHeight();
        int designWidth = getWidth();
        int designHeight = getHeight();
        int dx0 = Math.max(0, (int) Math.floor(
                (chunkX * CHUNK_SIZE - paintedOriginX) * paintedPixelsPerBlock));
        int dy0 = Math.max(0, (int) Math.floor(
                (chunkZ * CHUNK_SIZE - paintedOriginZ) * paintedPixelsPerBlock));
        int dx1 = Math.min(designWidth, (int) Math.ceil(
                ((chunkX + 1) * CHUNK_SIZE - paintedOriginX) * paintedPixelsPerBlock));
        int dy1 = Math.min(designHeight, (int) Math.ceil(
                ((chunkZ + 1) * CHUNK_SIZE - paintedOriginZ) * paintedPixelsPerBlock));
        if (dx1 <= dx0 || dy1 <= dy0) {
            return;
        }
        int[] pixels = surfacePixels();
        if (pixels == null) {
            return;
        }
        int needed = rasterWidth * rasterHeight;
        if (pixels.length < needed) {
            if (!loggedShortBuffer) {
                loggedShortBuffer = true;
                ModMain.LOGGER.error("[地图] 局部重绘像素数组不足：pixels={} 需要={}（raster={}x{}）",
                        pixels.length, needed, rasterWidth, rasterHeight);
            }
            return;
        }
        int x0 = Math.max(0, Math.min(rasterWidth, (int) Math.floor(dx0 * scale)));
        int y0 = Math.max(0, Math.min(rasterHeight, (int) Math.floor(dy0 * scale)));
        int x1 = Math.max(0, Math.min(rasterWidth, (int) Math.ceil(dx1 * scale)));
        int y1 = Math.max(0, Math.min(rasterHeight, (int) Math.ceil(dy1 * scale)));
        fillRaster(pixels, rasterWidth, rasterHeight, paintedOriginX, paintedOriginZ,
                paintedPixelsPerBlock * scale, paintedLevel, x0, y0, x1, y1);
        Rectangle2D bounds = new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0);
        renderOperation(graphics -> {
            graphics.scale(scale, scale);
            graphics.setClip(dx0, dy0, dx1 - dx0, dy1 - dy0);
            drawOverlay(graphics, designWidth, designHeight, dx0, dy0, dx1, dy1);
            drawChunkGrid(graphics, designWidth, designHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
            drawSelection(graphics, dx0, dy0, dx1, dy1);
            drawMarker(graphics, designWidth, designHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
            drawPlayerMarker(graphics, designWidth, designHeight,
                    paintedOriginX, paintedOriginZ, paintedPixelsPerBlock);
        }, bounds);
    }

    private void fillRaster(int[] pixels, int surfaceWidth, int surfaceHeight,
                            int originX, int originZ, double pixelsPerBlock, int level,
                            int clipX0, int clipY0, int clipX1, int clipY1) {
        if (pixelsPerBlock <= 0.0 || !Double.isFinite(pixelsPerBlock) || level < 0) {
            ModMain.LOGGER.error("[地图] fillRaster 参数非法：像素/方块={} level={}",
                    pixelsPerBlock, level);
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
        ResourceKey<Level> dimension = dimension();
        if (dimension == null) {
            return;
        }
        int scale = 1 << level;
        int side = CHUNK_SIZE >> level;
        int area = side * side;
        int chunkShift = 4 - level;
        int sideMask = side - 1;
        double invPpb = 1.0D / pixelsPerBlock;
        int startY = top;
        for (int py = startY; py < bottom; py++) {
            double worldZf = originZ + (py + 0.5) * invPpb;
            int dz = (int) Math.floor(worldZf / scale);
            int chunkZ = dz >> chunkShift;
            int dy = dz & sideMask;
            int[] cell = null;
            int[] northCell = null;
            int[] westCell = null;
            int loadedChunkX = Integer.MIN_VALUE;
            for (int px = left; px < right; px++) {
                double worldXf = originX + (px + 0.5) * invPpb;
                int dx = (int) Math.floor(worldXf / scale);
                int chunkX = dx >> chunkShift;
                if (chunkX != loadedChunkX) {
                    loadedChunkX = chunkX;
                    cell = ClientMapStore.chunkAt(dimension, chunkX, chunkZ, level);
                    northCell = ClientMapStore.chunkAt(dimension, chunkX, chunkZ - 1, level);
                    westCell = ClientMapStore.chunkAt(dimension, chunkX - 1, chunkZ, level);
                }
                if (cell == null) {
                    continue;
                }
                int tx = dx & sideMask;
                int local = dy * side + tx;
                int color = cell[local];
                if (color == 0) {
                    continue;
                }
                int height = cell[area + local];
                float k = 1.0f;
                if (height != WorldSurfaceSampler.NO_SURFACE) {
                    int hN = dy == 0
                            ? levelHeightAt(northCell, side, tx, side - 1)
                            : cell[area + (dy - 1) * side + tx];
                    int hW = tx == 0
                            ? levelHeightAt(westCell, side, side - 1, dy)
                            : cell[area + dy * side + tx - 1];
                    k = shadeFactor(height, hN, hW);
                }
                color = applyBrightness(color, k);
                pixels[py * surfaceWidth + px] = color;
            }
        }
    }

    private static int levelHeightAt(int[] cell, int side, int tx, int ty) {
        if (cell == null) {
            return WorldSurfaceSampler.NO_SURFACE;
        }
        return cell[side * side + ty * side + tx];
    }

    private static ResourceKey<Level> dimension() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.level == null ? null : minecraft.level.dimension();
    }

    private static float shadeFactor(int height, int hN, int hW) {
        long sum = height;
        int count = 1;
        if (hN != WorldSurfaceSampler.NO_SURFACE) {
            sum += hN;
            count++;
        }
        if (hW != WorldSurfaceSampler.NO_SURFACE) {
            sum += hW;
            count++;
        }
        double average = sum / (double) count;
        if (height > average) {
            return SHADE_HIGH;
        }
        if (height < average) {
            return SHADE_LOW;
        }
        return 1.0f;
    }

    private static int applyBrightness(int argb, float k) {
        if (k == 1.0f) {
            return argb;
        }
        int red = Math.min(255, Math.round(((argb >>> 16) & 0xFF) * k));
        int green = Math.min(255, Math.round(((argb >>> 8) & 0xFF) * k));
        int blue = Math.min(255, Math.round((argb & 0xFF) * k));
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
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
        drawRoadChunkLayer(graphics, clipX0, clipY0, clipX1, clipY1);
        drawCommittedRoadLayer(graphics, clipX0, clipY0, clipX1, clipY1);
        drawRoadLayer(graphics);
        drawBuildingLayer(graphics, surfaceWidth, surfaceHeight, clipX0, clipY0, clipX1, clipY1);
        drawIconLayer(graphics, surfaceWidth, surfaceHeight, clipX0, clipY0, clipX1, clipY1);
    }

    private void drawRoadChunkLayer(Graphics2D graphics, int clipX0, int clipY0, int clipX1, int clipY1) {
        List<MapOverlayStore.RoadView> roads = MapOverlayStore.roads();
        if (roads.isEmpty() || paintedPixelsPerBlock <= 0.0) {
            return;
        }
        int chunkPixels = (int) Math.round(paintedPixelsPerBlock * CHUNK_SIZE);
        for (int index = 0; index < roads.size(); index++) {
            MapOverlayStore.RoadView road = roads.get(index);
            if (!ClientMapStore.explored(road.chunkX(), road.chunkZ())) {
                continue;
            }
            int px0 = pixelX(road.chunkX() * CHUNK_SIZE);
            int py0 = pixelZ(road.chunkZ() * CHUNK_SIZE);
            if (px0 > clipX1 || py0 > clipY1 || px0 + chunkPixels < clipX0 || py0 + chunkPixels < clipY0) {
                continue;
            }
            graphics.setColor(new Color(0x66FFD24A, true));
            graphics.fillRect(px0, py0, chunkPixels, chunkPixels);
            graphics.setColor(new Color(0xFFFFD24A, true));
            graphics.drawRect(px0, py0, chunkPixels, chunkPixels);
        }
    }

    private void drawCommittedRoadLayer(Graphics2D graphics, int clipX0, int clipY0, int clipX1, int clipY1) {
        List<MapOverlayStore.RoadView> roads = MapOverlayStore.roads();
        if (roads.isEmpty() || paintedPixelsPerBlock <= 0.0) {
            return;
        }
        double cell = paintedPixelsPerBlock;
        int height = Math.max(1, (int) Math.round(cell));
        int chunkPixels = (int) Math.round(cell * CHUNK_SIZE);
        graphics.setColor(new Color(ROAD_COMMITTED_COLOR, true));
        for (int index = 0; index < roads.size(); index++) {
            MapOverlayStore.RoadView road = roads.get(index);
            if (!ClientMapStore.explored(road.chunkX(), road.chunkZ())) {
                continue;
            }
            int px0 = pixelX(road.chunkX() * CHUNK_SIZE);
            int py0 = pixelZ(road.chunkZ() * CHUNK_SIZE);
            if (px0 > clipX1 || py0 > clipY1 || px0 + chunkPixels < clipX0 || py0 + chunkPixels < clipY0) {
                continue;
            }
            drawRoadCells(graphics, road, cell, height);
        }
    }

    private void drawRoadCells(Graphics2D graphics, MapOverlayStore.RoadView road, double cell, int height) {
        boolean[][] grid = new boolean[CHUNK_SIZE][CHUNK_SIZE];
        long[] cells = road.cells();
        for (int index = 0; index < cells.length; index++) {
            int localX = BlockPos.getX(cells[index]) & 15;
            int localZ = BlockPos.getZ(cells[index]) & 15;
            grid[localZ][localX] = true;
        }
        int baseX = road.chunkX() * CHUNK_SIZE;
        int baseZ = road.chunkZ() * CHUNK_SIZE;
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            int runStart = -1;
            for (int localX = 0; localX <= CHUNK_SIZE; localX++) {
                boolean filled = localX < CHUNK_SIZE && grid[localZ][localX];
                if (filled && runStart < 0) {
                    runStart = localX;
                } else if (!filled && runStart >= 0) {
                    int px = pixelX(baseX + runStart);
                    int py = pixelZ(baseZ + localZ);
                    graphics.fillRect(px, py, (int) Math.max(1, (localX - runStart) * cell), height);
                    runStart = -1;
                }
            }
        }
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
            case "RESIDENTIAL", "RESIDENCE" -> 0xFFB89868;
            case "AGRICULTURE", "FARM" -> 0xFF6FA83C;
            case "LIVESTOCK", "RANCH" -> 0xFFC89A4A;
            case "INDUSTRY", "LUMBER", "MINE", "WORKSHOP", "FORGE", "CHEESE", "BREWERY" ->
                    0xFF8A7A6A;
            case "COMMERCIAL", "SHOP", "RESTAURANT" -> 0xFFD8A227;
            case "MILITARY", "BARRACKS" -> 0xFFA84A3C;
            case "WAREHOUSE", "BUILDER_HUT", "CITY_HALL", "HOSPITAL", "SCHOOL" -> 0xFF7A6A4A;
            case "DECORATION" -> 0xFF9A9A9A;
            default -> ICON_DEFAULT;
        };
    }

    private void handleClick(double screenX, double screenY) {
        if (paintedPixelsPerBlock <= 0.0) {
            return;
        }
        if (isRoadHost()) {
            int worldX = paintedOriginX + (int) Math.round(toSurfaceX(screenX) / paintedPixelsPerBlock);
            int worldZ = paintedOriginZ + (int) Math.round(toSurfaceY(screenY) / paintedPixelsPerBlock);
            int chunkX = Math.floorDiv(worldX, CHUNK_SIZE);
            int chunkZ = Math.floorDiv(worldZ, CHUNK_SIZE);
            for (MapOverlayStore.RoadView road : MapOverlayStore.roads()) {
                if (road.containsChunk(chunkX, chunkZ) && roadClickListener != null) {
                    roadClickListener.onRoadChunk(chunkX, chunkZ);
                    break;
                }
            }
            return;
        }
        if (roadClickListener != null) {
            int worldX = paintedOriginX + (int) Math.round(toSurfaceX(screenX) / paintedPixelsPerBlock);
            int worldZ = paintedOriginZ + (int) Math.round(toSurfaceY(screenY) / paintedPixelsPerBlock);
            int chunkX = Math.floorDiv(worldX, CHUNK_SIZE);
            int chunkZ = Math.floorDiv(worldZ, CHUNK_SIZE);
            for (MapOverlayStore.RoadView road : MapOverlayStore.roads()) {
                if (road.containsBlock(worldX, worldZ)) {
                    roadClickListener.onRoadChunk(chunkX, chunkZ);
                    return;
                }
            }
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

    private void drawPlayerMarker(Graphics2D graphics, int surfaceWidth, int surfaceHeight,
                                  int originX, int originZ, double pixelsPerBlock) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int cx = (int) Math.round((player.getX() - originX) * pixelsPerBlock);
        int cz = (int) Math.round((player.getZ() - originZ) * pixelsPerBlock);
        if (cx < -8 || cz < -8 || cx >= surfaceWidth + 8 || cz >= surfaceHeight + 8) {
            return;
        }
        graphics.setColor(PLAYER_MARKER_OUTLINE);
        graphics.fillRect(cx - 3, cz - 3, 7, 7);
        graphics.setColor(PLAYER_MARKER_FILL);
        graphics.fillRect(cx - 2, cz - 2, 5, 5);
    }

    private static long playerChunkKey() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? NO_CHUNK : ChunkPos.asLong(player.blockPosition());
    }

    private void repaintAroundPlayer(long key) {
        int chunkX = ChunkPos.getX(key);
        int chunkZ = ChunkPos.getZ(key);
        repaintChunk(ChunkPos.asLong(chunkX, chunkZ));
        repaintChunk(ChunkPos.asLong(chunkX + 1, chunkZ));
        repaintChunk(ChunkPos.asLong(chunkX, chunkZ + 1));
        repaintChunk(ChunkPos.asLong(chunkX + 1, chunkZ + 1));
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
        BuildingWork work = BuildingWork.parse(building.type());
        if (work != null) {
            String label = work.label();
            Tooltip.show(document, new Position(event.clientX, event.clientY),
                    label + "：" + building.name(), TIP_OPTIONS);
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
        if (roadDrawing) {
            endRoadStroke();
        }
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
        if (isRoadHost() && e.button == RIGHT_BUTTON) {
            roadDrawing = true;
            roadLastX = Double.NaN;
            roadLastZ = Double.NaN;
            roadHoverInvalid = false;
            RoadClientStore.beginStroke();
            stampRoadPoint(e.offsetX, e.offsetY);
            return;
        }
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
        if (roadDrawing) {
            stampRoadPoint(e.offsetX, e.offsetY);
            return;
        }
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
        if (roadDrawing) {
            if (e.button == RIGHT_BUTTON) {
                endRoadStroke();
            }
            return;
        }
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

    private static boolean isRoadHost() {
        return roadMode && Minecraft.getInstance().screen instanceof WorldRoadScreen;
    }

    public static void setRoadMode(boolean value) {
        roadMode = value;
        if (!value) {
            roadDrawing = false;
            roadLastX = Double.NaN;
            roadLastZ = Double.NaN;
            roadHoverInvalid = false;
        }
        selectionDirty = true;
    }

    public static Set<Long> ownTerritoryChunks() {
        Set<Long> chunks = new HashSet<>();
        for (MapOverlayStore.TerritoryView territory : MapOverlayStore.territories()) {
            if (territory.self()) {
                chunks.addAll(territory.chunks());
            }
        }
        return chunks;
    }

    private void endRoadStroke() {
        roadDrawing = false;
        roadLastX = Double.NaN;
        roadLastZ = Double.NaN;
        RoadClientStore.endStroke();
        requestRepaint();
    }

    private void stampRoadPoint(double screenX, double screenY) {
        if (paintedPixelsPerBlock <= 0.0 || !roadDrawing) {
            return;
        }
        int worldX = paintedOriginX + (int) Math.round(toSurfaceX(screenX) / paintedPixelsPerBlock);
        int worldZ = paintedOriginZ + (int) Math.round(toSurfaceY(screenY) / paintedPixelsPerBlock);
        roadHoverX = worldX;
        roadHoverZ = worldZ;
        roadHoverInvalid = false;
        if (Double.isNaN(roadLastX)) {
            stampRoadCell(worldX, worldZ);
        } else {
            stampRoadLine((int) Math.floor(roadLastX), (int) Math.floor(roadLastZ), worldX, worldZ);
        }
    }

    private void stampRoadLine(int fromX, int fromZ, int toX, int toZ) {
        int steps = Math.max(Math.abs(toX - fromX), Math.abs(toZ - fromZ));
        if (steps == 0) {
            stampRoadCell(toX, toZ);
            return;
        }
        for (int step = 0; step <= steps; step++) {
            int x = fromX + Math.round((float) (toX - fromX) * step / steps);
            int z = fromZ + Math.round((float) (toZ - fromZ) * step / steps);
            stampRoadCell(x, z);
        }
    }

    private void stampRoadCell(int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(worldZ, CHUNK_SIZE);
        if (inOwnTerritory(chunkX, chunkZ) || !ClientMapStore.explored(chunkX, chunkZ)) {
            breakRoadStroke(worldX, worldZ);
            return;
        }
        if (!RoadClientStore.isLoadedChunk(chunkX, chunkZ)) {
            roadHoverInvalid = true;
            breakRoadStroke(worldX, worldZ);
            return;
        }
        if (!Double.isNaN(roadLastX) && worldX == (int) Math.floor(roadLastX)
                && worldZ == (int) Math.floor(roadLastZ)) {
            return;
        }
        RoadClientStore.appendPoint(worldX + 0.5D, worldZ + 0.5D);
        roadLastX = worldX + 0.5D;
        roadLastZ = worldZ + 0.5D;
        requestRepaint();
    }

    private void breakRoadStroke(int worldX, int worldZ) {
        RoadClientStore.breakPolyline();
        roadLastX = Double.NaN;
        roadLastZ = Double.NaN;
        if (inOwnTerritory(Math.floorDiv(worldX, CHUNK_SIZE), Math.floorDiv(worldZ, CHUNK_SIZE))) {
            return;
        }
        if (!ClientMapStore.explored(Math.floorDiv(worldX, CHUNK_SIZE), Math.floorDiv(worldZ, CHUNK_SIZE))) {
            return;
        }
        requestRepaint();
    }

    private static boolean inOwnTerritory(int chunkX, int chunkZ) {
        for (MapOverlayStore.TerritoryView territory : MapOverlayStore.territories()) {
            if (territory.self() && territory.containsChunk(chunkX, chunkZ)) {
                return true;
            }
        }
        return false;
    }

    private void drawRoadLayer(Graphics2D graphics) {
        if (!isRoadHost() || paintedPixelsPerBlock <= 0.0) {
            return;
        }
        Set<Long> ownChunks = ownTerritoryChunks();
        List<RoadPolyline> lines = RoadClientStore.flatten();
        boolean[] rooted = RoadClientStore.computeRooted(lines, ownChunks);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float width = (float) Math.max(1.0, RoadPolyline.RADIUS * 2.0 * paintedPixelsPerBlock);
        graphics.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int flatIndex = 0;
        for (RoadClientStore.Stroke stroke : RoadClientStore.strokes()) {
            for (RoadPolyline line : stroke.polylines()) {
                drawRoadPolyline(graphics, line, rooted[flatIndex++]);
            }
            if (!stroke.openPoints().isEmpty()) {
                drawRoadPolyline(graphics, RoadPolyline.of(stroke.openPoints()), rooted[flatIndex++]);
            }
        }
        graphics.setStroke(new BasicStroke(1.0F));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        drawRoadHoverWarning(graphics);
    }

    private void drawRoadPolyline(Graphics2D graphics, RoadPolyline line, boolean rooted) {
        graphics.setColor(new Color(rooted ? ROAD_VALID_COLOR : ROAD_INVALID_COLOR, true));
        if (line.pointCount() == 1) {
            int px = (int) Math.round((line.xs()[0] - paintedOriginX) * paintedPixelsPerBlock);
            int py = (int) Math.round((line.zs()[0] - paintedOriginZ) * paintedPixelsPerBlock);
            int radius = (int) Math.max(1.0, RoadPolyline.RADIUS * paintedPixelsPerBlock);
            graphics.fillOval(px - radius, py - radius, radius * 2, radius * 2);
            return;
        }
        for (int index = 1; index < line.pointCount(); index++) {
            int x0 = (int) Math.round((line.xs()[index - 1] - paintedOriginX) * paintedPixelsPerBlock);
            int y0 = (int) Math.round((line.zs()[index - 1] - paintedOriginZ) * paintedPixelsPerBlock);
            int x1 = (int) Math.round((line.xs()[index] - paintedOriginX) * paintedPixelsPerBlock);
            int y1 = (int) Math.round((line.zs()[index] - paintedOriginZ) * paintedPixelsPerBlock);
            graphics.drawLine(x0, y0, x1, y1);
        }
    }

    private void drawRoadHoverWarning(Graphics2D graphics) {
        if (!roadHoverInvalid) {
            return;
        }
        int size = Math.max(2, (int) Math.round(paintedPixelsPerBlock * CHUNK_SIZE));
        int px0 = pixelX(roadHoverX - (Math.floorMod(roadHoverX, CHUNK_SIZE)));
        int py0 = pixelZ(roadHoverZ - (Math.floorMod(roadHoverZ, CHUNK_SIZE)));
        graphics.setColor(new Color(ROAD_HOVER_FILL, true));
        graphics.fillRect(px0, py0, size, size);
        graphics.setColor(new Color(ROAD_HOVER_EDGE, true));
        graphics.fillRect(px0, py0, size, EDGE_WIDTH);
        graphics.fillRect(px0, py0 + size - EDGE_WIDTH, size, EDGE_WIDTH);
        graphics.fillRect(px0, py0, EDGE_WIDTH, size);
        graphics.fillRect(px0 + size - EDGE_WIDTH, py0, EDGE_WIDTH, size);
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
        