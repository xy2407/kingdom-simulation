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
 * 建筑预览的离屏渲染目标。
 *
 * <p>内容渲染进一张带深度的离屏纹理，再登记到 Minecraft 的 TextureManager 上，
 * AUI 页面里的 {@code <structure-preview>} 元素就能直接把它当普通贴图采样，
 * 全程不经过 GPU -> CPU 回读。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class StructurePreviewTarget {

    /** 登记给 AUI 页面引用的纹理位置。 */
    public static final ResourceLocation TEXTURE_ID =
            ResourceLocation.parse("xy2407_kingdom:structure_preview");

    /** 调试默认建筑：原版平原村庄中心，town_centers 里体量最大的一个。 */
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
    /** 非 null 时强制使用该档位，否则按包围盒体积自动选档。 */
    private static StructureLod lodOverride;
    /** 默认建筑已尝试加载过；失败后不再重试，避免每帧刷日志。 */
    private static boolean defaultStructureAttempted;
    /** 原版调试屏打开时置 true，用于让 level-stage 钩子停掉提前渲染。 */
    private static boolean vanillaScreenActive;

    private StructurePreviewTarget() {
    }

    public static StructureCamera camera() {
        return CAMERA;
    }

    /** 由预览元素在每次绘制时调用，表示当前确实有页面在用它。 */
    public static void markAlive() {
        lastAliveNanos = System.nanoTime();
    }

    /** 半秒内有过绘制才认为仍在被使用。 */
    public static boolean isAlive() {
        return System.nanoTime() - lastAliveNanos < 500_000_000L;
    }

    /** 原版调试屏接管渲染。 */
    public static void setVanillaScreenActive(boolean active) {
        vanillaScreenActive = active;
    }

    public static boolean isVanillaScreenActive() {
        return vanillaScreenActive;
    }

    public static ResourceLocation currentStructureId() {
        return currentId;
    }

    /** 当前已加载的建筑模型；未加载时为 null。 */
    public static StructureModel model() {
        return currentModel;
    }

    public static int currentBlockCount() {
        return currentModel == null ? 0 : currentModel.blocks().size();
    }

    /** 当前使用的细节档位；未加载建筑时为 null。 */
    public static StructureLod currentLod() {
        return currentMesh == null ? null : currentMesh.lod();
    }

    public static int currentSectionCount() {
        return currentMesh == null ? 0 : currentMesh.sectionCount();
    }

    /** 离屏纹理是否已登记，可以安全采样。 */
    public static boolean isReady() {
        return fbo != null;
    }

    /** 切换当前显示的建筑：重新解析、重新烘焙，并把相机取景重置。 */
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

    /** 强制使用的细节档位；传 null 表示按包围盒体积自动选档。 */
    public static void setLodOverride(StructureLod lod) {
        if (lodOverride == lod) {
            return;
        }
        lodOverride = lod;
        if (currentModel == null) {
            return;
        }
        // 模型没变，只是换档位重新烘焙
        if (currentMesh != null) {
            currentMesh.close();
            currentMesh = null;
        }
        bakeCurrentModel();
    }

    /** 烘焙当前模型并重置取景。 */
    private static void bakeCurrentModel() {
        currentMesh = StructureMesher.build(currentModel, lodOverride);
        CAMERA.frame(currentModel);
        CAMERA.reset();
        fboHasContent = false;
        StructureDebugChat.chat("烘焙完成：lod=%s 段数=%d 包围盒=%s",
                currentMesh.lod(), currentMesh.sectionCount(), currentMesh.bounds());
    }

    /** 页面打开时懒加载默认建筑；只尝试一次，失败后不再重试。 */
    public static void ensureDefaultStructure() {
        if (currentId != null || defaultStructureAttempted) {
            StructureDebugChat.once("default-skip", "默认建筑跳过：currentId=%s 已尝试=%s",
                    currentId, defaultStructureAttempted);
            return;
        }
        // 单人世界还没就绪时不算失败，等世界加载完再试一次
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            StructureDebugChat.once("default-noserver",
                    "默认建筑跳过：单人服务端未就绪（多人环境客户端读不到 data/ 结构）");
            return;
        }
        defaultStructureAttempted = true;
        StructureDebugChat.chat("开始加载默认建筑：%s", DEFAULT_STRUCTURE);
        show(DEFAULT_STRUCTURE);
    }

    /**
     * 把当前建筑渲染进离屏纹理。
     *
     * <p>相机没有变化且画面已经画过时直接跳过，避免静止时每帧白跑一遍整个建筑。</p>
     */
    public static void renderFrame() {
        renderFrameInternal(false);
    }

    /** 原版调试屏每次打开都强制重新绘制一次，不依赖相机脏标记。 */
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
            // 同时清掉颜色与深度，否则上一帧的深度会参与这一帧的深度测试
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

    /**
     * 确保离屏目标与纹理已就绪。
     *
     * <p>资源重载会重建 TextureManager，此前的登记会丢失；这里用管理器实例做判据，
     * 一旦变化就整体重建，避免留下"还登记着但 GL id 已失效"的悬空贴图。</p>
     */
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

    /** 释放当前建筑模型与其 GPU 网格，关闭预览列表时调用。 */
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

    /**
     * 指向离屏颜色纹理的轻量 {@link AbstractTexture} 壳。
     *
     * <p>只借用 {@code id} 字段指向已存在的 GL 纹理，不拥有其生命周期：
     * {@link #close()} 刻意留空，释放交给离屏目标的销毁流程。</p>
     */
    private static final class FboTexture extends AbstractTexture {
        private FboTexture(int glTextureId) {
            this.id = glTextureId;
        }

        @Override
        public void load(ResourceManager resourceManager) throws IOException {
            // 纹理内容由离屏渲染提供，不参与资源加载
        }

        @Override
        public void close() {
            // 纹理 id 归离屏目标所有
        }
    }
}