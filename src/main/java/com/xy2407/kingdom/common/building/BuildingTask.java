package com.xy2407.kingdom.common.building;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 建筑施工任务，按脚手架段与建筑段交替的工步序列推进
 */
public final class BuildingTask {
    public static final int PLACE_INTERVAL = 2;
    public static final int REPAIR_SCAN_INTERVAL = 80;
    public static final int SWING_INTERVAL = 20;

    public enum WorkKind { RING, PILLAR, BUILD }

    public record Step(BlockPos pos, BlockState state, WorkKind kind, int section, BlockPos stand) {
    }

    private final UUID id;
    private final String blueprintName;
    private final ResourceKey<Level> dimension;
    private final BlockPos origin;
    private final BuildPlan plan;
    private final List<Step> sequence;
    private final List<BlockPos> scaffold;
    private final int[] sectionStarts;
    private UUID architectId;
    private String architectName;
    private final Map<String, Integer> materials;
    private final BlockPos depositPos;
    private final int level;
    private final int rotation;
    private final int createdDay;
    private final ArrayDeque<Step> repairs = new ArrayDeque<>();
    private int cursor;
    private int tickCounter;
    private int swingTimer;
    private int repairScanTimer;

    public BuildingTask(UUID id, String blueprintName, ResourceKey<Level> dimension, BlockPos origin,
                        BuildPlan plan, List<Step> sequence, List<BlockPos> scaffold,
                        UUID architectId, String architectName, int level, int rotation, int createdDay,
                        Map<String, Integer> materials, BlockPos depositPos) {
        this.materials = materials == null ? new LinkedHashMap<>() : new LinkedHashMap<>(materials);
        this.depositPos = depositPos;
        this.id = id;
        this.blueprintName = blueprintName;
        this.dimension = dimension;
        this.origin = origin;
        this.plan = plan;
        this.sequence = sequence;
        this.scaffold = scaffold;
        this.architectId = architectId;
        this.architectName = architectName == null ? "" : architectName;
        this.level = level;
        this.rotation = rotation;
        this.createdDay = createdDay;
        int maxSection = 0;
        for (Step step : sequence) {
            maxSection = Math.max(maxSection, step.section());
        }
        sectionStarts = new int[maxSection + 1];
        int current = -1;
        for (int i = 0; i < sequence.size(); i++) {
            int section = sequence.get(i).section();
            if (section != current) {
                current = section;
                if (section < sectionStarts.length) {
                    sectionStarts[section] = i;
                }
            }
        }
    }

    public UUID id() {
        return id;
    }

    public String blueprintName() {
        return blueprintName;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos origin() {
        return origin;
    }

    public BuildPlan plan() {
        return plan;
    }

    public List<BlockPos> scaffold() {
        return scaffold;
    }

    public UUID architectId() {
        return architectId;
    }

    public String architectName() {
        return architectName;
    }

    public Map<String, Integer> materials() {
        return materials;
    }

    public void assignArchitect(UUID npcId, String name) {
        this.architectId = npcId;
        this.architectName = name == null ? "" : name;
    }

    public void setCursor(int value) {
        this.cursor = Math.max(0, Math.min(value, sequence.size()));
    }

    public void markConsumed(Step step) {
        if (step == null || step.kind() != WorkKind.BUILD) {
            return;
        }
        String id = itemIdOf(step.state());
        Integer left = materials.get(id);
        if (left == null || left <= 0) {
            return;
        }
        if (left <= 1) {
            materials.remove(id);
        } else {
            materials.put(id, left - 1);
        }
    }

    public BlockPos depositPos() {
        return depositPos;
    }

    public static String itemIdOf(BlockState state) {
        return BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString();
    }

    public int level() {
        return level;
    }

    public int rotation() {
        return rotation;
    }

    public int createdDay() {
        return createdDay;
    }

    public List<Step> sequence() {
        return sequence;
    }

    public int sectionStart(int section) {
        return section >= 0 && section < sectionStarts.length ? sectionStarts[section] : 0;
    }

    public Step next() {
        return sequence.get(cursor);
    }

    public int cursor() {
        return cursor;
    }

    public void advance() {
        cursor++;
    }

    public boolean isFinished() {
        return cursor >= sequence.size();
    }

    public boolean isPlacementDue() {
        tickCounter++;
        return tickCounter % PLACE_INTERVAL == 0;
    }

    public boolean isSwingDue() {
        if (++swingTimer < SWING_INTERVAL) {
            return false;
        }
        swingTimer = 0;
        return true;
    }

    public boolean hasRepairs() {
        return !repairs.isEmpty();
    }

    public void clearRepairs() {
        repairs.clear();
    }

    public Step peekRepair() {
        return repairs.peek();
    }

    public void popRepair() {
        repairs.poll();
    }

    public void enqueueRepair(Step step) {
        if (repairs.size() < 256) {
            repairs.add(step);
        }
    }

    public boolean isRepairScanDue() {
        if (!repairs.isEmpty()) {
            repairScanTimer = 0;
            return false;
        }
        return ++repairScanTimer >= REPAIR_SCAN_INTERVAL;
    }

    public void resetRepairScan() {
        repairScanTimer = 0;
    }
}
