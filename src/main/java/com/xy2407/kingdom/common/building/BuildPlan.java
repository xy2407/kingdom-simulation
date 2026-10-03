package com.xy2407.kingdom.common.building;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.common.structure.StructureModel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * 建筑施工计划，方块按自下而上逐层逐列排序
 */
public final class BuildPlan {
    private final Vec3i size;
    private final List<List<StructureModel.BlockEntry>> layers;

    private BuildPlan(Vec3i size, List<List<StructureModel.BlockEntry>> layers) {
        this.size = size;
        this.layers = layers;
    }

    public static BuildPlan of(StructureModel model) {
        Vec3i size = model.size();
        List<List<StructureModel.BlockEntry>> layers = new ArrayList<>(Math.max(1, size.getY()));
        for (int y = 0; y < size.getY(); y++) {
            layers.add(new ArrayList<>());
        }
        for (StructureModel.BlockEntry entry : model.blocks()) {
            if (entry.state() == null || entry.state().isAir()) {
                continue;
            }
            int y = entry.pos().getY();
            if (y >= 0 && y < layers.size()) {
                layers.get(y).add(entry);
            }
        }
        for (List<StructureModel.BlockEntry> layer : layers) {
            layer.sort((a, b) -> {
                BlockPos pa = a.pos();
                BlockPos pb = b.pos();
                if (pa.getX() != pb.getX()) {
                    return Integer.compare(pa.getX(), pb.getX());
                }
                return Integer.compare(pa.getZ(), pb.getZ());
            });
        }
        return new BuildPlan(size, layers);
    }

    public Vec3i size() {
        return size;
    }

    public int layerCount() {
        return layers.size();
    }

    public List<StructureModel.BlockEntry> layer(int y) {
        return layers.get(y);
    }

    public int blockCount() {
        int total = 0;
        for (List<StructureModel.BlockEntry> layer : layers) {
            total += layer.size();
        }
        return total;
    }
}
