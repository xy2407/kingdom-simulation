package com.xy2407.kingdom.client.structure;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.xy2407.kingdom.common.structure.StructureLod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.List;
import java.util.function.Supplier;

/**
 * 一座建筑烘焙好之后的静态几何：按空间段切分、每段再按渲染层分组的一组顶点缓冲。
 *
 * <p>几何只在建筑加载或替换时构建一次，之后每帧只做可见性判断与绘制。</p>
 *
 * <p>绘制按"层序优先"而不是"段优先"：同一渲染层把所有可见段一次画完再做下一层。
 * 这样不透明几何会先全部落地，半透明层再统一混合，否则先画的半透明会被后画的
 * 不透明段盖掉。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class StructureMesh implements AutoCloseable {

    /** 一层：所属层序、渲染类型、着色器（用 Supplier 取，资源重载后能拿到新实例）、顶点缓冲。 */
    record Layer(int order, RenderType renderType, Supplier<ShaderInstance> shader, VertexBuffer buffer) {
    }

    /** 一个空间段：包围盒（已相对建筑中心）+ 该段内的各层。 */
    record Section(AABB bounds, List<Layer> layers) {
    }

    /** 一个层序的公共状态，所有段共用。 */
    record LayerOrder(int order, RenderType renderType, Supplier<ShaderInstance> shader) {
    }

    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();

    private final List<Section> sections;
    /** 已按层序升序，且只保留实际有内容的层。 */
    private final List<LayerOrder> orders;
    private final AABB bounds;
    private final StructureLod lod;

    StructureMesh(List<Section> sections, List<LayerOrder> orders, AABB bounds, StructureLod lod) {
        this.sections = List.copyOf(sections);
        this.orders = List.copyOf(orders);
        this.bounds = bounds;
        this.lod = lod;
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public AABB bounds() {
        return bounds;
    }

    public StructureLod lod() {
        return lod;
    }

    public int sectionCount() {
        return sections.size();
    }

    /**
     * 用给定的视图与投影矩阵绘制。
     *
     * <p>视图矩阵要经过 model-view 栈，因为绘制阶段下游的着色器 uniform 取自
     * {@link RenderSystem#getModelViewMatrix()}。视锥也从同一对矩阵构建，
     * 因此段包围盒与视锥处在同一坐标系，不需要再做相机偏移。</p>
     */
    public void draw(Matrix4f view, Matrix4f projection) {
        draw(view, projection, 1.0F);
    }

    /** alpha 小于 1 时开启混合并整体按该透明度着色，用于半透明投影 */
    public void draw(Matrix4f view, Matrix4f projection, float alpha) {
        if (sections.isEmpty()) {
            return;
        }
        FRUSTUM.set(new Matrix4f(projection).mul(view));

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(view);
        RenderSystem.applyModelViewMatrix();
        try {
            for (LayerOrder order : orders) {
                drawOrder(order, view, projection, alpha);
            }
        } finally {
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private void drawOrder(LayerOrder order, Matrix4f view, Matrix4f projection, float alpha) {
        ShaderInstance shader = order.shader().get();
        if (shader == null) {
            return;
        }
        RenderType renderType = order.renderType();
        renderType.setupRenderState();
        RenderSystem.setShader(() -> shader);
        boolean tinted = alpha < 1.0F;
        if (tinted) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        }
        // 方块渲染的着色器 uniform 不会有人替我们填，这里按 RenderType 的模式补齐
        shader.setDefaultUniforms(renderType.mode(), view, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        try {
            for (Section section : sections) {
                if (!isSectionVisible(section)) {
                    continue;
                }
                for (Layer layer : section.layers()) {
                    if (layer.order() != order.order()) {
                        continue;
                    }
                    layer.buffer().bind();
                    layer.buffer().draw();
                }
            }
        } finally {
            VertexBuffer.unbind();
            shader.clear();
            if (tinted) {
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                RenderSystem.disableBlend();
            }
            renderType.clearRenderState();
        }
    }

    private static boolean isSectionVisible(Section section) {
        AABB box = section.bounds();
        return FRUSTUM.testAab(
                (float) box.minX, (float) box.minY, (float) box.minZ,
                (float) box.maxX, (float) box.maxY, (float) box.maxZ
        );
    }

    @Override
    public void close() {
        for (Section section : sections) {
            for (Layer layer : section.layers()) {
                layer.buffer().close();
            }
        }
    }
}