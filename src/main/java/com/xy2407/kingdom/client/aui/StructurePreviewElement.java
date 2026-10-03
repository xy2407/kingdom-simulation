package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.ImageDrawer;
import com.sighs.apricityui.render.Rect;
import com.sighs.apricityui.spi.TextureKey;
import com.xy2407.kingdom.client.structure.StructureCamera;
import com.xy2407.kingdom.client.structure.StructureDebugChat;
import com.xy2407.kingdom.client.structure.StructureLoader;
import com.xy2407.kingdom.client.structure.StructureMesher;
import com.xy2407.kingdom.client.structure.StructurePreviewTarget;
import com.xy2407.kingdom.client.structure.KingdomSimulationStructures;
import com.xy2407.kingdom.client.watchdog.ClientWatchdog;
import com.xy2407.kingdom.common.structure.StructureLod;
import com.xy2407.kingdom.common.structure.StructureModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * {@code <structure-preview>}：在 AUI 页面里直接逐方块渲染建筑，并承接鼠标交互。
 *
 * <p>不走离屏/纹理贴图，而是在 {@link #drawPhase} 阶段用原版方块管线逐方块画进当前
 * GUI 帧（同原版屏那套 immediate 渲染）。LOD 与 {@code StructureMesher} 共用同一套合并规则
 * （每 4³ / 16³ 个方块合成一个体素、取格内出现次数最多的方块，再把该方块放大到整格），
 * 所以这里的卡片预览和世界空间的投影看起来是同一种粗度，而不是每隔 N 格丢一格的稀疏点阵。</p>
 *
 * <ul>
 *   <li>左键拖动 —— 旋转</li>
 *   <li>右键 / 中键拖动 —— 平移</li>
 *   <li>滚轮 —— 缩放</li>
 * </ul>
 *
 * <p>可选读 {@code yaw} / {@code pitch} / {@code zoom} / {@code lod} 属性覆盖初始视角与档位。</p>
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(StructurePreviewElement.TAG_NAME)
public class StructurePreviewElement extends Element {

    public static final String TAG_NAME = "STRUCTURE-PREVIEW";

    /** 非空气方块数上限（40³ / 100³），对应三档 LOD。 */
    private static final int COARSE_SOLID = 40 * 40 * 40;
    private static final int ROUGH_SOLID = 100 * 100 * 100;

    private static final double ORBIT_DEGREES_PER_PIXEL = 0.45;
    private static final double PAN_PER_PIXEL = 0.02;
    private static final double ZOOM_PER_NOTCH = 1.15;

    private static final int BUTTON_LEFT = 0;
    private static final int BUTTON_RIGHT = 1;
    private static final int BUTTON_MIDDLE = 2;

    private static final long STALE_DRAG_NANOS = 500_000_000L;

    private boolean dragging;
    private int dragButton = -1;
    private double lastX;
    private double lastY;
    private long lastDragNanos;

    /** 页面写死的 LOD 档位；空表示按体积自动选档。 */
    private StructureLod lod;

    private String observedYaw;
    private String observedPitch;
    private String observedZoom;
    private String observedLod;

    /** 每个实例独立的建筑模型与相机：由 {@code structure} 属性驱动，支持单页多卡片各显一栋。 */
    private StructureModel ownModel;
    private StructureCamera ownCamera;
    private String observedStructure;

    /** 降采样体素缓存：模型或档位变化时重建，避免每帧重跑合并。 */
    private StructureModel cachedVoxelModel;
    private StructureLod cachedVoxelLod;
    private List<StructureMesher.DisplayVoxel> cachedVoxels;

    /** 存活卡片登记表，弱引用持有：元素被回收时自动移出。 */
    private static final Set<StructurePreviewElement> LIVE_ELEMENTS =
            Collections.newSetFromMap(new WeakHashMap<>());

    public StructurePreviewElement(Document document) {
        super(document, TAG_NAME);
        LIVE_ELEMENTS.add(this);
        StructureDebugChat.once("el-created", "structure-preview 元素已实例化（标签注册成功）");
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
                if (ClientWatchdog.isSafeMode()) {
                    // 看门狗确认卡死过之后，只保留底框：逐方块渲染是这里唯一的高风险动作。
                    return;
                }
                ClientWatchdog.beginPhase("structure-preview.drawPreview");
                try {
                    drawPreview(poseStack, rect);
                } finally {
                    ClientWatchdog.endPhase();
                }
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    private void drawPreview(PoseStack poseStack, Rect rect) {
        Position position = rect.getBodyRectPosition();
        Size size = rect.getBodyRectSize();
        if (size.width() <= 0 || size.height() <= 0) {
            StructureDebugChat.once("el-zero", "元素绘制跳过：尺寸为 0（%sx%s）", size.width(), size.height());
            return;
        }

        applyAttributeOverrides();
        clearStaleDrag();

        StructureModel model = model();
        if (model == null || model.blocks().isEmpty()) {
            StructureDebugChat.once("el-nomodel", "元素绘制跳过：建筑模型未加载");
            return;
        }

        StructureCamera camera = camera();
        float w = (float) size.width();
        float h = (float) size.height();
        float maxDim = Math.max(1, model.maxDimension());
        float scale = Math.min(w / maxDim, h / maxDim) * 0.7f * (float) camera.zoom();
        float cx = model.size().getX() / 2.0f;
        float cy = model.size().getY() / 2.0f;
        float cz = model.size().getZ() / 2.0f;
        List<StructureMesher.DisplayVoxel> voxels = displayVoxels(model);

        poseStack.pushPose();
        // AUI 把"平台共享缓冲"（MC 的 MultiBufferSource）当成自己的排序对象：Base.commitDraws()
        // 会在文档/遮罩边界这些合法点冲掉它，遮罩切换与深度切换全靠这个顺序成立。我们要往这个
        // 缓冲里塞方块几何，就必须先手动冲一次，否则后面那次 endBatch() 会把 AUI 还没到提交点的
        // 几何（文档里的字体等）提前用方块着色器冲出去，表现就是整页 UI 一起没了。
        Base.commitDraws();
        // 深度测试走 AUI 的状态栈，并在退出时还原 GL 状态：原来只调 RenderSystem.enableDepthTest()
        // 不还原，整个后续 GUI 帧都带着深度测试，AUI 之后画的 UI 会被深度测试挡掉。
        boolean depthTestWasOn = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
        Base.pushDepthTest(true);
        RenderSystem.enableDepthTest();
        try {
            poseStack.translate((float) position.x + w / 2.0f, (float) position.y + h / 2.0f, 100.0f);
            poseStack.scale(scale, -scale, scale);
            poseStack.mulPose(Axis.YP.rotationDegrees((float) camera.yaw()));
            poseStack.mulPose(Axis.XP.rotationDegrees((float) camera.pitch()));
            poseStack.translate(-cx, -cy, -cz);

            Lighting.setupForEntityInInventory();

            Minecraft mc = Minecraft.getInstance();
            BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            for (StructureMesher.DisplayVoxel voxel : voxels) {
                BlockPos pos = voxel.pos();
                poseStack.pushPose();
                poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
                if (voxel.unitSize() > 1) {
                    // 粗档：把一个方块放大到整个体素，纹理铺满，而不是空出一圈变成稀疏点阵
                    poseStack.scale(voxel.unitSize(), voxel.unitSize(), voxel.unitSize());
                }
                dispatcher.renderSingleBlock(voxel.state(), poseStack, buffers,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                poseStack.popPose();
            }
            buffers.endBatch();
            StructureDebugChat.once("el-draw", "元素 immediate 渲染：pos=(%.1f,%.1f) size=%.1fx%.1f 档位=%s 体素=%d",
                    position.x, position.y, w, h, effectiveLod(model), voxels.size());
        } finally {
            Lighting.setupForFlatItems();
            Base.popDepthTest();
            if (!depthTestWasOn) {
                RenderSystem.disableDepthTest();
            }
            poseStack.popPose();
        }
    }

    /** 取当前档位下的降采样体素，按模型与档位缓存，避免每帧重跑合并。 */
    private List<StructureMesher.DisplayVoxel> displayVoxels(StructureModel model) {
        StructureLod target = effectiveLod(model);
        if (cachedVoxels != null && cachedVoxelModel == model && cachedVoxelLod == target) {
            return cachedVoxels;
        }
        cachedVoxelModel = model;
        cachedVoxelLod = target;
        cachedVoxels = StructureMesher.displayVoxels(model, target);
        return cachedVoxels;
    }

    private StructureLod effectiveLod(StructureModel model) {
        if (lod != null) {
            return lod;
        }
        int solid = model.blocks().size();
        if (solid <= COARSE_SOLID) {
            return StructureLod.FINE;
        }
        if (solid <= ROUGH_SOLID) {
            return StructureLod.COARSE;
        }
        return StructureLod.ROUGH;
    }

    private void onMouseDown(Event event) {
        MouseEvent e = (MouseEvent) event;
        dragging = true;
        dragButton = e.button;
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

        StructureCamera camera = camera();
        if (dragButton == BUTTON_LEFT) {
            camera.orbit(dx * ORBIT_DEGREES_PER_PIXEL, dy * ORBIT_DEGREES_PER_PIXEL);
        } else if (dragButton == BUTTON_RIGHT || dragButton == BUTTON_MIDDLE) {
            camera.pan(dx * PAN_PER_PIXEL, -dy * PAN_PER_PIXEL);
        }
    }

    private void onMouseUp(Event event) {
        dragging = false;
        dragButton = -1;
    }

    private void onWheel(Event event) {
        MouseEvent e = (MouseEvent) event;
        StructureCamera camera = camera();
        if (e.scrollDelta > 0.0) {
            camera.zoomBy(ZOOM_PER_NOTCH);
        } else if (e.scrollDelta < 0.0) {
            camera.zoomBy(1.0 / ZOOM_PER_NOTCH);
        }
    }

    private void clearStaleDrag() {
        if (dragging && System.nanoTime() - lastDragNanos > STALE_DRAG_NANOS) {
            dragging = false;
            dragButton = -1;
        }
    }

    private void applyAttributeOverrides() {
        applyStructureAttribute();
        applyCameraAttributes();
        applyLodAttribute();
    }

    private void applyCameraAttributes() {
        String yaw = getAttribute("yaw");
        String pitch = getAttribute("pitch");
        String zoom = getAttribute("zoom");
        String curYaw = blankToNull(yaw);
        String curPitch = blankToNull(pitch);
        String curZoom = blankToNull(zoom);
        if (!java.util.Objects.equals(curYaw, observedYaw) || !java.util.Objects.equals(curPitch, observedPitch)
                || !java.util.Objects.equals(curZoom, observedZoom)) {
            observedYaw = curYaw;
            observedPitch = curPitch;
            observedZoom = curZoom;
            StructureCamera camera = camera();
            camera.setFromAttributes(
                    parseDouble(curYaw, camera.yaw()),
                    parseDouble(curPitch, camera.pitch()),
                    parseDouble(curZoom, camera.zoom())
            );
        }
    }

    /**
     * 按 {@code structure} 属性为当前元素独立加载模型与相机。
     *
     * <p>属性值取 structures 文件夹里的渲染名（如 {@code plains_meeting_point_1}），从
     * {@code <游戏根目录>/kingdom simulation/structures/<名>.nbt} 加载；每个元素持有自己的 model +
     * camera，支持一页多卡片各显一栋。属性为空则回退到全局单例
     * {@link StructurePreviewTarget}（旧单卡行为）。</p>
     */
    private void applyStructureAttribute() {
        String cur = blankToNull(getAttribute("structure"));
        if (java.util.Objects.equals(cur, observedStructure)) {
            return;
        }
        observedStructure = cur;
        if (cur == null) {
            ownModel = null;
            ownCamera = null;
            return;
        }
        Path file = KingdomSimulationStructures.structuresDir().resolve(cur + ".nbt");
        StructureModel loaded = StructureLoader.loadFile(file);
        if (loaded == null || loaded.blocks().isEmpty()) {
            ownModel = null;
            ownCamera = null;
            StructureDebugChat.once("el-structure-empty", "structure 属性已变更为 %s，但加载为空/失败：%s", cur, file);
            return;
        }
        ownModel = loaded;
        ownCamera = new StructureCamera();
        ownCamera.frame(loaded);
        ownCamera.reset();
        StructureDebugChat.once("el-structure", "structure 元素加载：%s 尺寸=%d 方块=%d",
                cur, loaded.maxDimension(), loaded.blocks().size());
    }

    /** 当前元素要渲染的建筑模型；有 {@code structure} 属性时用独立模型，否则用全局单例。 */
    private StructureModel model() {
        if (ownModel != null) {
            return ownModel;
        }
        if (observedStructure == null) {
            return StructurePreviewTarget.model();
        }
        return null;
    }

    /** 当前元素要用的相机；有 {@code structure} 属性时用独立相机，否则用全局单例。 */
    private StructureCamera camera() {
        if (ownCamera != null) {
            return ownCamera;
        }
        return StructurePreviewTarget.camera();
    }

    private void applyLodAttribute() {
        String cur = blankToNull(getAttribute("lod"));
        if (!java.util.Objects.equals(cur, observedLod)) {
            observedLod = cur;
            lod = parseLod(cur);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static StructureLod parseLod(String value) {
        if (value == null) {
            return null;
        }
        try {
            return StructureLod.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 释放全部存活卡片持有的建筑模型与相机，关闭列表时调用。 */
    public static void releaseAll() {
        for (StructurePreviewElement element : new ArrayList<>(LIVE_ELEMENTS)) {
            element.release();
        }
    }

    private void release() {
        ownModel = null;
        ownCamera = null;
        observedStructure = null;
    }
}