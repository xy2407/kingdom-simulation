package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.element.Canvas;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.Box;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.DevicePixelGrid;
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
    /**
     * 表面分辨率倍数：按 GUI 缩放取，够 1:1 清晰即可。
     * 原来固定 8 倍，48px 的头像要一张 384×384 的 BufferedImage + 同样大的 GPU 纹理，
     * 长列表里 200 个头像就是 100MB 级别的显存/堆开销。
     */
    private static final int MAX_RASTER_SCALE = 6;
    private static final int MAX_SURFACE = 512;

    private static final Map<ResourceLocation, BufferedImage> SKIN_CACHE = new ConcurrentHashMap<>();

    private double layoutWidth = -1;
    private double layoutHeight = -1;
    private String paintedSignature;
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
        if (!faceReady) {
            return;
        }
        // 绘制永远以「这一帧的真实内容盒」为准：缓存值只作为布局未就绪时的兜底，
        // 否则元素一旦被复用/改尺寸，就可能按上一帧的大尺寸画出去、超出卡片被裁
        Size live = Box.of(this).innerSize();
        double width = live.width() > 0 ? live.width() : layoutWidth;
        double height = live.height() > 0 ? live.height() : layoutHeight;
        if (width <= 0 || height <= 0) {
            return;
        }
        layoutWidth = width;
        layoutHeight = height;
        syncTexture();
        if (textureLocation == null) {
            return;
        }
        double device = getDeviceScale();
        Position contentPos = rectRenderer.getContentPosition();
        ImageDrawer.draw(poseStack, textureLocation,
                DevicePixelGrid.snap(contentPos.x, device),
                DevicePixelGrid.snap(contentPos.y, device),
                (float) width, (float) height, false);
    }

    private static int rasterScale() {
        Minecraft minecraft = Minecraft.getInstance();
        // 1.21.1 的 Window#getGuiScale() 返回 double，这里要整数倍数
        int scale = minecraft.getWindow() == null ? 2 : (int) minecraft.getWindow().getGuiScale();
        return Math.max(2, Math.min(scale, MAX_RASTER_SCALE));
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
            paintedSignature = null;
            return;
        }

        layoutWidth = contentSize.width();
        layoutHeight = contentSize.height();
        int scale = rasterScale();
        double longSide = Math.max(contentSize.width(), contentSize.height()) * scale;
        if (longSide > MAX_SURFACE) {
            scale = Math.max(1, (int) (MAX_SURFACE / Math.max(contentSize.width(), contentSize.height())));
        }
        int wantedWidth = Math.max(1, (int) Math.ceil(contentSize.width() * scale));
        int wantedHeight = Math.max(1, (int) Math.ceil(contentSize.height() * scale));
        resizeSurface(wantedWidth, wantedHeight, false);
        // 真光栅尺寸与皮肤一起进签名：换皮肤、改尺寸、改设备缩放都会重画。
        String signature = wantedWidth + "x" + wantedHeight
                + "@" + getBitmapWidth() + "x" + getBitmapHeight()
                + "|" + skin;
        if (signature.equals(paintedSignature)) {
            faceReady = true;
            return;
        }
        paintedSignature = signature;
        renderOperation(graphics -> {
            double device = getDeviceScale();
            if (device > 0.0D && Double.isFinite(device)) {
                graphics.scale(device, device);
            }
            paintFace(graphics, source, wantedWidth, wantedHeight);
        });
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
