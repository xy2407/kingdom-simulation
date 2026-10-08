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
 * {@code <structure-preview>}：AUI 页面里逐方块渲染建筑，并承接鼠标旋转平移缩放。
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(StructurePreviewElement.TAG_NAME)
public class StructurePreviewElement extends Element {

    public static final String TAG_NAME = "STRUCTURE-PREVIEW";

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

    private StructureLod lod;

    private String observedYaw;
    private String observedPitch;
    private String observedZoom;
    private String observedLod;

    private StructureModel ownModel;
    private StructureCamera ownCamera;
    private String observedStructure;

    private StructureModel cachedVoxelModel;
    private StructureLod cachedVoxelLod;
    private List<StructureMesher.DisplayVoxel> cachedVoxels;

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
        Base.commitDraws();
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

    private StructureModel model() {
        if (ownModel != null) {
            return ownModel;
        }
        if (observedStructure == null) {
            return StructurePreviewTarget.model();
        }
        return null;
    }

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
