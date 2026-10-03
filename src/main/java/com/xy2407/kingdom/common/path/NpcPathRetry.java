package com.xy2407.kingdom.common.path;

import java.util.List;

import com.xy2407.kingdom.common.entity.NpcEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 卡住检测与阶梯重试，依次原地重算、切换 A*、放弃
 */
public final class NpcPathRetry {
    private static final int STUCK_TICKS = 100;
    private static final int RETRY_COOLDOWN_TICKS = 200;
    private static final int RESAMPLE_RADIUS = 8;
    private static final int ABANDONED_STAGE = 3;

    private BlockPos anchor;
    private int stuckTicks;
    private int stage;
    private int cooldown;

    public void reset() {
        anchor = null;
        stuckTicks = 0;
        stage = 0;
        cooldown = 0;
    }

    public void tick(NpcEntity npc) {
        if (cooldown > 0) {
            cooldown--;
            anchor = npc.blockPosition();
            stuckTicks = 0;
            return;
        }
        if (!npc.getNavigation().isInProgress()) {
            stage = 0;
            anchor = null;
            stuckTicks = 0;
            return;
        }
        if (anchor == null
                || npc.getBlockX() != anchor.getX()
                || npc.getBlockY() != anchor.getY()
                || npc.getBlockZ() != anchor.getZ()) {
            anchor = npc.blockPosition();
            stuckTicks = 0;
            return;
        }
        if (++stuckTicks < STUCK_TICKS) {
            return;
        }
        if (advance(npc)) {
            stuckTicks = 0;
        }
    }

    private boolean advance(NpcEntity npc) {
        if (stage == 0) {
            BlockPos center = npc.getNavTarget();
            npc.walkToRandomPoint(center == null ? npc.blockPosition() : center, RESAMPLE_RADIUS);
            stage = 1;
            return true;
        }
        if (stage == 1) {
            List<BlockPos> waypoints = search(npc);
            if (waypoints == null) {
                return false;
            }
            if (waypoints.isEmpty()) {
                abandon(npc);
            } else {
                npc.applyPath(waypoints);
                stage = 2;
            }
            return true;
        }
        abandon(npc);
        return true;
    }

    private List<BlockPos> search(NpcEntity npc) {
        BlockPos target = npc.getNavTarget();
        if (target == null || !(npc.level() instanceof ServerLevel level)) {
            return List.of();
        }
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository == null || !repository.tryAcquireSearch()) {
            return null;
        }
        try {
            NpcPathfinder.requestChunks(repository, level.dimension(), npc.blockPosition(), target);
            return NpcPathfinder.find(repository, level.dimension(), npc.blockPosition(), target);
        } finally {
            repository.releaseSearch();
        }
    }

    private void abandon(NpcEntity npc) {
        npc.getNavigation().stop();
        npc.onPathAbandoned();
        stage = ABANDONED_STAGE;
        cooldown = RETRY_COOLDOWN_TICKS;
    }
}
