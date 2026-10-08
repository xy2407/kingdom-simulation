package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.render.Graph;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 像素缓冲：按设备像素落笔，取用时把相邻同色像素合并成矩形提交给 AUI。
 */
final class SpanSheet {

    record Span(int y0, int y1, int x0, int x1, int color) {
    }

    private final int width;
    private final int height;
    private final int[] pixels;
    private List<Span> spans;
    private boolean dirty = true;

    SpanSheet(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.pixels = new int[this.width * this.height];
    }

    void set(int x, int y, int argb) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        pixels[y * width + x] = argb;
        dirty = true;
    }

    void fill(int x0, int y0, int x1, int y1, int argb) {
        int left = Math.max(0, Math.min(x0, x1));
        int right = Math.min(width, Math.max(x0, x1));
        int top = Math.max(0, Math.min(y0, y1));
        int bottom = Math.min(height, Math.max(y0, y1));
        if (left >= right || top >= bottom) {
            return;
        }
        for (int y = top; y < bottom; y++) {
            int row = y * width;
            for (int x = left; x < right; x++) {
                pixels[row + x] = over(argb, pixels[row + x]);
            }
        }
        dirty = true;
    }

    List<Span> spans() {
        if (dirty || spans == null) {
            spans = merge();
            dirty = false;
        }
        return spans;
    }

    static void draw(PoseStack poseStack, Position origin, Size box, SpanSheet sheet) {
        if (sheet == null) {
            return;
        }
        double boxWidth = box.width();
        double boxHeight = box.height();
        if (boxWidth <= 0 || boxHeight <= 0) {
            return;
        }
        float scaleX = (float) (boxWidth / sheet.width);
        float scaleY = (float) (boxHeight / sheet.height);
        Matrix4f matrix = poseStack.last().pose();
        Graph.beginBatch();
        for (Span span : sheet.spans()) {
            Graph.drawFillRect(matrix,
                    (float) origin.x + span.x0() * scaleX,
                    (float) origin.y + span.y0() * scaleY,
                    (float) origin.x + span.x1() * scaleX,
                    (float) origin.y + span.y1() * scaleY,
                    span.color());
        }
        Graph.endBatch();
    }

    private List<Span> merge() {
        List<Span> merged = new ArrayList<>();
        List<Open> open = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            int row = y * width;
            List<Open> alive = new ArrayList<>();
            int runStart = 0;
            while (runStart < width) {
                int color = pixels[row + runStart];
                int runEnd = runStart + 1;
                while (runEnd < width && pixels[row + runEnd] == color) {
                    runEnd++;
                }
                if ((color >>> 24) != 0) {
                    Open matched = null;
                    for (Open candidate : open) {
                        if (candidate.color == color && candidate.x0 == runStart && candidate.x1 == runEnd) {
                            matched = candidate;
                            break;
                        }
                    }
                    if (matched == null) {
                        matched = new Open(runStart, runEnd, color, y);
                    }
                    alive.add(matched);
                }
                runStart = runEnd;
            }
            for (Open candidate : open) {
                if (!alive.contains(candidate)) {
                    merged.add(new Span(candidate.y0, y, candidate.x0, candidate.x1, candidate.color));
                }
            }
            open = alive;
        }
        for (Open candidate : open) {
            merged.add(new Span(candidate.y0, height, candidate.x0, candidate.x1, candidate.color));
        }
        return merged;
    }

    private static int over(int src, int dst) {
        int srcAlpha = src >>> 24;
        if (srcAlpha == 255) {
            return src;
        }
        if (srcAlpha == 0) {
            return dst;
        }
        int weight = (dst >>> 24) * (255 - srcAlpha) / 255;
        int outAlpha = srcAlpha + weight;
        if (outAlpha == 0) {
            return 0;
        }
        int red = (((src >> 16) & 0xFF) * srcAlpha + ((dst >> 16) & 0xFF) * weight) / outAlpha;
        int green = (((src >> 8) & 0xFF) * srcAlpha + ((dst >> 8) & 0xFF) * weight) / outAlpha;
        int blue = ((src & 0xFF) * srcAlpha + (dst & 0xFF) * weight) / outAlpha;
        return (outAlpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static final class Open {
        private final int x0;
        private final int x1;
        private final int color;
        private final int y0;

        private Open(int x0, int x1, int color, int y0) {
            this.x0 = x0;
            this.x1 = x1;
            this.color = color;
            this.y0 = y0;
        }
    }
}
