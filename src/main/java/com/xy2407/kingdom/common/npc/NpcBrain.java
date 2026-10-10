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

    public enum WanderStyle { RANDOM_WALK, SOCIALIZE, SIT }

    private final NpcData owner;
    private boolean rtsControlled;
    private boolean constructionControlled;
    private boolean farmControlled;
    private boolean ranchControlled;
    private boolean industryControlled;
    private boolean restaurantControlled;
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
        if (industryControlled) {
            if (NpcSchedule.subState(owner.getShift(), owner.getDayTime()) == NpcSubState.WORKING) {
                setPlan(phase(), NpcSubState.WORKING, NpcDetail.PRODUCING);
                return;
            }
        }
        if (restaurantControlled) {
            if (NpcSchedule.subState(owner.getShift(), owner.getDayTime()) == NpcSubState.WORKING) {
                // 后厨里做菜和上菜是两个行为，具体干哪个由餐厅服务按员工分工决定
                setPlan(phase(), NpcSubState.WORKING, NpcRestaurantService.detailFor(owner));
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

    private void correctDrift() {
        if (rtsControlled || constructionControlled || farmControlled || ranchControlled
                || industryControlled || restaurantControlled) {
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
                subState = NpcSubState.RESTING;
                detail = NpcDetail.WANDERING;
            }
        } else if (subState == NpcSubState.WORKING && !hasWorkplace()) {
            subState = NpcSubState.RESTING;
            detail = NpcDetail.WANDERING;
        }
        if (detail == null) {
            NpcDetail current = owner.getDetail();
            // 休息时段特意不沿用 WANDERING：SLEEPING 与 WANDERING 的子状态都是 RESTING，
            // 沿用的话会让人一直闲逛下去、永远回不到睡觉（房子刚分配时尤其明显）。
            boolean restarted = subState == NpcSubState.RESTING
                    && current == NpcDetail.WANDERING && hasHome();
            if (owner.getSubState() == subState && current != null
                    && current.subState() == subState && !restarted) {
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
            // 有家就回家睡：睡觉本身就是回家的理由，不该先掷骰子决定睡不睡、输了就整晚在街上闲逛。
            // 而且回家窗口只有 1000 tick，SLEEPING 走的是 sleepAtHome（走到床边再躺下），
            // 所以哪怕这时人还在半路也会接着走回家，不会像以前那样卡在闲逛上。
            case RESTING -> hasHome() ? NpcDetail.SLEEPING : NpcDetail.WANDERING;
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

    public void setFarmDetail(NpcDetail detail) {
        NpcDetail next = detail == NpcDetail.PLANTING || detail == NpcDetail.HARVESTING
                ? detail : NpcDetail.FARMING;
        if (owner.getDetail() != next) {
            owner.setDetail(next);
        }
    }

    private NpcDetail farmDetail() {
        NpcDetail current = owner.getDetail();
        return current == NpcDetail.PLANTING || current == NpcDetail.HARVESTING ? current : NpcDetail.FARMING;
    }

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

    /**
     * 诊断用一行：当前 tick 计数、距下一次状态机检查还有多久，以及全部控制位。
     * 任何一位为 true，{@link #tick()} 都会在进入 mainLoop 之前提前返回，
     * 排班表就再也改不动这个人 —— 排查"为什么不睡觉"第一个要看的就是这里。
     */
    public String debugLine() {
        return "tick=" + tickCounter
                + " 距检查=" + (MAIN_LOOP_INTERVAL - Math.floorMod(tickCounter, MAIN_LOOP_INTERVAL)) + "t"
                + " 控制位[rts=" + rtsControlled
                + " 施工=" + constructionControlled
                + " 农=" + farmControlled
                + " 牧=" + ranchControlled
                + " 工=" + industryControlled
                + " 餐=" + restaurantControlled + "]";
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

    public boolean isIndustryControlled() {
        return industryControlled;
    }

    public void setIndustryControlled(boolean industryControlled) {
        this.industryControlled = industryControlled;
    }

    public boolean isRestaurantControlled() {
        return restaurantControlled;
    }

    public void setRestaurantControlled(boolean restaurantControlled) {
        this.restaurantControlled = restaurantControlled;
    }
}
