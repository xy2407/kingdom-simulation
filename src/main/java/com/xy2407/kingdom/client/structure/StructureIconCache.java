package com.xy2407.kingdom.client.structure;

import com.xy2407.kingdom.common.structure.StructureLod;
import com.xy2407.kingdom.common.structure.StructureModel;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 建筑卡片缩略图缓存：每栋建筑只镶嵌一次（VBO），之后卡片每帧就是一次 draw call。
 * 不再逐方块 renderSingleBlock，也不走离屏纹理——离屏那条路在 AUI 的绘制阶段拿不稳。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureIconCache {

    /** 同时保留的网格数量，超出按最久未用淘汰 */
    private static final int MAX_ENTRIES = 64;

    /** 每帧最多镶嵌几个，避免一次性上传大量 VBO 卡顿 */
    private static final long BUILD_INTERVAL_NANOS = 8_000_000L;

    private static final class Entry {
        private StructureMesh mesh;
        private boolean failed;
        private boolean queued;
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(16, 0.75f, true);
    private static final Deque<String> PENDING = new ArrayDeque<>();

    private StructureIconCache() {
    }

    /**
     * 拿某栋建筑的网格；还没镶嵌好返回 null（调用方回退到逐方块渲染）。
     * 第一次调用会把 id 排进构建队列，由 {@link #tick()} 在渲染线程上镶嵌。
     */
    public static StructureMesh meshOf(String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return null;
        }
        Entry entry = ENTRIES.get(structureId);
        if (entry == null) {
            while (ENTRIES.size() >= MAX_ENTRIES) {
                if (!evictOldest()) {
                    break;
                }
            }
            entry = new Entry();
            ENTRIES.put(structureId, entry);
        }
        if (entry.mesh != null || entry.failed) {
            return entry.mesh;
        }
        if (!entry.queued) {
            entry.queued = true;
            PENDING.add(structureId);
        }
        return null;
    }

    public static boolean isReady(String structureId) {
        Entry entry = ENTRIES.get(structureId);
        return entry != null && entry.mesh != null;
    }

    /** 镶嵌节流：tick() 会被每个卡片元素各调一次，用时间窗限制每帧真正镶嵌的数量 */
    private static long lastBuildNanos;

    public static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastBuildNanos < BUILD_INTERVAL_NANOS) {
            return;
        }
        lastBuildNanos = now;
        String structureId = PENDING.poll();
        if (structureId != null) {
            build(structureId);
        }
    }

    public static void clear() {
        for (Entry entry : ENTRIES.values()) {
            if (entry.mesh != null) {
                entry.mesh.close();
            }
        }
        ENTRIES.clear();
        PENDING.clear();
    }

    private static boolean evictOldest() {
        if (ENTRIES.isEmpty()) {
            return false;
        }
        String oldest = ENTRIES.keySet().iterator().next();
        Entry entry = ENTRIES.remove(oldest);
        if (entry != null && entry.mesh != null) {
            entry.mesh.close();
        }
        return true;
    }

    private static int bakedCount;
    private static int failedCount;

    private static void build(String structureId) {
        Entry entry = ENTRIES.get(structureId);
        if (entry == null || entry.mesh != null || entry.failed) {
            return;
        }
        Path file = KingdomSimulationStructures.structuresDir().resolve(structureId + ".nbt");
        StructureModel model = StructureLoader.loadFile(file);
        if (model == null || model.blocks().isEmpty()) {
            entry.failed = true;
            reportFailure("模型为空", structureId);
            return;
        }
        StructureMesh mesh = StructureMesher.build(model, StructureLod.ROUGH);
        if (mesh == null || mesh.isEmpty()) {
            if (mesh != null) {
                mesh.close();
            }
            entry.failed = true;
            reportFailure("无可见几何", structureId);
            return;
        }
        entry.mesh = mesh;
        bakedCount++;
        if (bakedCount == 1) {
            StructureDebugChat.chat("卡片网格就绪（首例）：%s 段数=%d 体素来自镶嵌缓存",
                    structureId, mesh.sectionCount());
        }
    }

    /** 失败只报前几条，避免几百个坏文件把聊天栏刷爆 */
    private static void reportFailure(String reason, String structureId) {
        failedCount++;
        if (failedCount <= 3) {
            StructureDebugChat.chat("卡片网格跳过（%s）：%s", reason, structureId);
        }
    }
}
