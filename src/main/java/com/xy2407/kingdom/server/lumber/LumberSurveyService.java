package com.xy2407.kingdom.server.lumber;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.xy2407.kingdom.common.area.PlotArea;
import com.xy2407.kingdom.common.area.PlotAreaKind;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.server.area.PlotAreaManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 伐木轮询：每 2400 tick 对每个伐木范围做一次轻量扫描——
 * 逐列找原木 → 竖直向上找到顶 → 顶部必须是自然生成树叶 → 相邻查询收整棵树，
 * 记录树基座坐标形成砍伐队列（NPC 自己去推进）。
 */
public final class LumberSurveyService {

    public static final LumberSurveyService INSTANCE = new LumberSurveyService();

    private static final long SURVEY_INTERVAL_TICKS = 2400L;

    private static final int MAX_TREE_BLOCKS = 512;

    private static final int BOTTOM_SCAN_DOWN = 4;

    private static final int TRUNK_SCAN_UP = 24;

    private long lastSurveyTick = Long.MIN_VALUE;

    private LumberSurveyService() {
    }

    public void reset() {
        lastSurveyTick = Long.MIN_VALUE;
    }

    public void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (lastSurveyTick != Long.MIN_VALUE && now - lastSurveyTick < SURVEY_INTERVAL_TICKS) {
            return;
        }
        lastSurveyTick = now;
        surveyAll(server);
    }

    private void surveyAll(MinecraftServer server) {
        for (PlotArea area : PlotAreaManager.INSTANCE.all(PlotAreaKind.LUMBER)) {
            ServerLevel level = levelOf(server, area.dimension());
            if (level == null) {
                continue;
            }
            BuildingRecord building = BuildingRegistry.INSTANCE.byId(area.buildingId());
            if (building == null) {
                continue;
            }
            List<BlockPos> trees = surveyPlot(level, area);
            distribute(level, building, trees);
        }
    }

    private void distribute(ServerLevel level, BuildingRecord building, List<BlockPos> trees) {
        List<NpcEntity> workers = NpcLumberService.workersFor(level, building, 4);
        for (NpcEntity npc : workers) {
            NpcLumberService.clearQueue(npc);
        }
        if (workers.isEmpty() || trees.isEmpty()) {
            return;
        }
        for (int index = 0; index < trees.size(); index++) {
            NpcLumberService.appendTree(workers.get(index % workers.size()), trees.get(index));
        }
        for (NpcEntity npc : workers) {
            NpcLumberService.markActive(npc, building.id());
        }
    }

    private List<BlockPos> surveyPlot(ServerLevel level, PlotArea area) {
        return scanCells(level, area.cells());
    }

    public static List<BlockPos> scanCells(ServerLevel level, List<Long> cells) {
        List<BlockPos> result = new ArrayList<>();
        if (cells == null || cells.isEmpty()) {
            return result;
        }
        Set<Long> visitedColumns = new HashSet<>();
        Set<Long> claimedBlocks = new HashSet<>();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 2;
        for (Long cell : cells) {
            if (cell == null) {
                continue;
            }
            BlockPos ground = BlockPos.of(cell);
            int x = ground.getX();
            int z = ground.getZ();
            if (!visitedColumns.add(BlockPos.asLong(x, 0, z))) {
                continue;
            }
            for (int y = minY; y <= maxY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!isLog(level.getBlockState(pos))) {
                    continue;
                }
                boolean bottom = true;
                for (int down = 1; down <= BOTTOM_SCAN_DOWN; down++) {
                    if (isLog(level.getBlockState(pos.below(down)))) {
                        bottom = false;
                        break;
                    }
                }
                if (!bottom) {
                    continue;
                }
                if (!isPlantableBase(level.getBlockState(pos.below()))) {
                    continue;
                }
                BlockPos top = climbTrunk(level, pos, maxY);
                if (!hasNaturalLeavesAbove(level, top, maxY)) {
                    continue;
                }
                List<BlockPos> blocks = collectTree(level, pos);
                if (blocks.isEmpty()) {
                    continue;
                }
                boolean overlap = false;
                for (BlockPos block : blocks) {
                    if (claimedBlocks.contains(block.asLong())) {
                        overlap = true;
                        break;
                    }
                }
                if (overlap) {
                    continue;
                }
                for (BlockPos block : blocks) {
                    claimedBlocks.add(block.asLong());
                }
                result.add(pos);
            }
        }
        return result;
    }

    private static BlockPos climbTrunk(ServerLevel level, BlockPos base, int maxY) {
        int limit = Math.min(maxY, base.getY() + TRUNK_SCAN_UP);
        int y = base.getY();
        while (y < limit && isLog(level.getBlockState(new BlockPos(base.getX(), y + 1, base.getZ())))) {
            y++;
        }
        return new BlockPos(base.getX(), y, base.getZ());
    }

    private static boolean isPlantableBase(BlockState state) {
        return state.is(BlockTags.DIRT);
    }

    private static boolean hasNaturalLeavesAbove(ServerLevel level, BlockPos top, int maxY) {
        int limit = Math.min(maxY, top.getY() + TRUNK_SCAN_UP);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = top.getY() + 1; y <= limit; y++) {
            cursor.set(top.getX(), y, top.getZ());
            BlockState state = level.getBlockState(cursor);
            if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT)) {
                return true;
            }
        }
        return false;
    }

    public static List<BlockPos> collectTree(ServerLevel level, BlockPos base) {
        List<BlockPos> collected = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        open.add(base);
        seen.add(base.asLong());
        while (!open.isEmpty() && collected.size() < MAX_TREE_BLOCKS) {
            BlockPos current = open.poll();
            BlockState state = level.getBlockState(current);
            boolean log = isLog(state);
            boolean leaves = state.getBlock() instanceof LeavesBlock;
            if (!log && !leaves) {
                continue;
            }
            collected.add(current);
            for (BlockPos neighbor : neighbors(current)) {
                if (seen.size() >= MAX_TREE_BLOCKS) {
                    break;
                }
                if (!seen.add(neighbor.asLong())) {
                    continue;
                }
                BlockState neighborState = level.getBlockState(neighbor);
                if (isLog(neighborState)) {
                    open.add(neighbor);
                } else if (neighborState.getBlock() instanceof LeavesBlock
                        && Math.abs(neighbor.getX() - base.getX()) <= 5
                        && Math.abs(neighbor.getZ() - base.getZ()) <= 5
                        && Math.abs(neighbor.getY() - base.getY()) <= 24) {
                    open.add(neighbor);
                }
            }
        }
        return collected;
    }

    public static List<BlockPos> neighbors(BlockPos pos) {
        List<BlockPos> list = new ArrayList<>(6);
        list.add(pos.above());
        list.add(pos.below());
        list.add(pos.north());
        list.add(pos.south());
        list.add(pos.east());
        list.add(pos.west());
        return list;
    }

    public static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

}