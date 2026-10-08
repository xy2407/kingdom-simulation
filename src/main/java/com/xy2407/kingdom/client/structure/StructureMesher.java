package com.xy2407.kingdom.client.structure;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xy2407.kingdom.common.structure.StructureLod;
import com.xy2407.kingdom.common.structure.StructureModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 把建筑模型烘焙成静态几何：按 LOD 降采样、分段镶嵌并上传顶点缓冲。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureMesher {

    private static final int SECTION_UNITS = 16;
    private static final int LAYER_ORDER_COUNT = 5;
    private static final int INITIAL_LAYER_BYTES = 256 * 1024;
    private static final int EMIT_OK = 0;
    private static final int EMIT_INVISIBLE = 1;
    private static final int EMIT_CUSTOM = 2;
    private static final int EMIT_NO_RENDER_TYPE = 3;

    private StructureMesher() {
    }

    private record Unit(BlockPos viewPos, BlockState state) {
    }

    private record Stage(int unitSize, Map<Long, BlockState> states, List<Unit> units) {
    }

    private record SectionKey(int x, int y, int z) {
    }

    private record MeshContext(ModelBlockRenderer modelRenderer, StructureBlockView view,
                               float centerX, float centerY, float centerZ, int unitSize) {
    }

    public static StructureMesh build(StructureModel model) {
        return build(model, null);
    }

    public static StructureMesh build(StructureModel model, StructureLod forcedLod) {
        Minecraft minecraft = Minecraft.getInstance();
        StructureLod lod = forcedLod != null ? forcedLod : StructureLod.forVolume(model.blocks().size());
        Stage stage = lod == StructureLod.FINE
                ? fineStage(model)
                : coarseStage(model, lod.mergeFactor());

        Vec3i size = model.size();
        float centerX = size.getX() / 2.0f;
        float centerY = size.getY() / 2.0f;
        float centerZ = size.getZ() / 2.0f;

        int unitSize = stage.unitSize();
        int unitsPerSection = Math.max(1, SECTION_UNITS / unitSize);

        Map<SectionKey, List<Unit>> bySection = new LinkedHashMap<>();
        for (Unit unit : stage.units()) {
            BlockPos pos = unit.viewPos();
            SectionKey key = new SectionKey(
                    Math.floorDiv(pos.getX(), unitsPerSection),
                    Math.floorDiv(pos.getY(), unitsPerSection),
                    Math.floorDiv(pos.getZ(), unitsPerSection)
            );
            bySection.computeIfAbsent(key, ignored -> new ArrayList<>()).add(unit);
        }

        MeshContext context = new MeshContext(
                minecraft.getBlockRenderer().getModelRenderer(),
                new StructureBlockView(stage.states()),
                centerX, centerY, centerZ, unitSize
        );

        List<SectionKey> sectionKeys = new ArrayList<>(bySection.keySet());
        sectionKeys.sort(Comparator.comparingInt(SectionKey::x)
                .thenComparingInt(SectionKey::y)
                .thenComparingInt(SectionKey::z));

        StructureMesh.LayerOrder[] orders = new StructureMesh.LayerOrder[LAYER_ORDER_COUNT];
        List<StructureMesh.Section> sections = new ArrayList<>(sectionKeys.size());
        int skippedInvisible = 0;
        int skippedCustomRenderer = 0;
        int skippedNoRenderType = 0;
        int emptyLayerSections = 0;

        for (SectionKey key : sectionKeys) {
            Map<RenderType, LayerAccumulator> accumulators = new LinkedHashMap<>();
            try {
                for (Unit unit : bySection.get(key)) {
                    switch (emitUnit(context, unit, accumulators)) {
                        case EMIT_INVISIBLE -> skippedInvisible++;
                        case EMIT_CUSTOM -> skippedCustomRenderer++;
                        case EMIT_NO_RENDER_TYPE -> skippedNoRenderType++;
                        default -> {
                        }
                    }
                }

                List<StructureMesh.Layer> layers = new ArrayList<>(accumulators.size());
                for (LayerAccumulator accumulator : accumulators.values()) {
                    StructureMesh.Layer layer = accumulator.upload();
                    if (layer == null) {
                        continue;
                    }
                    layers.add(layer);
                    int order = accumulator.order();
                    if (orders[order] == null) {
                        orders[order] = new StructureMesh.LayerOrder(
                                order, accumulator.renderType(), accumulator.shader());
                    }
                }
                if (layers.isEmpty()) {
                    emptyLayerSections++;
                    continue;
                }
                sections.add(new StructureMesh.Section(
                        sectionBounds(key, unitsPerSection, unitSize, centerX, centerY, centerZ),
                        layers
                ));
            } finally {
                for (LayerAccumulator accumulator : accumulators.values()) {
                    accumulator.close();
                }
            }
        }

        List<StructureMesh.LayerOrder> usedOrders = new ArrayList<>(LAYER_ORDER_COUNT);
        for (StructureMesh.LayerOrder order : orders) {
            if (order != null) {
                usedOrders.add(order);
            }
        }

        AABB bounds = meshBounds(size, unitSize, centerX, centerY, centerZ);
        StructureDebugChat.chat(
                "镶嵌结果：档位=%s 合并=%d 包围盒=%dx%dx%d 单元=%d 段=%d 层序=%d 空段=%d 跳过(不可见=%d/方块实体=%d/无渲染层=%d)",
                lod, unitSize, size.getX(), size.getY(), size.getZ(), stage.units().size(),
                sections.size(), usedOrders.size(), emptyLayerSections,
                skippedInvisible, skippedCustomRenderer, skippedNoRenderType);
        if (sections.isEmpty()) {
            StructureDebugChat.chat("镶嵌结果为空：没有任何可上传的几何段，预览必然空白");
        }
        return new StructureMesh(sections, usedOrders, bounds, lod);
    }

    public record DisplayVoxel(BlockPos pos, BlockState state, int unitSize) {
    }

    public static List<DisplayVoxel> displayVoxels(StructureModel model, StructureLod lod) {
        Stage stage = lod == StructureLod.FINE ? fineStage(model) : coarseStage(model, lod.mergeFactor());
        int unitSize = stage.unitSize();
        List<DisplayVoxel> voxels = new ArrayList<>(stage.units().size());
        for (Unit unit : stage.units()) {
            BlockPos viewPos = unit.viewPos();
            voxels.add(new DisplayVoxel(
                    new BlockPos(viewPos.getX() * unitSize, viewPos.getY() * unitSize, viewPos.getZ() * unitSize),
                    unit.state(),
                    unitSize
            ));
        }
        return voxels;
    }

    private static Stage fineStage(StructureModel model) {
        Map<Long, BlockState> states = new HashMap<>(model.blocks().size() * 2);
        List<Unit> units = new ArrayList<>(model.blocks().size());
        for (StructureModel.BlockEntry entry : model.blocks()) {
            states.put(entry.pos().asLong(), entry.state());
            units.add(new Unit(entry.pos(), entry.state()));
        }
        return new Stage(1, states, units);
    }

    private static Stage coarseStage(StructureModel model, int factor) {
        Map<Long, Map<BlockState, Integer>> counters = new HashMap<>();
        for (StructureModel.BlockEntry entry : model.blocks()) {
            BlockPos pos = entry.pos();
            long cellKey = BlockPos.asLong(
                    Math.floorDiv(pos.getX(), factor),
                    Math.floorDiv(pos.getY(), factor),
                    Math.floorDiv(pos.getZ(), factor)
            );
            counters.computeIfAbsent(cellKey, ignored -> new LinkedHashMap<>())
                    .merge(entry.state(), 1, Integer::sum);
        }

        Map<Long, BlockState> states = new HashMap<>(counters.size() * 2);
        List<Unit> units = new ArrayList<>(counters.size());
        for (Map.Entry<Long, Map<BlockState, Integer>> cell : counters.entrySet()) {
            BlockState dominant = null;
            int bestCount = -1;
            for (Map.Entry<BlockState, Integer> candidate : cell.getValue().entrySet()) {
                if (candidate.getValue() > bestCount) {
                    bestCount = candidate.getValue();
                    dominant = candidate.getKey();
                }
            }
            if (dominant == null) {
                continue;
            }
            BlockPos viewPos = BlockPos.of(cell.getKey());
            states.put(cell.getKey(), dominant);
            units.add(new Unit(viewPos, dominant));
        }
        return new Stage(factor, states, units);
    }

    private static int emitUnit(MeshContext context, Unit unit,
                                Map<RenderType, LayerAccumulator> accumulators) {
        BlockState state = unit.state();
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return EMIT_INVISIBLE;
        }
        BakedModel bakedModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        if (bakedModel.isCustomRenderer()) {
            return EMIT_CUSTOM;
        }

        int unitSize = context.unitSize();
        PoseStack poseStack = new PoseStack();
        poseStack.translate(
                unit.viewPos().getX() * (float) unitSize - context.centerX(),
                unit.viewPos().getY() * (float) unitSize - context.centerY(),
                unit.viewPos().getZ() * (float) unitSize - context.centerZ()
        );
        if (unitSize != 1) {
            poseStack.scale(unitSize, unitSize, unitSize);
        }

        RandomSource random = RandomSource.create();
        long seed = state.getSeed(unit.viewPos());
        int accepted = 0;
        for (RenderType renderType : bakedModel.getRenderTypes(state, random, ModelData.EMPTY)) {
            int order = layerOrder(renderType);
            Supplier<ShaderInstance> shader = shaderForOrder(order);
            if (shader == null) {
                continue;
            }
            LayerAccumulator accumulator = accumulators.computeIfAbsent(
                    renderType, type -> new LayerAccumulator(order, type, shader));
            context.modelRenderer().tesselateBlock(
                    context.view(),
                    bakedModel,
                    state,
                    unit.viewPos(),
                    poseStack,
                    accumulator.consumer(),
                    true,
                    random,
                    seed,
                    OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY,
                    renderType
            );
            accepted++;
        }
        return accepted == 0 ? EMIT_NO_RENDER_TYPE : EMIT_OK;
    }

    private static AABB sectionBounds(SectionKey key, int unitsPerSection, int unitSize,
                                      float centerX, float centerY, float centerZ) {
        double extent = (double) unitsPerSection * unitSize;
        double minX = key.x() * extent - centerX;
        double minY = key.y() * extent - centerY;
        double minZ = key.z() * extent - centerZ;
        return new AABB(minX, minY, minZ, minX + extent, minY + extent, minZ + extent);
    }

    private static AABB meshBounds(Vec3i size, int unitSize,
                                   float centerX, float centerY, float centerZ) {
        double extentX = Math.ceil(size.getX() / (double) unitSize) * unitSize;
        double extentY = Math.ceil(size.getY() / (double) unitSize) * unitSize;
        double extentZ = Math.ceil(size.getZ() / (double) unitSize) * unitSize;
        return new AABB(-centerX, -centerY, -centerZ,
                extentX - centerX, extentY - centerY, extentZ - centerZ);
    }

    private static int layerOrder(RenderType renderType) {
        if (renderType == RenderType.solid()) {
            return 0;
        }
        if (renderType == RenderType.cutoutMipped()) {
            return 1;
        }
        if (renderType == RenderType.cutout()) {
            return 2;
        }
        if (renderType == RenderType.tripwire()) {
            return 3;
        }
        if (renderType == RenderType.translucent()) {
            return 4;
        }
        return -1;
    }

    private static Supplier<ShaderInstance> shaderForOrder(int order) {
        return switch (order) {
            case 0 -> GameRenderer::getRendertypeSolidShader;
            case 1 -> GameRenderer::getRendertypeCutoutMippedShader;
            case 2 -> GameRenderer::getRendertypeCutoutShader;
            case 3 -> GameRenderer::getRendertypeTripwireShader;
            case 4 -> GameRenderer::getRendertypeTranslucentShader;
            default -> null;
        };
    }

    private static final class LayerAccumulator implements AutoCloseable {
        private final int order;
        private final RenderType renderType;
        private final Supplier<ShaderInstance> shader;
        private final ByteBufferBuilder byteBuffer = new ByteBufferBuilder(INITIAL_LAYER_BYTES);
        private final BufferBuilder buffer =
                new BufferBuilder(byteBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

        private LayerAccumulator(int order, RenderType renderType, Supplier<ShaderInstance> shader) {
            this.order = order;
            this.renderType = renderType;
            this.shader = shader;
        }

        private int order() {
            return order;
        }

        private RenderType renderType() {
            return renderType;
        }

        private Supplier<ShaderInstance> shader() {
            return shader;
        }

        private VertexConsumer consumer() {
            return buffer;
        }

        private StructureMesh.Layer upload() {
            MeshData mesh = buffer.build();
            if (mesh == null) {
                return null;
            }
            try {
                VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                vertexBuffer.bind();
                vertexBuffer.upload(mesh);
                VertexBuffer.unbind();
                return new StructureMesh.Layer(order, renderType, shader, vertexBuffer);
            } finally {
                mesh.close();
            }
        }

        @Override
        public void close() {
            byteBuffer.close();
        }
    }

    private static final class StructureBlockView implements BlockAndTintGetter {
        private final Map<Long, BlockState> states;
        private final BlockAndTintGetter delegate;

        private StructureBlockView(Map<Long, BlockState> states) {
            this.states = states;
            this.delegate = Minecraft.getInstance().level;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return delegate == null ? 384 : delegate.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return delegate == null ? -64 : delegate.getMinBuildHeight();
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            if (!shade) {
                return 1.0f;
            }
            return switch (direction) {
                case DOWN -> 0.5f;
                case UP -> 1.0f;
                case NORTH, SOUTH -> 0.8f;
                case EAST, WEST -> 0.6f;
            };
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return delegate == null ? null : delegate.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return delegate == null ? 0xFFFFFF : delegate.getBlockTint(pos, colorResolver);
        }

        @Override
        public int getBrightness(LightLayer lightLayer, BlockPos pos) {
            return 15;
        }
    }
}
