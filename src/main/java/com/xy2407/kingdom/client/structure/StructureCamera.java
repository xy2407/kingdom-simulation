package com.xy2407.kingdom.client.structure;

import com.xy2407.kingdom.common.structure.StructureModel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 建筑预览的轨道相机。
 *
 * <p>顶点已经相对建筑中心存储，所以相机只需绕原点旋转并向后拉开距离；
 * 平移量作用在视图空间，视觉上就是拖着建筑走。</p>
 *
 * <p>任何参数变化都会置脏，离屏画面只在真正变化时重绘。</p>
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

    /** 按建筑尺寸设定取景距离。 */
    public void frame(StructureModel model) {
        baseDistance = Math.max(8.0, model.maxDimension() * 1.9);
        dirty = true;
    }

    public void orbit(double deltaYawDegrees, double deltaPitchDegrees) {
        yaw += deltaYawDegrees;
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + deltaPitchDegrees));
        dirty = true;
    }

    /** factor 大于 1 拉近（滚轮向上）。 */
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

    /** 供页面属性直接驱动视角。 */
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

    /** 取走脏标记；返回 true 表示这一帧需要重绘。 */
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