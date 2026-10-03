package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.element.Canvas;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Box;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.parser.Color;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.ImageDrawer;
import com.sighs.apricityui.render.Rect;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * 阵营盾牌：把 start.html 里原来用 canvas 逐像素画的盾徽，改成 Java2D 绘制。
 *
 * <p>算法与原页 {@code drawShield()} 一致：上半段是整宽矩形，下半段按 {@code w = W * (1 - t²)}
 * 收成圆角尖底；左右两半分别填 {@code c1}/{@code c2}，边缘 1 像素描深轮廓、再 1 像素描亮线，
 * 左上点高光、右下点阴影、正中一道竖缝。抖动来自 {@link #noise}（原页的 {@code nz()}）。</p>
 *
 * <p>用法：{@code <kingdom-shield width="40" height="48" c1="#7a1414" c2="#e8d8b0"></kingdom-shield>}。
 * 纹章符号不画进这张位图（原页要先把字形光栅化成蒙版再染色），而是由页面上的文字节点叠在盾面上，
 * 这样字体走 AUI 自己的文字管线，颜色也就是一个 CSS {@code color}。</p>
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(KingdomShieldElement.TAG_NAME)
public class KingdomShieldElement extends Canvas {

    public static final String TAG_NAME = "KINGDOM-SHIELD";
    public static final String ATTRIBUTE_COLOR_LEFT = "c1";
    public static final String ATTRIBUTE_COLOR_RIGHT = "c2";

    /** 原页里的盾面像素尺寸。 */
    private static final int ART_WIDTH = 40;
    private static final int ART_HEIGHT = 48;
    private static final int RASTER_SCALE = 3;
    /** 上半段整宽的行数。 */
    private static final int SOLID_ROWS = 26;

    private static final int OUTLINE = 0xFF2A1A08;
    private static final int OUTLINE_LIGHT = 0xFF8A6E3C;
    private static final int HIGHLIGHT = 0x47FFF0C8;
    private static final int SHADOW = 0x52281405;
    private static final int SEAM = 0x59281405;

    private String colorLeft = "#7a1414";
    private String colorRight = "#e8d8b0";
    private String paintedSignature;
    private double layoutWidth = -1;
    private double layoutHeight = -1;

    public KingdomShieldElement(Document document) {
        super(document);
        this.tagName = TAG_NAME;
    }

    @Override
    protected void onInitFromDom(Element origin) {
        super.onInitFromDom(origin);
        readColors();
    }

    @Override
    public void setAttribute(String name, String value) {
        super.setAttribute(name, value);
        if (isColorAttribute(name)) {
            readColors();
        }
    }

    @Override
    public void removeAttribute(String name) {
        super.removeAttribute(name);
        if (isColorAttribute(name)) {
            readColors();
        }
    }

    private static boolean isColorAttribute(String name) {
        return name != null && (ATTRIBUTE_COLOR_LEFT.equalsIgnoreCase(name)
                || ATTRIBUTE_COLOR_RIGHT.equalsIgnoreCase(name));
    }

    private void readColors() {
        String left = getAttribute(ATTRIBUTE_COLOR_LEFT);
        String right = getAttribute(ATTRIBUTE_COLOR_RIGHT);
        colorLeft = left == null || left.isBlank() ? "#7a1414" : left.trim();
        colorRight = right == null || right.isBlank() ? "#e8d8b0" : right.trim();
        paintedSignature = null;
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
        Rect rect = Rect.of(this);
        switch (phase) {
            case SHADOW -> rect.drawShadow(poseStack);
            case BODY -> {
                rect.drawBody(poseStack);
                renderShield();
                drawCanvas(poseStack, rect);
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    private void renderShield() {
        Size contentSize = Box.of(this).innerSize();
        if (contentSize.width() <= 0 || contentSize.height() <= 0) {
            return;
        }
        layoutWidth = contentSize.width();
        layoutHeight = contentSize.height();
        int wantedWidth = Math.max(1, (int) Math.ceil(contentSize.width() * RASTER_SCALE));
        int wantedHeight = Math.max(1, (int) Math.ceil(contentSize.height() * RASTER_SCALE));
        String signature = wantedWidth + "x" + wantedHeight + "|" + colorLeft + "|" + colorRight;
        if (signature.equals(paintedSignature)) {
            return;
        }
        paintedSignature = signature;
        if (wantedWidth != getWidth() || wantedHeight != getHeight()) {
            resizeSurface(wantedWidth, wantedHeight, false);
        }
        renderOperation(graphics -> paint(graphics, wantedWidth, wantedHeight));
    }

    @Override
    protected void drawCanvas(PoseStack poseStack, Rect rectRenderer) {
        syncTexture();
        if (textureLocation == null || layoutWidth <= 0 || layoutHeight <= 0) {
            return;
        }
        ImageDrawer.draw(poseStack, textureLocation,
                (float) rectRenderer.getContentPosition().x,
                (float) rectRenderer.getContentPosition().y,
                (float) layoutWidth, (float) layoutHeight, true);
    }

    private void paint(Graphics2D graphics, int surfaceWidth, int surfaceHeight) {
        int left = Color.parse(colorLeft);
        int right = Color.parse(colorRight);
        BufferedImage image = new BufferedImage(ART_WIDTH, ART_HEIGHT, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < ART_HEIGHT; y++) {
            double rowLeft;
            double rowRight;
            if (y < SOLID_ROWS) {
                rowLeft = 0;
                rowRight = ART_WIDTH - 1;
            } else {
                double t = (double) (y - SOLID_ROWS) / (ART_HEIGHT - SOLID_ROWS);
                double width = ART_WIDTH * (1 - t * t);
                rowLeft = (ART_WIDTH - width) / 2.0;
                rowRight = rowLeft + width - 1;
            }
            int from = (int) Math.floor(rowLeft);
            int to = (int) Math.ceil(rowRight);
            boolean topOrBottomRow = y == 0 || y == ART_HEIGHT - 1;

            for (int x = from; x <= to; x++) {
                if (x > rowRight || x < rowLeft) {
                    continue;
                }
                double edge = Math.min(x - rowLeft, rowRight - x);
                int argb;
                if (edge < 1 || topOrBottomRow) {
                    argb = OUTLINE;
                } else if (edge < 2) {
                    argb = OUTLINE_LIGHT;
                } else {
                    argb = x < ART_WIDTH / 2.0 ? left : right;
                }
                image.setRGB(x, y, argb);
            }
        }

        for (int y = 3; y < ART_HEIGHT - 3; y++) {
            for (int x = 2; x < ART_WIDTH / 2 - 2; x++) {
                int distance = (x - 3) + (y - 3);
                if (distance < 8 && noise(x, y, 3.0) > 0.5) {
                    blendPixel(image, x, y, HIGHLIGHT);
                }
            }
        }
        for (int y = 20; y < ART_HEIGHT - 3; y++) {
            for (int x = ART_WIDTH / 2 + 2; x < ART_WIDTH - 3; x++) {
                int distance = (ART_WIDTH - 3 - x) + (ART_HEIGHT - 3 - y);
                if (distance < 10 && noise(x, y, 7.0) > 0.5) {
                    blendPixel(image, x, y, SHADOW);
                }
            }
        }
        for (int y = 2; y < ART_HEIGHT - 2; y++) {
            blendPixel(image, ART_WIDTH / 2, y, SEAM);
        }

        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(image, 0, 0, surfaceWidth, surfaceHeight, null);
    }

    /** 把半透明的描边色叠到已有像素上（原页用的 {@code ctx.fillStyle = rgba(...)} + fillRect）。 */
    private static void blendPixel(BufferedImage image, int x, int y, int sourceArgb) {
        if (x < 0 || y < 0 || x >= ART_WIDTH || y >= ART_HEIGHT) {
            return;
        }
        int target = image.getRGB(x, y);
        double alpha = ((sourceArgb >>> 24) & 0xFF) / 255.0;
        if (alpha <= 0) {
            return;
        }
        int targetAlpha = (target >>> 24) & 0xFF;
        if (targetAlpha == 0) {
            return;
        }
        int red = blendChannel((target >>> 16) & 0xFF, (sourceArgb >>> 16) & 0xFF, alpha);
        int green = blendChannel((target >>> 8) & 0xFF, (sourceArgb >>> 8) & 0xFF, alpha);
        int blue = blendChannel(target & 0xFF, sourceArgb & 0xFF, alpha);
        image.setRGB(x, y, 0xFF000000 | (red << 16) | (green << 8) | blue);
    }

    private static int blendChannel(int base, int over, double alpha) {
        return (int) Math.round(base * (1 - alpha) + over * alpha);
    }

    /** 原页的 {@code nz()}：确定性伪随机，用来决定高光/阴影点位。 */
    private static double noise(double x, double y, double seed) {
        double value = Math.sin(x * 12.9898 + y * 78.233 + seed * 4.71) * 43758.5453;
        return value - Math.floor(value);
    }

    /** 便于调试：当前配色。 */
    public String describeColors() {
        return String.format(Locale.ROOT, "c1=%s c2=%s", colorLeft, colorRight);
    }
}
