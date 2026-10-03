package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.element.Canvas;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.Box;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.ImageDrawer;
import com.sighs.apricityui.render.Rect;
import com.xy2407.kingdom.client.entity.NpcEntityRenderer;
import com.xy2407.kingdom.common.entity.NpcEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身份卡片左上角的头像：**直接贴皮肤贴图上那张 2D 的脸**。
 *
 * <p>不是渲染 3D 头部模型，而是按原版皮肤布局取像素：脸在 {@code (8,8)} 的 8×8 区域，
 * 再叠上第二层 {@code (40,8)}（头发/帽子那层），最后按最近邻放大铺满头像框——
 * 也就是原版"玩家头颅"图标的那张脸。</p>
 *
 * <p>皮肤来源是实体自己的渲染器（{@code NpcEntityRenderer.getTextureLocation}），所以
 * 服务端同步下来的 per-市民皮肤、以及"按 UUID 稳定分皮"都自动跟着换；贴图缺失时这一格留空。</p>
 *
 * <p>绘制方式照搬 {@link KingdomSealElement}：{@code extends Canvas} + Java2D 画到表面，
 * 再按元素布局尺寸贴出来，所以任何缩放下边缘都是硬像素、不会糊。</p>
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(KingdomEntityAvatarElement.TAG_NAME)
public class KingdomEntityAvatarElement extends Canvas {

    public static final String TAG_NAME = "KINGDOM-ENTITY-AVATAR";

    /** 原版皮肤布局：脸 (8,8)、第二层 (40,8)，都是 8×8。 */
    private static final int FACE_U = 8;
    private static final int FACE_V = 8;
    private static final int OVERLAY_U = 40;
    private static final int OVERLAY_V = 8;
    private static final int FACE_SIZE = 8;
    /** 超采样倍率：原图只有 8×8，先放大到这里再交给元素缩放。 */
    private static final int RASTER_SCALE = 8;

    /** 已读过的皮肤图；读失败的每次重试，资源重载后能自愈。 */
    private static final Map<ResourceLocation, BufferedImage> SKIN_CACHE = new ConcurrentHashMap<>();

    private double layoutWidth = -1;
    private double layoutHeight = -1;
    /** 当前表面画的是哪张皮肤，用来判断要不要重画。 */
    private ResourceLocation renderedSkin;
    /** 没有目标/没有贴图时不贴表面，避免留下上一张脸。 */
    private boolean faceReady;
    /** {@code skin} 属性的缓存：属性值 → 解析出来的贴图。 */
    private String declaredSkinName;
    private ResourceLocation declaredSkinTexture;

    public KingdomEntityAvatarElement(Document document) {
        super(document);
        this.tagName = TAG_NAME;
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
        Rect rect = Rect.of(this);
        switch (phase) {
            case SHADOW -> rect.drawShadow(poseStack);
            case BODY -> {
                rect.drawBody(poseStack);
                renderFace();
                drawCanvas(poseStack, rect);
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    @Override
    protected void drawCanvas(PoseStack poseStack, Rect rectRenderer) {
        if (!faceReady || layoutWidth <= 0 || layoutHeight <= 0) {
            return;
        }
        syncTexture();
        if (textureLocation == null) {
            return;
        }
        ImageDrawer.draw(poseStack, textureLocation,
                (float) rectRenderer.getContentPosition().x,
                (float) rectRenderer.getContentPosition().y,
                (float) layoutWidth, (float) layoutHeight, true);
    }

    /** 按当前该画的皮肤准备表面；皮肤没变就直接复用上一帧的表面。 */
    private void renderFace() {
        Size contentSize = Box.of(this).innerSize();
        if (contentSize.width() <= 0 || contentSize.height() <= 0) {
            return;
        }
        ResourceLocation skin = currentSkin();
        BufferedImage source = skin == null ? null : skinImage(skin);
        if (source == null) {
            faceReady = false;
            renderedSkin = null;
            return;
        }

        layoutWidth = contentSize.width();
        layoutHeight = contentSize.height();
        int wantedWidth = Math.max(1, (int) Math.ceil(contentSize.width() * RASTER_SCALE));
        int wantedHeight = Math.max(1, (int) Math.ceil(contentSize.height() * RASTER_SCALE));
        if (wantedWidth == getWidth() && wantedHeight == getHeight()
                && skin.equals(renderedSkin)) {
            faceReady = true;
            return;
        }

        resizeSurface(wantedWidth, wantedHeight, false);
        renderedSkin = skin;
        renderOperation(graphics -> paintFace(graphics, source, wantedWidth, wantedHeight));
        faceReady = true;
    }

    /** 取 8×8 脸 → 叠 8×8 第二层 → 最近邻铺满整张表面。 */
    private static void paintFace(Graphics2D graphics, BufferedImage skin, int width, int height) {
        BufferedImage face = new BufferedImage(FACE_SIZE, FACE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D faceGraphics = face.createGraphics();
        try {
            faceGraphics.drawImage(skin, 0, 0, FACE_SIZE, FACE_SIZE,
                    FACE_U, FACE_V, FACE_U + FACE_SIZE, FACE_V + FACE_SIZE, null);
            faceGraphics.drawImage(skin, 0, 0, FACE_SIZE, FACE_SIZE,
                    OVERLAY_U, OVERLAY_V, OVERLAY_U + FACE_SIZE, OVERLAY_V + FACE_SIZE, null);
        } finally {
            faceGraphics.dispose();
        }
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(face, 0, 0, width, height, null);
    }

    /**
     * 当前该画的皮肤。
     *
     * <p>元素写了 {@code skin} 属性（如 {@code skin="female12"}）就按名解析——住户可能在屋里、
     * 甚至在别的区块，按实体根本取不到，只能靠名字。没写属性则沿用原本的行为：跟随玩家瞄准的市民。</p>
     *
     * <p>属性值按上一次的结果缓存，避免每帧重复解析与查表。</p>
     */
    private ResourceLocation currentSkin() {
        String declared = getAttribute("skin");
        if (declared != null && !declared.isBlank()) {
            if (!declared.equals(declaredSkinName)) {
                declaredSkinName = declared;
                declaredSkinTexture = NpcEntityRenderer.resolve(declared);
            }
            return declaredSkinTexture;
        }
        declaredSkinName = null;
        declaredSkinTexture = null;
        NpcEntity target = NpcIdentifyWindow.currentTarget();
        return target == null ? null : skinOf(target);
    }

    /** 目标实体用的皮肤贴图（由它的渲染器决定，因此 per-市民皮肤自动生效）。 */
    private static ResourceLocation skinOf(NpcEntity target) {
        EntityRenderer<? super NpcEntity> renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(target);
        return renderer == null ? null : renderer.getTextureLocation(target);
    }

    /** 读皮肤 PNG；按位置缓存，读失败返回 null（这一格留空）。 */
    private static BufferedImage skinImage(ResourceLocation location) {
        BufferedImage cached = SKIN_CACHE.get(location);
        if (cached != null) {
            return cached;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getResourceManager() == null) {
            return null;
        }
        try (InputStream stream = minecraft.getResourceManager().getResourceOrThrow(location).open()) {
            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                return null;
            }
            SKIN_CACHE.put(location, image);
            return image;
        } catch (Exception failed) {
            return null;
        }
    }
}
