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
        if (tickCounter % MAIN_LOOP_INTERVAL != 0) {
            return;
        }
        mainLoop();
    }

    private void mainLoop() {
        applySchedule();
        if (owner.getDetail() == NpcDetail.WANDERING) {
            rollWanderStyle();
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
                detail = null;
            }
        } else if (subState == NpcSubState.WORKING && !hasWorkplace()) {
            subState = NpcSubState.RESTING;
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
}
