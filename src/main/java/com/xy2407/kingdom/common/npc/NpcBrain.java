package com.xy2407.kingdom.common.npc;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 市民状态机，按昼夜排班与饥饿驱动三主状态
 */
public final class NpcBrain {
    public static final int HUNGER_TICK_INTERVAL = 1200;
    public static final int MAIN_LOOP_INTERVAL = 240;
    public static final double HUNGER_TRIGGER = 2.0;
    private static final double SLACK_CHANCE = 0.15D;
    private static final double SLEEP_CHANCE = 0.5D;

    public enum WanderStyle { RANDOM_WALK, SOCIALIZE, SIT }

    private final NpcData owner;
    private boolean rtsControlled;
    private boolean constructionControlled;
    private boolean farmControlled;
    private boolean ranchControlled;
    private WanderStyle wanderStyle = WanderStyle.RANDOM_WALK;
    private boolean forageDue;
    private int tickCounter = ThreadLocalRandom.current().nextInt(MAIN_LOOP_INTERVAL);

    public NpcBrain(NpcData owner) {
        this.owner = owner;
    }

    public void tick() {
        tickCounter++;
        if (tickCounter % HUNGER_TICK_INTERVAL == 0) {
            owner.setHunger(owner.getHunger() - 1.0);
        }
        if (rtsControlled) {
            setPlan(NpcState.COMMANDED, NpcSubState.MOVING, NpcDetail.COMMANDED_MOVE);
            return;
        }
        if (owner.getSubState() == NpcSubState.HUNGRY) {
            if (owner.getHunger() < NpcData.HUNGER_MAX) {
                if (tickCounter % MAIN_LOOP_INTERVAL == 0) {
                    forageDue = true;
                }
                return;
            }
            forageDue = false;
            applySchedule();
        } else if (isStarving()) {
            forageDue = true;
            setPlan(phase(), NpcSubState.HUNGRY, NpcDetail.FORAGING);
            return;
        }
        if (constructionControlled) {
            setPlan(phase(), NpcSubState.WORKING, NpcDetail.BUILDING);
            return;
        }
        if (farmControlled) {
            // 只在自己班次的工时里下地，其余时间照常回家休息
            if (NpcSchedule.subState(owner.getShift(), owner.getDayTime()) == NpcSubState.WORKING) {
                setPlan(phase(), NpcSubState.WORKING, farmDetail());
                return;
            }
        }
        if (ranchControlled) {
            if (NpcSchedule.subState(owner.getShift(), owner.getDayTime()) == NpcSubState.WORKING) {
                setPlan(phase(), NpcSubState.WORKING, NpcDetail.RANCHING);
                return;
            }
        }
        if (tickCounter % MAIN_LOOP_INTERVAL != 0) {
            return;
        }
        mainLoop();
    }

    private void mainLoop() {
        applySchedule();
        correctDrift();
        if (owner.getDetail() == NpcDetail.WANDERING) {
            rollWanderStyle();
        }
    }

    /**
     * 窗口外的状态纠偏：把 detail 拉回"它与当前子状态该有的对应关系"。
     *
     * <p>时间驱动的正常推进由 {@link #applySchedule()} 负责（它已经在按当前时刻算该上班、
     * 该回家还是该休息），这里只兜住"对不上"的情况——detail 空着，或者子状态被别处改过而细节
     * 还停在旧的一档。受控状态（RTS 指令 / 施工 / 农活 / 养殖）的细节由各自系统指派，
     * 不在这里纠正，否则会把它们刚设好的行为抹掉。</p>
     */
    private void correctDrift() {
        if (rtsControlled || constructionControlled || farmControlled || ranchControlled) {
            return;
        }
        NpcSubState subState = owner.getSubState();
        NpcDetail detail = owner.getDetail();
        if (detail == null || detail.subState() != subState) {
            setPlan(phase(), subState, rollDetail(subState));
        }
    }

