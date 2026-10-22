package com.xy2407.kingdom.server.mining;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.npc.NpcBrain;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcDetail;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcMiningService;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 矿坑扫描调度：工作时段轮询各坑当前层，清空则封边下沉，未清空把待挖格推进到采矿 NPC。
 */
public final class MiningScanService {

    private static final int SCAN_INTERVAL_TICKS = 10;

    private static final long DAY_LENGTH = 24000L;

    private static final long WORK_END = 13000L;

    private static final long NIGHT_START = 23001L;

    private static final List<UUID> SCAN_ORDER = new ArrayList<>();

    private static int scanCursor;

    private static int tickCounter;

    private MiningScanService() {
    }

    public static void reset() {
        SCAN_ORDER.clear();
        scanCursor = 0;
        tickCounter = 0;
    }

    public static void register(UUID plotId) {
        if (plotId == null || SCAN_ORDER.contains(plotId)) {
            return;
        }
        int pos = SCAN_ORDER.isEmpty() ? 0 : ThreadLocalRandom.current().nextInt(SCAN_ORDER.size() + 1);
        SCAN_ORDER.add(pos, plotId);
    }

    public static void unregister(UUID plotId) {
        if (plotId == null) {
            return;
        }
        int index = SCAN_ORDER.indexOf(plotId);
        if (index >= 0) {
            SCAN_ORDER.remove(index);
            if (index < scanCursor) {
                scanCursor--;
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (SCAN_ORDER.isEmpty()) {
            rebuild();
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null || !inWorkWindow(overworld.getDayTime())) {
            return;
        }
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        if (scanCursor >= SCAN_ORDER.size()) {
            scanCursor = 0;
        }
        UUID plotId = SCAN_ORDER.get(scanCursor);
        MiningPlot plot = MiningPlotManager.INSTANCE.byId(plotId);
        if (plot == null || MiningPlot.STATUS_DONE.equals(plot.status()) || !plot.hasArea()) {
            SCAN_ORDER.remove(scanCursor);
            return;
        }
        scanCursor = (scanCursor + 1) % SCAN_ORDER.size();
        if (!MiningPlot.STATUS_RUNNING.equals(plot.status())) {
            return;
        }
        ServerLevel level = levelOf(server, plot.dimension());
        if (level == null || !hasActiveMiner(level, plot)) {
            return;
        }
        scanPlot(server, level, plot);
    }

    private static boolean hasActiveMiner(ServerLevel level, MiningPlot plot) {
        for (UUID workerId : plot.workerIds()) {
            NpcData data = NpcManager.INSTANCE.getNpc(workerId);
            if (data == null || data.isDead()) {
                continue;
            }
            NpcBrain brain = data.getBrain();
            if (!brain.isMiningControlled() || brain.getDetail() != NpcDetail.MINING) {
                continue;
            }
            UUID entityId = data.getEntityUuid();
            if (entityId == null) {
                continue;
            }
            Entity entity = level.getEntity(entityId);
            if (entity instanceof NpcEntity npc && npc.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static void rebuild() {
        for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
            if (MiningPlot.STATUS_RUNNING.equals(plot.status()) && plot.hasArea()) {
                SCAN_ORDER.add(plot.id());
            }
        }
        if (SCAN_ORDER.size() > 1) {
            Collections.shuffle(SCAN_ORDER);
        }
        scanCursor = 0;
    }

    private static boolean inWorkWindow(long dayTime) {
        long tick = Math.floorMod(dayTime, DAY_LENGTH);
        return (tick >= 0L && tick <= WORK_END) || (tick >= NIGHT_START && tick < DAY_LENGTH);
    }

    private static void scanPlot(MinecraftServer server, ServerLevel level, MiningPlot plot) {
        while (true) {
            MiningPlot live = MiningPlotManager.INSTANCE.byId(plot.id());
            if (live == null || !MiningPlot.STATUS_RUNNING.equals(live.status())) {
                return;
            }
            plot = live;
            NpcMiningService.sealLayer(level, plot);
            List<Integer> pending = new ArrayList<>();
            int unloaded = 0;
            int total = plot.layerSize();
            for (int index = 0; index < total; index++) {
                BlockPos pos = plot.posOf(index);
                if (!level.isLoaded(pos)) {
                    unloaded++;
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(Blocks.LADDER)) {
                    continue;
                }
                if (state.getBlock() instanceof LiquidBlock && !state.getFluidState().isSource()) {
                    continue;
                }
                pending.add(index);
            }
            if (!pending.isEmpty()) {
                pushQueue(level, plot, pending);
                return;
            }
            if (unloaded > 0) {
                return;
            }
            MiningPlot next = advance(server, level, plot);
            if (next == null) {
                return;
            }
            plot = next;
        }
    }

    private static MiningPlot advance(MinecraftServer server, ServerLevel level, MiningPlot plot) {
        MiningPlot live = MiningPlotManager.INSTANCE.byId(plot.id());
        if (live == null || live.currentY() != plot.currentY()) {
            return null;
        }
        int nextY = live.currentY() - 1;
        if (nextY < level.getMinBuildHeight()) {
            MiningPlotManager.INSTANCE.update(live.withStatus(MiningPlot.STATUS_DONE));
            unregister(live.id());
            return null;
        }
        MiningPlot next = live.withProgress(nextY, 0);
        MiningPlotManager.INSTANCE.update(next);
        return next;
    }

    private static void pushQueue(ServerLevel level, MiningPlot plot, List<Integer> pending) {
        if (plot.workerIds().isEmpty()) {
            return;
        }
        int[] cells = new int[pending.size()];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = pending.get(i);
        }
        for (UUID workerId : plot.workerIds()) {
            NpcData data = NpcManager.INSTANCE.getNpc(workerId);
            if (data == null || data.isDead()) {
                continue;
            }
            NpcBrain brain = data.getBrain();
            if (!brain.isMiningControlled() || brain.getDetail() != NpcDetail.MINING) {
                continue;
            }
            UUID entityId = data.getEntityUuid();
            if (entityId == null) {
                continue;
            }
            Entity entity = level.getEntity(entityId);
            if (!(entity instanceof NpcEntity npc)) {
                continue;
            }
            CompoundTag tag = npc.getPersistentData();
            int[] existing = tag.getIntArray(NpcMiningService.NBT_LAYER_QUEUE);
            boolean unchanged = tag.getInt(NpcMiningService.NBT_LAYER_Y) == plot.currentY()
                    && Arrays.equals(existing, cells);
            if (unchanged) {
                continue;
            }
            tag.putInt(NpcMiningService.NBT_LAYER_Y, plot.currentY());
            tag.putIntArray(NpcMiningService.NBT_LAYER_QUEUE, cells);
        }
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
