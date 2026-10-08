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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * 火漆印章元素：按原页算法用 Java2D 绘制蜡封，超采样后铺到元素表面。
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(KingdomSealElement.TAG_NAME)
public class KingdomSealElement extends Canvas {

    public static final String TAG_NAME = "KINGDOM-SEAL";

    private static final int ART_SIZE = 52;
    private static final int RASTER_SCALE = 4;

    private static final int[] C_OUTER = {0x4A, 0x0A, 0x0A};
    private static final int[] C_MID = {0x7A, 0x14, 0x14};
    private static final int[] C_MAIN = {0xA8, 0x23, 0x1C};
    private static final int[] C_HI = {0xC4, 0x33, 0x2A};
    private static final int[] C_HI2 = {0xD8, 0x45, 0x3A};
    private static final int[] C_INNER = {0xFF, 0xD0, 0xB0};
    private static final int[] C_FLARE = {0xE8, 0x58, 0x48};
    private static final int[] C_CROSS = {255, 215, 180};

    private double layoutWidth = -1;
    private double layoutHeight = -1;
    private int surfaceWidth = -1;
    private int surfaceHeight = -1;

    public KingdomSealElement(Document document) {
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
                renderSeal();
                drawCanvas(poseStack, rect);
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    private void renderSeal() {
        Size contentSize = Box.of(this).innerSize();
        if (contentSize.width() <= 0 || contentSize.height() <= 0) {
            return;
        }
        layoutWidth = contentSize.width();
        layoutHeight = contentSize.height();
        int wantedWidth = Math.max(1, (int) Math.ceil(contentSize.width() * RASTER_SCALE));
        int wantedHeight = Math.max(1, (int) Math.ceil(contentSize.height() * RASTER_SCALE));
        if (wantedWidth == getWidth() && wantedHeight == getHeight()
                && surfaceWidth == wantedWidth && surfaceHeight == wantedHeight) {
            return;
        }
        resizeSurface(wantedWidth, wantedHeight, false);
        surfaceWidth = wantedWidth;
        surfaceHeight = wantedHeight;

        renderOperation(graphics -> paint(graphics, wantedWidth, wantedHeight));
    }

    @Override
    protected void drawCanvas(PoseStack poseStack, Rect rectRenderer) {
        syncTexture();
        if (textureLocation == null) {
            return;
        }
        if (layoutWidth <= 0 || layoutHeight <= 0) {
            return;
        }
        ImageDrawer.draw(poseStack, textureLocation,
                (float) rectRenderer.getContentPosition().x,
                (float) rectRenderer.getContentPosition().y,
                (float) layoutWidth, (float) layoutHeight, true);
    }

    private static void paint(Graphics2D graphics, int surfaceWidth, int surfaceHeight) {
        BufferedImage image = new BufferedImage(ART_SIZE, ART_SIZE, BufferedImage.TYPE_INT_ARGB);
        double center = (ART_SIZE - 1) / 2.0;
        double outerRadius = ART_SIZE / 2.0 - 1.0;
        int centerX = (int) center;
        int centerY = (int) center;

        for (int y = 0; y < ART_SIZE; y++) {
            for (int x = 0; x < ART_SIZE; x++) {
                double dx = x - center;
                double dy = y - center;
                double radius = Math.sqrt(dx * dx + dy * dy);
                int[] rgb = null;
                double noise = (noise(x, y, 5.0) - 0.5) * 1.4;
                if (radius <= outerRadius + noise) {
                    if (radius > outerRadius - 1.5) {
                        rgb = C_OUTER;
                    } else if (radius > outerRadius - 3.0) {
                        rgb = C_MID;
                    } else if (radius > outerRadius - 5.0) {
                        rgb = C_MAIN;
                    } else if (radius > outerRadius - 7.0) {
                        rgb = C_HI;
                    } else {
                        rgb = C_HI2;
                    }
                    if (dx < -3 && dy < -3 && radius > 4 && radius < outerRadius - 3
                            && (dx + dy) < -8 && noise(x, y, 7.0) > 0.4) {
                        rgb = C_FLARE;
                    }
                    double innerRadius = outerRadius - 9;
                    if (Math.abs(radius - innerRadius) < 0.7 && noise(x, y, 9.0) > 0.5) {
                        rgb = C_INNER;
                    }
                }
                if (rgb != null) {
                    boolean onCross = (x == centerX && y >= centerY - 3 && y <= centerY + 3)
                            || (y == centerY && x >= centerX - 3 && x <= centerX + 3);
                    if (onCross) {
                        rgb = blend(rgb, C_CROSS, 140 / 255.0);
                    }
                    if (x == centerX && y == centerY) {
                        rgb = blend(rgb, C_CROSS, 217 / 255.0);
                    }
                    image.setRGB(x, y, 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
                }
            }
        }
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(image, 0, 0, surfaceWidth, surfaceHeight, null);
    }

    private static int[] blend(int[] base, int[] over, double alpha) {
        return new int[]{
                (int) Math.round(base[0] * (1 - alpha) + over[0] * alpha),
                (int) Math.round(base[1] * (1 - alpha) + over[1] * alpha),
                (int) Math.round(base[2] * (1 - alpha) + over[2] * alpha)
        };
    }

    private static double noise(double x, double y, double seed) {
        double value = Math.sin(x * 12.9898 + y * 78.233 + seed * 4.71) * 43758.5453;
        return value - Math.floor(value);
    }
}