    private void applySchedule() {
        long dayTime = owner.getDayTime();
        NpcShift shift = owner.getShift();
        NpcSubState subState = NpcSchedule.subState(shift, dayTime);
        NpcDetail detail = null;
        if (subState == NpcSubState.MOVING) {
            detail = NpcSchedule.moveDetail(shift, dayTime);
            boolean goingWork = detail == NpcDetail.GOING_WORK;
            if ((goingWork && !hasWorkplace()) || (!goingWork && !hasHome())) {
                /* 该上班却没有工作地点（或该回家却没有住宅）：**统一降级为闲逛**。
                   不能留在这里不动，也不能放回 rollDetail——那对"有家"的人会掷出 SLEEPING，
                   把"没有工作地点的人"显示成在家睡觉。分配系统落地前这是常态而非异常。 */
                subState = NpcSubState.RESTING;
                detail = NpcDetail.WANDERING;
            }
        } else if (subState == NpcSubState.WORKING && !hasWorkplace()) {
            /* 同上：该干活却没有工作地点 → 闲逛 */
            subState = NpcSubState.RESTING;
            detail = NpcDetail.WANDERING;
        }
        if (detail == null) {
            NpcDetail current = owner.getDetail();
            if (owner.getSubState() == subState && current != null && current.subState() == subState) {
                detail = current;
            } else {
                detail = rollDetail(subState);
            }
        }
        setPlan(phase(), subState, detail);
    }

    private NpcDetail rollDetail(NpcSubState subState) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return switch (subState) {
            case WORKING -> random.nextDouble() < SLACK_CHANCE ? NpcDetail.SLACKING : NpcDetail.WORKING_FOCUS;
            case RESTING -> hasHome() && random.nextDouble() < SLEEP_CHANCE
                    ? NpcDetail.SLEEPING : NpcDetail.WANDERING;
            case HUNGRY -> NpcDetail.FORAGING;
            case MOVING -> NpcDetail.GOING_WORK;
        };
    }

    private void rollWanderStyle() {
        WanderStyle[] styles = WanderStyle.values();
        wanderStyle = styles[ThreadLocalRandom.current().nextInt(styles.length)];
    }

    private NpcState phase() {
        return NpcSchedule.isDay(owner.getDayTime()) ? NpcState.DAY : NpcState.NIGHT;
    }

    private boolean hasWorkplace() {
        return owner.getWorkplaceId() != null;
    }

    private boolean hasHome() {
        return owner.getHomeId() != null;
    }

    private void setPlan(NpcState state, NpcSubState subState, NpcDetail detail) {
        if (owner.getState() != state) {
            owner.setState(state);
        }
        if (owner.getSubState() != subState) {
            owner.setSubState(subState);
        }
        if (owner.getDetail() != detail) {
            owner.setDetail(detail);
        }
    }

    public boolean consumeForageDue() {
        if (!forageDue) {
            return false;
        }
        forageDue = false;
        return true;
    }

    /** 巡检结果写回状态机：种植中 / 收割中 / 在田里候着；值没变不写，免得每 tick 把市民标脏。 */
    public void setFarmDetail(NpcDetail detail) {
        NpcDetail next = detail == NpcDetail.PLANTING || detail == NpcDetail.HARVESTING
                ? detail : NpcDetail.FARMING;
        if (owner.getDetail() != next) {
            owner.setDetail(next);
        }
    }

    /** 农活子行为由巡检决定，状态机每 tick 复述时不能把它抹回 FARMING。 */
    private NpcDetail farmDetail() {
        NpcDetail current = owner.getDetail();
        return current == NpcDetail.PLANTING || current == NpcDetail.HARVESTING ? current : NpcDetail.FARMING;
    }

    /** 是否处于饥饿状态：饿着的人不能生产，派工前要先问这里。 */
    public boolean isStarving() {
        return owner.getHunger() < HUNGER_TRIGGER;
    }

    public void onPathAbandoned() {
        if (owner.getDetail() == NpcDetail.FORAGING) {
            forageDue = false;
        } else if (owner.getDetail() == NpcDetail.WANDERING) {
            rollWanderStyle();
        }
    }

    public NpcState getState() {
        return owner.getState();
    }

    public NpcSubState getSubState() {
        return owner.getSubState();
    }

    public NpcDetail getDetail() {
        return owner.getDetail();
    }

    public WanderStyle getWanderStyle() {
        return wanderStyle;
    }

    public boolean isRtsControlled() {
        return rtsControlled;
    }

    public void setRtsControlled(boolean rtsControlled) {
        this.rtsControlled = rtsControlled;
    }

    public boolean isConstructionControlled() {
        return constructionControlled;
    }

    public void setConstructionControlled(boolean constructionControlled) {
        this.constructionControlled = constructionControlled;
    }

    public boolean isFarmControlled() {
        return farmControlled;
    }

    public void setFarmControlled(boolean farmControlled) {
        this.farmControlled = farmControlled;
    }

    public boolean isRanchControlled() {
        return ranchControlled;
    }

    public void setRanchControlled(boolean ranchControlled) {
        this.ranchControlled = ranchControlled;
    }
}
