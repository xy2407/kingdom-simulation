package com.xy2407.kingdom.client.structure;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.sighs.apricityui.spi.AuiRenderService;
import com.sighs.apricityui.spi.AuiServices;
import com.sighs.apricityui.spi.FboHandle;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.structure.StructureLod;
import com.xy2407.kingdom.common.structure.StructureModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.io.IOException;

/**
 * 建筑预览的离屏渲染目标，把烘焙好的几何画进一张可直接采样的纹理。
 */
@OnlyIn(Dist.CLIENT)
public final class StructurePreviewTarget {

    public static final ResourceLocation TEXTURE_ID =
            ResourceLocation.parse("xy2407_kingdom:structure_preview");

    public static final ResourceLocation DEFAULT_STRUCTURE =
            ResourceLocation.parse("minecraft:village/plains/town_centers/plains_meeting_point_1");

    private static final int WIDTH = 512;
    private static final int HEIGHT = 384;

    private static final StructureCamera CAMERA = new StructureCamera();

    private static FboHandle fbo;
    private static FboTexture shim;
    private static TextureManager registeredInto;
    private static boolean fboHasContent;

    private static volatile long lastAliveNanos;

    private static StructureModel currentModel;
    private static StructureMesh currentMesh;
    private static ResourceLocation currentId;
    private static StructureLod lodOverride;
    private static boolean defaultStructureAttempted;
    private static boolean vanillaScreenActive;

    private StructurePreviewTarget() {
    }

    public static StructureCamera camera() {
        return CAMERA;
    }

    public static void markAlive() {
        lastAliveNanos = System.nanoTime();
    }

    public static boolean isAlive() {
        return System.nanoTime() - lastAliveNanos < 500_000_000L;
    }

    public static void setVanillaScreenActive(boolean active) {
        vanillaScreenActive = active;
    }

    public static boolean isVanillaScreenActive() {
        return vanillaScreenActive;
    }

    public static ResourceLocation currentStructureId() {
        return currentId;
    }

    public static StructureModel model() {
        return currentModel;
    }

    public static int currentBlockCount() {
        return currentModel == null ? 0 : currentModel.blocks().size();
    }

    public static StructureLod currentLod() {
        return currentMesh == null ? null : currentMesh.lod();
    }

    public static int currentSectionCount() {
        return currentMesh == null ? 0 : currentMesh.sectionCount();
    }

    public static boolean isReady() {
        return fbo != null;
    }

    public static void show(ResourceLocation structureId) {
        if (structureId == null) {
            StructureDebugChat.once("show-null", "show 跳过：结构 id 为 null");
            return;
        }
        if (structureId.equals(currentId)) {
            StructureDebugChat.once("show-same", "show 跳过：%s 已是当前建筑", structureId);
            return;
        }
        StructureDebugChat.chat("开始加载建筑：%s", structureId);
        releaseMesh();

        StructureModel model = StructureLoader.load(structureId);
        if (model == null) {
            currentId = null;
            StructureDebugChat.chat("加载失败：模型为 null，预览将保持空白");
            return;
        }
        currentModel = model;
        currentId = structureId;
        bakeCurrentModel();
    }

    public static void setLodOverride(StructureLod lod) {
        if (lodOverride == lod) {
            return;
        }
        lodOverride = lod;
        if (currentModel == null) {
            return;
        }
        if (currentMesh != null) {
            currentMesh.close();
            currentMesh = null;
        }
        bakeCurrentModel();
    }

    private static void bakeCurrentModel() {
        currentMesh = StructureMesher.build(currentModel, lodOverride);
        CAMERA.frame(currentModel);
        CAMERA.reset();
        fboHasContent = false;
        StructureDebugChat.chat("烘焙完成：lod=%s 段数=%d 包围盒=%s",
                currentMesh.lod(), currentMesh.sectionCount(), currentMesh.bounds());
    }

