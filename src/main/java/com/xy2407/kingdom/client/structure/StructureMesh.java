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
 * 建筑烘焙后的静态几何：按空间段切分、每段按渲染层分组的一组顶点缓冲。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureMesh implements AutoCloseable {

    record Layer(int order, RenderType renderType, Supplier<ShaderInstance> shader, VertexBuffer buffer) {
    }

    record Section(AABB bounds, List<Layer> layers) {
    }

    record LayerOrder(int order, RenderType renderType, Supplier<ShaderInstance> shader) {
    }

    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();

    private final List<Section> sections;
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

    public void draw(Matrix4f view, Matrix4f projection) {
        draw(view, projection, 1.0F);
    }

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
