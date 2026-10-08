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
 * 身份卡片左上角的头像：按原版皮肤布局取 8×8 脸与第二层，最近邻放大铺满头像框。
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(KingdomEntityAvatarElement.TAG_NAME)
public class KingdomEntityAvatarElement extends Canvas {

    public static final String TAG_NAME = "KINGDOM-ENTITY-AVATAR";

    private static final int FACE_U = 8;
    private static final int FACE_V = 8;
    private static final int OVERLAY_U = 40;
    private static final int OVERLAY_V = 8;
    private static final int FACE_SIZE = 8;
    private static final int RASTER_SCALE = 8;

    private static final Map<ResourceLocation, BufferedImage> SKIN_CACHE = new ConcurrentHashMap<>();

    private double layoutWidth = -1;
    private double layoutHeight = -1;
    private ResourceLocation renderedSkin;
    private boolean faceReady;
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

    private static ResourceLocation skinOf(NpcEntity target) {
        EntityRenderer<? super NpcEntity> renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(target);
        return renderer == null ? null : renderer.getTextureLocation(target);
    }

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
