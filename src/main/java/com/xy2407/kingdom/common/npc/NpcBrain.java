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
    private boolean miningControlled;
    private boolean lumberControlled;
    private boolean lumberForced;
    private WanderStyle wanderStyle = WanderStyle.RANDOM_WALK;
    private boolean forageDue;
    private int tickCounter = ThreadLocalRandom.current().nextInt(MAIN_LOOP_INTERVAL);

    public NpcBrain(NpcData owner) {
        this.owner = owner;
    }

    public void tick() {
        tickCounter++;
        if (owner.isTourist()) {
            setPlan(NpcState.DAY, NpcSubState.MOVING, NpcDetail.TOURIST);
            return;
        }
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
        if (miningControlled) {
            NpcSubState scheduled = NpcSchedule.subState(owner.getShift(), owner.getDayTime());
            boolean goingWork = scheduled == NpcSubState.MOVING
                    && NpcSchedule.moveDetail(owner.getShift(), owner.getDayTime()) == NpcDetail.GOING_WORK;
            if (scheduled == NpcSubState.WORKING || goingWork) {
                setPlan(phase(), NpcSubState.WORKING, NpcDetail.MINING);
                return;
            }
            if (owner.getSubState() == NpcSubState.WORKING) {
                setPlan(phase(), NpcSubState.MOVING,
                        NpcSchedule.moveDetail(owner.getShift(), owner.getDayTime()));
                return;
            }
        }
        if (lumberControlled) {
            if (lumberForced) {
                setPlan(phase(), NpcSubState.WORKING, NpcDetail.LOGGING);
                return;
            }
            NpcSubState scheduled = NpcSchedule.subState(owner.getShift(), owner.getDayTime());
            boolean goingWork = scheduled == NpcSubState.MOVING
                    && NpcSchedule.moveDetail(owner.getShift(), owner.getDayTime()) == NpcDetail.GOING_WORK;
            if (scheduled == NpcSubState.WORKING || goingWork) {
                setPlan(phase(), NpcSubState.WORKING, NpcDetail.LOGGING);
                return;
            }
            if (owner.getSubState() == NpcSubState.WORKING) {
                setPlan(phase(), NpcSubState.MOVING,
                        NpcSchedule.moveDetail(owner.getShift(), owner.getDayTime()));
                return;
            }
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
                || industryControlled || restaurantControlled || miningControlled
                || lumberControlled) {
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
            } else if (goingWork && atWorkplace()) {
                subState = NpcSubState.WORKING;
                detail = null;
            } else if (!goingWork && atHome()) {
                subState = NpcSubState.RESTING;
                detail = null;
            }
        } else if (subState == NpcSubState.WORKING && !hasWorkplace()) {
            subState = NpcSubState.RESTING;
            detail = NpcDetail.WANDERING;
        }
        if (subState == NpcSubState.WORKING && !atWorkplace()) {
            subState = NpcSubState.MOVING;
            detail = NpcDetail.GOING_WORK;
        } else if (subState == NpcSubState.RESTING && hasHome() && !atHome()) {
            subState = NpcSubState.MOVING;
            detail = NpcDetail.GOING_HOME;
        }

        if (detail == NpcDetail.GOING_HOME && atHome()) {
            subState = NpcSubState.RESTING;
            detail = null;
        } else if (detail == NpcDetail.GOING_WORK && atWorkplace()) {
            subState = NpcSubState.WORKING;
            detail = null;
        }
        if (detail == null) {
            NpcDetail current = owner.getDetail();
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

    private boolean atWorkplace() {
        if (!hasWorkplace()) {
            return false;
        }
        if (owner.getWorkX() == 0 && owner.getWorkY() == 0 && owner.getWorkZ() == 0) {
            return true;
        }
        double dx = owner.getX() - (owner.getWorkX() + 0.5D);
        double dz = owner.getZ() - (owner.getWorkZ() + 0.5D);
        double dy = Math.abs(owner.getY() - owner.getWorkY());
        return dx * dx + dz * dz <= 4.0D && dy <= 2.0D;
    }

    private boolean atHome() {
        if (!hasHome()) {
            return false;
        }
        if (owner.getHomeX() == 0 && owner.getHomeY() == 0 && owner.getHomeZ() == 0) {
            return true;
        }
        double dx = owner.getX() - (owner.getHomeX() + 0.5D);
        double dz = owner.getZ() - (owner.getHomeZ() + 0.5D);
        double dy = Math.abs(owner.getY() - owner.getHomeY());
        return dx * dx + dz * dz <= 4.0D && dy <= 2.0D;
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

    private NpcDetail farmDetail() {
        return NpcFarmService.detailOf(owner.getId());
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

    public String debugLine() {
        return "tick=" + tickCounter
                + " 距检查=" + (MAIN_LOOP_INTERVAL - Math.floorMod(tickCounter, MAIN_LOOP_INTERVAL)) + "t"
                + " 控制位[rts=" + rtsControlled
                + " 施工=" + constructionControlled
                + " 农=" + farmControlled
                + " 牧=" + ranchControlled
                + " 工=" + industryControlled
                + " 餐=" + restaurantControlled
                + " 矿=" + miningControlled + "]";
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

    public boolean isMiningControlled() {
        return miningControlled;
    }

    public void setMiningControlled(boolean miningControlled) {
        this.miningControlled = miningControlled;
    }

    public boolean isLumberControlled() {
        return lumberControlled;
    }

    public void setLumberControlled(boolean lumberControlled) {
        this.lumberControlled = lumberControlled;
        if (!lumberControlled) {
            this.lumberForced = false;
        }
    }

    public void setLumberForced(boolean lumberForced) {
        this.lumberForced = lumberForced;
    }

    public void setRestaurantControlled(boolean restaurantControlled) {
        this.restaurantControlled = restaurantControlled;
    }
}