    public static void ensureDefaultStructure() {
        if (currentId != null || defaultStructureAttempted) {
            StructureDebugChat.once("default-skip", "默认建筑跳过：currentId=%s 已尝试=%s",
                    currentId, defaultStructureAttempted);
            return;
        }
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            StructureDebugChat.once("default-noserver",
                    "默认建筑跳过：单人服务端未就绪（多人环境客户端读不到 data/ 结构）");
            return;
        }
        defaultStructureAttempted = true;
        StructureDebugChat.chat("开始加载默认建筑：%s", DEFAULT_STRUCTURE);
        show(DEFAULT_STRUCTURE);
    }

    public static void renderFrame() {
        renderFrameInternal(false);
    }

    public static void forceRenderFrame() {
        renderFrameInternal(true);
    }

    private static void renderFrameInternal(boolean force) {
        ensureRegistered();
        if (fbo == null) {
            StructureDebugChat.once("rf-nofbo", "离屏渲染跳过：离屏目标未创建");
            return;
        }
        if (currentMesh == null) {
            StructureDebugChat.once("rf-nomesh", "离屏渲染跳过：建筑几何为 null（未加载成功）");
            return;
        }
        if (currentMesh.isEmpty()) {
            StructureDebugChat.once("rf-empty", "离屏渲染跳过：几何没有任何可见段");
            return;
        }
        boolean cameraChanged = CAMERA.consumeDirty();
        if (!force && !cameraChanged && fboHasContent) {
            StructureDebugChat.once("rf-clean", "离屏渲染跳过：相机未变化且画面已就绪");
            return;
        }

        AuiRenderService render = AuiServices.render();
        Matrix4f savedProjection = new Matrix4f(render.getProjectionMatrix());
        try (AuiRenderService.RenderStateScope ignored = render.pushFilterRenderState()) {
            render.bindWrite(fbo, true);
            render.clear(fbo, 0.07f, 0.08f, 0.10f, 1.0f);
            render.enableDepthTest();
            render.setDepthMask(true);
            render.enableCull();
            float aspect = (float) WIDTH / HEIGHT;
            Matrix4f projection = CAMERA.projectionMatrix(aspect);
            render.setProjectionMatrix(projection);

            currentMesh.draw(CAMERA.viewMatrix(), projection);
            fboHasContent = true;
            StructureDebugChat.once("rf-done", "离屏渲染成功：段数=%d lod=%s 宽高比=%.3f",
                    currentMesh.sectionCount(), currentMesh.lod(), aspect);
        } catch (RuntimeException exception) {
            ModMain.LOGGER.error("Structure preview render failed", exception);
            StructureDebugChat.chat("离屏渲染异常：%s: %s",
                    exception.getClass().getSimpleName(), exception.getMessage());
        } finally {
            render.bindWrite(render.getMainRenderTarget(), true);
            render.setProjectionMatrix(savedProjection);
        }
    }

    private static void ensureRegistered() {
        TextureManager manager = Minecraft.getInstance().getTextureManager();
        if (registeredInto == manager && fbo != null) {
            return;
        }
        StructureDebugChat.chat("创建离屏渲染目标：%dx%d（纹理管理器变更或首次创建）", WIDTH, HEIGHT);
        releaseOffscreen();

        AuiRenderService render = AuiServices.render();
        fbo = render.createOffscreenTarget(WIDTH, HEIGHT, true);
        if (fbo == null) {
            StructureDebugChat.chat("创建离屏渲染目标失败：AUI 渲染服务返回 null");
            return;
        }
        RenderTarget target = fbo.as();
        shim = new FboTexture(target.getColorTextureId());
        render.registerTexture(shim, TEXTURE_ID);
        registeredInto = manager;
        fboHasContent = false;
        StructureDebugChat.chat("离屏纹理已登记：%s glId=%d", TEXTURE_ID, target.getColorTextureId());
    }

    private static void releaseOffscreen() {
        if (fbo != null) {
            AuiRenderService render = AuiServices.render();
            if (registeredInto != null) {
                render.releaseTexture(TEXTURE_ID);
            }
            render.destroyBuffers(fbo);
        }
        fbo = null;
        shim = null;
        registeredInto = null;
    }

    public static void clear() {
        releaseMesh();
    }

    private static void releaseMesh() {
        if (currentMesh != null) {
            currentMesh.close();
        }
        currentMesh = null;
        currentModel = null;
    }

    private static final class FboTexture extends AbstractTexture {
        private FboTexture(int glTextureId) {
            this.id = glTextureId;
        }

        @Override
        public void load(ResourceManager resourceManager) throws IOException {
        }

        @Override
        public void close() {
        }
    }
}
