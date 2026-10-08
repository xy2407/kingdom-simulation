package com.xy2407.kingdom.client.structure;

import com.xy2407.kingdom.common.structure.StructureModel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 建筑预览的轨道相机：绕原点旋转、平移与缩放，参数变化才重绘。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureCamera {

    private static final double FIELD_OF_VIEW_DEGREES = 35.0;
    private static final double MIN_PITCH = -89.0;
    private static final double MAX_PITCH = 89.0;
    private static final double MIN_ZOOM = 0.15;
    private static final double MAX_ZOOM = 8.0;
    private static final double DEFAULT_YAW = 35.0;
    private static final double DEFAULT_PITCH = 22.0;

    private double yaw = DEFAULT_YAW;
    private double pitch = DEFAULT_PITCH;
    private double zoom = 1.0;
    private double panX;
    private double panY;
    private double baseDistance = 32.0;
    private boolean dirty = true;

    public void frame(StructureModel model) {
        baseDistance = Math.max(8.0, model.maxDimension() * 1.9);
        dirty = true;
    }

    public void orbit(double deltaYawDegrees, double deltaPitchDegrees) {
        yaw += deltaYawDegrees;
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + deltaPitchDegrees));
        dirty = true;
    }

    public void zoomBy(double factor) {
        double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        if (next != zoom) {
            zoom = next;
            dirty = true;
        }
    }

    public void pan(double dx, double dy) {
        panX += dx;
        panY += dy;
        dirty = true;
    }

    public void reset() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1.0;
        panX = 0;
        panY = 0;
        dirty = true;
    }

    public void setFromAttributes(double yaw, double pitch, double zoom) {
        this.yaw = Double.isFinite(yaw) ? yaw : DEFAULT_YAW;
        this.pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, Double.isFinite(pitch) ? pitch : DEFAULT_PITCH));
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Double.isFinite(zoom) ? zoom : 1.0));
        dirty = true;
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    public double zoom() {
        return zoom;
    }

    public boolean consumeDirty() {
        boolean wasDirty = dirty;
        dirty = false;
        return wasDirty;
    }

    public void markDirty() {
        dirty = true;
    }

    public Matrix4f viewMatrix() {
        double distance = baseDistance / zoom;
        return new Matrix4f()
                .translate((float) panX, (float) panY, (float) -distance)
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw));
    }

    public Matrix4f projectionMatrix(float aspect) {
        return new Matrix4f().perspective(
                (float) Math.toRadians(FIELD_OF_VIEW_DEGREES),
                aspect <= 0.0f ? 1.0f : aspect,
                0.05f,
                20000.0f
        );
    }
}
