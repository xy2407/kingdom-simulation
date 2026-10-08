package com.xy2407.kingdom.server.npc;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcSchedule;
import com.xy2407.kingdom.common.path.SnapshotRepository;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 上下班错峰通勤调度器：把整城的出行从每个 NPC 手里收上来，按固定速率摊开派发。
 */
public final class CommuteScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int COLLECT_INTERVAL_TICKS = 100;

    private static final int MORNING_WINDOW_START = 23001;
    private static final int EVENING_WINDOW_START = 12000;
    private static final int EVENING_WINDOW_END = 13000;

    private static final int BATCH_INTERVAL_TICKS = 40;
    private static final int BATCH_SIZE = 200;
    private static final int SEARCHES_PER_TICK = BATCH_SIZE / BATCH_INTERVAL_TICKS;

    private static final double TELEPORT_DISTANCE_SQR = 96.0D * 96.0D;

    private static final int IN_FLIGHT_TIMEOUT_TICKS = 200;

    private static final Deque<Task> PENDING = new ArrayDeque<>();
    private static final Set<UUID> QUEUED = new HashSet<>();
    private static final Map<UUID, InFlight> IN_FLIGHT = new HashMap<>();

    private static int tickCounter;

    private CommuteScheduler() {
    }

    public static void reset() {
        PENDING.clear();
        QUEUED.clear();
        IN_FLIGHT.clear();
        tickCounter = 0;
    }

    private record Task(UUID citizenId, UUID entityUuid, BlockPos target) {
    }

    private record InFlight(UUID entityUuid, Future<List<BlockPos>> future, int startedAt) {
    }

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        tickCounter++;
        applyFinished(level);

        boolean evening = inEveningWindow(level);
        boolean morning = !evening && inMorningWindow(level);
        if (!morning && !evening) {
            if (!PENDING.isEmpty()) {
                PENDING.clear();
                QUEUED.clear();
            }
            return;
        }
        if (tickCounter % COLLECT_INTERVAL_TICKS == 0) {
            collect(level, evening);
        }
        dispatchSome(level);
    }


    private static long dayTime(ServerLevel level) {
        long time = level.getDayTime() % NpcSchedule.DAY_LENGTH;
        return time < 0 ? time + NpcSchedule.DAY_LENGTH : time;
    }

    private static boolean inMorningWindow(ServerLevel level) {
        long time = dayTime(level);
        return time >= MORNING_WINDOW_START && time < NpcSchedule.DAY_LENGTH;
    }

    private static boolean inEveningWindow(ServerLevel level) {
        long time = dayTime(level);
        return time >= EVENING_WINDOW_START && time < EVENING_WINDOW_END;
    }


    private static void collect(ServerLevel level, boolean goingHome) {
        int added = 0;
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc == null || npc.isDead()) {
                continue;
            }
            UUID citizenId = npc.getId();
            if (citizenId == null || QUEUED.contains(citizenId) || IN_FLIGHT.containsKey(citizenId)) {
                continue;
            }
            if (goingHome) {
                if (npc.getHomeId() == null) {
                    continue;
                }
            } else if (npc.getWorkplaceId() == null) {
                continue;
            }
            UUID entityUuid = npc.getEntityUuid();
            if (entityUuid == null) {
                continue;
            }
            BlockPos target = goingHome
                    ? new BlockPos(npc.getHomeX(), npc.getHomeY(), npc.getHomeZ())
                    : new BlockPos(npc.getWorkX(), npc.getWorkY(), npc.getWorkZ());
            if (!level.isLoaded(target)) {
                continue;
            }
            if (level.getEntity(entityUuid) == null) {
                continue;
            }
            PENDING.addLast(new Task(citizenId, entityUuid, target));
            QUEUED.add(citizenId);
            added++;
        }
        if (added > 0) {
            LOGGER.info("[通勤] {} 窗口收集到 {} 名市民，待办队列 {} 条",
                    goingHome ? "下班" : "上班", added, PENDING.size());
        }
    }


    private static void dispatchSome(ServerLevel level) {
        if (PENDING.isEmpty()) {
            return;
        }
        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository == null) {
            return;
        }
        repository.resetCommuteSearches();
        int budget = SEARCHES_PER_TICK;
        while (budget > 0 && !PENDING.isEmpty()) {
            Task task = PENDING.pollFirst();
            QUEUED.remove(task.citizenId());
            int result = applyTask(level, repository, task);
            if (result < 0) {
                break;
            }
            if (result > 0) {
                budget--;
            }
        }
    }

    private static int applyTask(ServerLevel level, SnapshotRepository repository, Task task) {
        Entity entity = level.getEntity(task.entityUuid());
        if (!(entity instanceof NpcEntity npc)) {
            return 0;
        }
        BlockPos target = task.target();
        if (npc.distanceToSqr(Vec3.atCenterOf(target)) > TELEPORT_DISTANCE_SQR) {
            npc.teleportTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
            return 1;
        }
        Future<List<BlockPos>> future = repository.submitCommuteSearch(level, npc.blockPosition(), target);
        if (future == null) {
            PENDING.addLast(task);
            QUEUED.add(task.citizenId());
            return -1;
        }
        IN_FLIGHT.put(task.citizenId(), new InFlight(task.entityUuid(), future, tickCounter));
        return 1;
    }


    private static void applyFinished(ServerLevel level) {
        if (IN_FLIGHT.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, InFlight>> iterator = IN_FLIGHT.entrySet().iterator();
        while (iterator.hasNext()) {
            InFlight flight = iterator.next().getValue();
            if (!flight.future().isDone()) {
                if (tickCounter - flight.startedAt() > IN_FLIGHT_TIMEOUT_TICKS) {
                    iterator.remove();
                }
                continue;
            }
            iterator.remove();
            List<BlockPos> waypoints = takePath(flight.future());
            if (waypoints == null || waypoints.size() < 2) {
                continue;
            }
            Entity entity = level.getEntity(flight.entityUuid());
            if (entity instanceof NpcEntity npc) {
                npc.applyPath(waypoints);
            }
        }
    }

    private static List<BlockPos> takePath(Future<List<BlockPos>> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException failed) {
            return null;
        }
    }
}
