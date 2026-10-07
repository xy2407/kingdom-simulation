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
 * 上下班错峰通勤调度器：挂在服务端 tick 上，把整城的出行摊开算。
 *
 * <h2>为什么要有它</h2>
 * 上下班原本由每个 NPC 的状态机各自每 240 tick 算一次，于是<b>全城在同一瞬间</b>一起改状态、
 * 一起寻路——一个两千人的城镇会在窗口开启那一 tick 同时爆出上千条 A*，主线程与后台搜索池
 * 一起堵住。这里把"什么时候算"从每个 NPC 手里收上来集中调度，让它按固定速率滴水：
 * 稳态每 tick 只新增 5 条搜索，任何一 tick 的负载都是平的。
 *
 * <h2>节奏</h2>
 * <ul>
 *   <li><b>窗口</b>：上班 23001～24000、下班 12000～13000（各 1000 tick，见 {@code NpcSchedule}）；</li>
 *   <li><b>收集</b>：窗口内每 {@value #COLLECT_INTERVAL_TICKS} tick 扫一遍全体市民，
 *       把"有目的地、但还没到"的挑进待办队列；</li>
 *   <li><b>派发</b>：每 tick 最多提交 {@value #SEARCHES_PER_TICK} 条，
 *       即每 {@value #BATCH_INTERVAL_TICKS} tick 恰好 {@value #BATCH_SIZE} 个——这就是需求里的批次；</li>
 *   <li><b>落地</b>：搜索在后台算完，主线程轮询到 {@code isDone()} 后 {@code applyPath} 交给原版导航。</li>
 * </ul>
 *
 * <h2>几条刻意的取舍</h2>
 * <ul>
 *   <li><b>不占 RTS 那条通道</b>：走 {@link SnapshotRepository#submitCommuteSearch}，
 *       名额与玩家的框选指令各算各的，互不排队；</li>
 *   <li><b>缺住宅/工作地点的直接跳过</b>：两者的分配系统还没做（{@code homeId}/{@code workplaceId}
 *       目前没有任何代码写入），所以这一层必须容忍大面积为空，不能拿它们当异常；</li>
 *   <li><b>超过 96 格直接传送</b>：{@code NpcPathfinder.MAX_RANGE} 就是 96，再远的搜索本来就空手而归，
 *       与其让它在队列里等到超时，不如直接把人送过去；</li>
 *   <li><b>未加载区块不处理</b>：实体不在场就丢掉这个任务（{@link #applyTask} 返回"跳过"），
 *       等玩家把区块加载回来，NPC 自己的状态机还会兜一次。</li>
 * </ul>
 */
public final class CommuteScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 窗口内每 100 tick 收集一次待办。 */
    private static final int COLLECT_INTERVAL_TICKS = 100;

    /** 上班窗口：23001 起（{@code NpcSchedule} 的 23000 是"开始去上工"，这里排除整点）。 */
    private static final int MORNING_WINDOW_START = 23001;
    /** 下班窗口。 */
    private static final int EVENING_WINDOW_START = 12000;
    private static final int EVENING_WINDOW_END = 13000;

    /** 每 40 tick 派一批，每批 200 个。 */
    private static final int BATCH_INTERVAL_TICKS = 40;
    private static final int BATCH_SIZE = 200;
    /** 摊平到每 tick 的提交量：200 ÷ 40 = 5。 */
    private static final int SEARCHES_PER_TICK = BATCH_SIZE / BATCH_INTERVAL_TICKS;

    /** 超过这个距离就传送。与 {@code NpcPathfinder.MAX_RANGE} 对齐。 */
    private static final double TELEPORT_DISTANCE_SQR = 96.0D * 96.0D;

    /** 一条搜索算这么久还没出来就丢掉，免得表无限膨胀。 */
    private static final int IN_FLIGHT_TIMEOUT_TICKS = 200;

    /** 本窗口待办队列。 */
    private static final Deque<Task> PENDING = new ArrayDeque<>();
    /** 已入队但还没派发的市民，用来去重——同一个人不需要排两次。 */
    private static final Set<UUID> QUEUED = new HashSet<>();
    /** 已提交、正等结果的搜索。 */
    private static final Map<UUID, InFlight> IN_FLIGHT = new HashMap<>();

    private static int tickCounter;

    private CommuteScheduler() {
    }

    /** 换存档时清干净：上一局的实体会话、坐标、待办都不该留到这一局。 */
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
            /* 窗口外：把待办清掉。留着的话下个窗口一开就会派出一批"按上个窗口算出来的目的地"，
               而且队列会跨昼夜一直涨。已经提交在算的那些不动，让它们自然完成。 */
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

    // ------------------------------------------------------------------ 窗口

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

    // ------------------------------------------------------------------ 收集

    /** 扫一遍全体市民，把"该上路却还没上路"的挑进待办队列。 */
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
            /* 缺目的地就跳过——分配系统还没做，这是常态而不是异常 */
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
            /* 未加载区块不处理：等玩家把区块加载回来，由 NPC 自己的状态机兜 */
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

    // ------------------------------------------------------------------ 派发

    /**
     * 每 tick 提交最多 {@value #SEARCHES_PER_TICK} 条，把一批 200 个摊到 40 tick 里。
     *
     * <p>每 tick 先重置通勤名额：这条通道与 RTS 指令那条互不相干，
     * 不能因为玩家在框选部队就把通勤挤掉，反过来也一样。</p>
     */
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
                /* 名额用尽：剩下的下一 tick 再说 */
                break;
            }
            if (result > 0) {
                budget--;
            }
        }
    }

    /**
     * @return {@code 1} 消耗了一个名额；{@code 0} 任务作废（实体不在）；{@code -1} 名额用尽、需稍后重试
     */
    private static int applyTask(ServerLevel level, SnapshotRepository repository, Task task) {
        Entity entity = level.getEntity(task.entityUuid());
        if (!(entity instanceof NpcEntity npc)) {
            /* 实体不在场（区块没加载 / 已销毁）：丢掉这个任务。
               下次收集（100 tick 后）若它回来了会重新入队。 */
            return 0;
        }
        BlockPos target = task.target();
        if (npc.distanceToSqr(Vec3.atCenterOf(target)) > TELEPORT_DISTANCE_SQR) {
            /* 超过 96 格：A* 本来也搜不到，直接送过去 */
            npc.teleportTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
            return 1;
        }
        Future<List<BlockPos>> future = repository.submitCommuteSearch(level, npc.blockPosition(), target);
        if (future == null) {
            /* 可能只是这一 tick 的名额用完了：放回队尾，下一 tick 再试 */
            PENDING.addLast(task);
            QUEUED.add(task.citizenId());
            return -1;
        }
        IN_FLIGHT.put(task.citizenId(), new InFlight(task.entityUuid(), future, tickCounter));
        return 1;
    }

    // ------------------------------------------------------------------ 落地

    /** 主线程轮询后台搜索结果；算完就交给原版导航执行。 */
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
                /* 没算出路径：这一趟就算了，状态机那边会照常把它当"还没到"处理 */
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
