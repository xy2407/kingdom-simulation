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
 *
 * <p>表上再叠两样：每 {@link #CHUNK_SIZE} 格一条区块边框，以及市政厅位置的标记
 * （世界坐标由宿主写进 {@code marker-x} / {@code marker-z}）。</p>
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(WorldMapElement.TAG_NAME)
public class WorldMapElement extends Element {

    public static final String TAG_NAME = "WORLD-MAP";

    private static final String ATTR_CENTER_X = "center-x";
    private static final String ATTR_CENTER_Z = "center-z";
    private static final String ATTR_RADIUS = "radius";
    private static final String ATTR_ZOOM = "zoom";
    /** 要标记的世界坐标（市政厅所在处）。两个都不写就不画标记。 */
    private static final String ATTR_MARKER_X = "marker-x";
    private static final String ATTR_MARKER_Z = "marker-z";

    /**
     * 标记用的原版地图图标。
     *
     * <p>{@code textures/map/decorations/} 下的原版贴图。想换外观就改这一行——
     * 同目录还有 {@code red_x} / {@code banner_white} / {@code village_plains} /
     * {@code mansion} 等，都是 8×8 的像素图。</p>
     */
    private static final String MARKER_TEXTURE = "textures/map/decorations/target_x.png";

    /** 标记在屏幕上的边长（GUI 像素）。跟原版一样固定大小，不随地图缩放变。 */
    private static final int MARKER_SIZE = 16;

    private static final int DEFAULT_RADIUS = 192;
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_PER_NOTCH = 1.25;

    /** 拖动时的降采样步长。 */
    private static final int DRAG_STEP = 3;

    /** 未探索区块的颜色，与原版一致：纯黑。 */
    private static final int UNEXPLORED_COLOR = 0xFF000000;

    /** 区块边长（方块）。地图上每隔这么远画一条浅线。 */
    private static final int CHUNK_SIZE = 16;

    /** 一个区块在屏幕上不足这么多像素就不画边框——缩得太狠时线会挤成一片，比不画还糊。 */
    private static final double MIN_CHUNK_GRID_PIXELS = 10.0;

    /**
     * 区块边框的颜色：四分之一白。
     *
     * <p>要"较浅"就得靠 alpha——它和底下的地形做 source-over 混合，压在任何颜色上都只是
     * 提亮一点，不会盖住地形。嫌不明显就把 alpha 往上调（{@code 0x60} 更清楚，
     * {@code 0x20} 几乎看不见）。</p>
     */
    private static final int CHUNK_GRID_COLOR = 0x40FFFFFF;

    private static final long STALE_DRAG_NANOS = 500_000_000L;

    private double zoom = 1.0;
    private double panX;
    private double panZ;

    /** 上次发出请求的区域（原点 + 边长）。手上缓存跟它对不上就该重新要。 */
    private int requestedOriginX = Integer.MIN_VALUE;
    private int requestedOriginZ = Integer.MIN_VALUE;
    private int requestedSize = -1;
    private long drawnRevision = -1;

    private SpanSheet sheet;
    private String signature;

    /** 取色探针只打一次，避免刷屏。 */
    private static boolean colorProbed;

    /** 原版标记图标的像素（ARGB 行优先）。读一次就缓存，读不到则保持 null。 */
    private static int[][] markerPixels;
    private static boolean markerAttempted;

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

    /* ------------------------------------------------------------------ 数据 */

    /**
     * 手上没有"当前中心点这块区域"的数据，就向服务端要一份。
     *
     * <p>判断依据是<b>区域是否吻合</b>（原点 + 边长），不是"收齐了没有"。
     * 早先只看 {@code isComplete()}，而那个标志一旦收齐就永远为真，
     * 于是同一局里换了中心点也不会重采——拖动只是拿旧快照做视觉偏移，
     * 拖出已加载范围就是一片黑。</p>
     */
    private void ensureData() {
        int radius = radius();
        int size = radius * 2 + 1;
        int originX = (int) Math.round(centerX()) - radius;
        int originZ = (int) Math.round(centerZ()) - radius;

        if (!WorldMapClientData.hasRegion(originX, originZ, size)) {
            /* 同一块区域只请求一次，免得每帧轰炸服务端 */
            if (requestedOriginX != originX || requestedOriginZ != originZ || requestedSize != size) {
                requestedOriginX = originX;
                requestedOriginZ = originZ;
                requestedSize = size;
                WorldMapClientData.markPending();
                PacketDistributor.sendToServer(
                        new WorldMapRequestPacket((int) Math.round(centerX()),
                                (int) Math.round(centerZ()), radius));
            }
            return;
        }
        if (WorldMapClientData.revision() != drawnRevision) {
            drawnRevision = WorldMapClientData.revision();
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

    /**
     * 把服务端发来的调色板打包 id 解成 AUI 要的 <b>ARGB</b>。
     *
     * <p>原版 {@code MapColor.getColorFromPackedId} 吐出来的<b>不是</b> ARGB：它的
     * <b>低字节是红、高字节是蓝</b>，也就是 {@code 0xAABBGGRR}。这不是它写错了——
     * 那个值一直是直接喂 {@code NativeImage#setPixelRGBA} 的，而 NativeImage 的像素
     * 本来就是 ABGR 序。实测可以把这一点钉死：{@code MapColor.WOOD.col} 是
     * {@code 0x8F7748}（棕），解出来却是 {@code 0xFF48778F}，恰是逐字节反向。</p>
     *
     * <p>而 AUI 这一侧从头到尾按标准 ARGB 解——{@code FastColor.ARGB32} 的定义、
     * {@link SpanSheet} 里的混合、{@code Graph} 的顶点色，全是"红在 {@code >>16}、
     * 蓝在 {@code &0xFF}"。照直把原版那个值传进去，红蓝就会对调：草地发青、沙地发蓝，
     * 整张地图偏冷发灰。这里用 {@code FastColor.ARGB32} 显式重装一遍。</p>
     */
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

    /**
     * 一次性取色探针：把"服务端发来的打包值"和"原版调色板解出来的颜色"对照打出来。
     *
     * <p>颜色错位这种事光看截图猜不出来——是服务端把颜色算错了，还是客户端解错了，
     * 打一行就能分开。只打一次，不刷屏。</p>
     */
    private static void probeColorOnce(int centerX, int centerZ) {
        if (colorProbed || !WorldMapClientData.isComplete()) {
            return;
        }
        colorProbed = true;
        int packed = WorldMapClientData.packedAt(centerX, centerZ);
        int colorId = (packed & 0xFF) >> 2;
        int brightness = packed & 3;
        int argb = mapArgb(packed);
        /* 自检：WOOD 的基础色是 0x8F7748，把它的打包 id 走一遍 mapArgb 应能还原出同一个值。
           对得上就说明"打包 id → ARGB"这一环没问题；对不上就是这里的通道序又错了。
           （MapColor 不是枚举，没有 values()/name()，只能拿它的基础色字段来对。） */
        int woodPacked = MapColor.WOOD.getPackedId(MapColor.Brightness.HIGH) & 0xFF;
        ModMain.LOGGER.info("[地图] 取色探针 center=({}, {}) packed={} colorId={} brightness={} argb=#{} 未探索={}",
                centerX, centerZ, packed, colorId, brightness,
                Integer.toHexString(argb), WorldSurfaceSampler.UNEXPLORED);
        ModMain.LOGGER.info("[地图] 自检 MapColor.WOOD：col=#{} 经 mapArgb 还原=#{}（两者应一致）",
                Integer.toHexString(MapColor.WOOD.col & 0xFFFFFF),
                Integer.toHexString(mapArgb(woodPacked) & 0xFFFFFF));
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

        /* 每世界方块占几个屏幕像素：视野横向覆盖 radius × 2 + 1 格 */
        double blocksAcross = radius() * 2.0 + 1.0;
        double scale = width / blocksAcross / zoom;
        if (scale <= 0.0) {
            return result;
        }

        if (WorldMapClientData.isComplete()) {
            int px = Math.max(1, (int) Math.ceil(scale * step));
            int unexplored = WorldSurfaceSampler.UNEXPLORED;

            for (int py = 0; py < height; py += px) {
                int wz = centerZ + (int) Math.round((py + px / 2.0 - height / 2.0) / scale);
                for (int pxi = 0; pxi < width; pxi += px) {
                    int wx = centerX + (int) Math.round((pxi + px / 2.0 - width / 2.0) / scale);
                    int packed = WorldMapClientData.packedAt(wx, wz);
                    if (packed == unexplored) {
                        continue;   // 未探索，留着黑底
                    }
                    /* 原版地图的取色：把服务端算好的打包 id（含明暗档）交给原版调色板。
                       明暗档由服务端按"本列高度与四邻比较"定，地形起伏就是这样表现出来的。
                       注意必须经 mapArgb 换序——原版那个返回值是 ABGR，见该方法注释。 */
                    result.fill(pxi, py,
                            Math.min(width, pxi + px), Math.min(height, py + px),
                            mapArgb(packed));
                }
            }

            /* 区块边框叠在地形之上。放进数据判齐里：没数据时整块是黑的，画格子只是徒增噪点。 */
            drawChunkGrid(result, width, height, centerX, centerZ, scale, step);
        }

        /* 标记永远最后画：它标的是市政厅的位置，跟"这块地图数据收齐没有"无关——
           数据还没回来时整块是黑的，那时更该看得见标记。 */
        drawMarker(result, width, height, centerX, centerZ, scale, step);

        return result;
    }

    /**
     * 叠一层区块边框：每 {@link #CHUNK_SIZE} 格一条浅线，跟着地图一起缩放与平移。
     *
     * <p>换算与 {@link #build} 反解世界坐标是同一套，只是方向相反：
     * 世界 X 落在 {@code 16k} 上的那条竖线，屏幕横坐标是
     * {@code 画布宽/2 + (16k - 中心X) × scale}。所以拖动和缩放时它自然跟着地面走，
     * 不用额外记账。</p>
     *
     * <p>两个收敛：一个区块在屏幕上不足 {@link #MIN_CHUNK_GRID_PIXELS} 像素时整层不画；
     * 拖动期间地图按 {@link #DRAG_STEP} 降采样，线宽也跟着加粗——不加粗的话，
     * 一像素宽的线会被降采样整条抹掉，看着就像拖动时边框消失了。</p>
     */
    private static void drawChunkGrid(SpanSheet sheet, int width, int height,
                                      int centerX, int centerZ, double scale, int step) {
        if (scale <= 0.0 || scale * CHUNK_SIZE < MIN_CHUNK_GRID_PIXELS) {
            return;
        }
        int thickness = Math.max(1, step);

        /* 竖线：世界 X 是 CHUNK_SIZE 的倍数 */
        int leftWorld = centerX + (int) Math.floor(-width / 2.0 / scale);
        int rightWorld = centerX + (int) Math.ceil(width / 2.0 / scale);
        for (int worldX = Math.floorDiv(leftWorld, CHUNK_SIZE) * CHUNK_SIZE;
             worldX <= rightWorld; worldX += CHUNK_SIZE) {
            int px = (int) Math.round(width / 2.0 + (worldX - centerX) * scale);
            sheet.fill(px, 0, px + thickness, height, CHUNK_GRID_COLOR);
        }

        /* 横线：世界 Z 是 CHUNK_SIZE 的倍数 */
        int topWorld = centerZ + (int) Math.floor(-height / 2.0 / scale);
        int bottomWorld = centerZ + (int) Math.ceil(height / 2.0 / scale);
        for (int worldZ = Math.floorDiv(topWorld, CHUNK_SIZE) * CHUNK_SIZE;
             worldZ <= bottomWorld; worldZ += CHUNK_SIZE) {
            int py = (int) Math.round(height / 2.0 + (worldZ - centerZ) * scale);
            sheet.fill(0, py, width, py + thickness, CHUNK_GRID_COLOR);
        }
    }

    /* ------------------------------------------------------------------ 标记 */

    /**
     * 把标记图标叠到地图上。
     *
     * <p>世界坐标 (marker-x, marker-z) 换成画布像素是
     * {@code 画布中心 + (标记点 - 地图中心) × scale}——与 {@link #build} 里反解世界坐标
     * 是同一套换算，方向相反。</p>
     *
     * <p>图标按 {@link #MARKER_SIZE} 的固定像素大小贴，跟原版地图一样<b>不随缩放变</b>：
     * 缩放时它跟着地面移动，但自身不变大变小。</p>
     */
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
                return;   // 页面没配标记点，不画
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

        /* 整块都在画布外就跳过，省掉一次逐像素循环 */
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
                    continue;   // 全透明像素跳过，否则会在图上盖出个方洞
                }
                sheet.fill(left + x, top + y,
                        Math.min(left + x + stride, left + MARKER_SIZE),
                        Math.min(top + y + stride, top + MARKER_SIZE), argb);
            }
        }
    }

    /** 原版标记图标，读一次缓存。读不到就返回 null——界面上只是没有标记，不影响其它部分。 */
    private static int[][] markerPixels() {
        if (!markerAttempted) {
            markerAttempted = true;
            markerPixels = loadMarker();
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
                    /* getRGB 给的是 ARGB，SpanSheet.fill 也吃 ARGB，正好对上 */
                    pixels[y][x] = image.getRGB(x, y);
                }
            }
            return pixels;
        } catch (Exception missing) {
            /* 图标缺失只影响这一个标记，不值得把整页拖下水 */
            return null;
        }
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
        /* 松手才把平移量折算进中心点：地图是"拖到哪看哪"，
           新区域交给服务端重采。拖动过程中不请求，免得一路拖一路炸服务端。 */
        commitPan();
    }

    /**
     * 把拖动累积的平移量折进 center-x / center-z。
     *
     * <p>平移量的单位本来就是「世界格」，所以直接加到中心点上、再归零——
     * 画面不会跳，而下一次 {@link #ensureData()} 会发现区域变了，自然去要新的一块。</p>
     */
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
            /* 写回 zoom 属性。两个原因：
               ① 放大后的全屏地图要继承当前视角，读的就是这个属性；
               ② applyAttributeOverrides() 每帧按属性校准字段，不写回就会被它拉回原值
                  （页面没写 zoom 属性时不会，写了就会——所以这里统一写回，行为才确定）。
               panX/panZ 的单位是世界格，缩放不改变"看的是哪一块地面"，不用动它。 */
            setAttribute(ATTR_ZOOM, String.valueOf(next));
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
