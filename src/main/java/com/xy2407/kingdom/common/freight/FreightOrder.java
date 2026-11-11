package com.xy2407.kingdom.common.freight;

import com.xy2407.kingdom.common.building.ItemCount;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/** 货运订单：把一批材料从某个仓库运到某个需求方的一次完整委托。 */
public final class FreightOrder {

    public enum State {
        PENDING,
        ASSIGNED,
        PICKING_UP,
        DELIVERING,
        RETURNING,
        DONE,
        FAILED
    }

    public enum Target {
        BUILDING,
        CONTAINER
    }

    private final UUID id;
    private final Target target;
    private final UUID requesterId;
    private final BlockPos destination;
    private final String dimension;
    private final List<ItemCount> items;
    private final long createdAt;

    private State state = State.PENDING;
    private long stateAt;
    private UUID warehouseId;
    private UUID pickupId;
    private UUID carrierId;
    private long nextAttemptAt;
    private int attempts;
    private String reason;

    private FreightOrder(UUID id, Target target, UUID requesterId, BlockPos destination,
                         String dimension, List<ItemCount> items, long createdAt) {
        this.id = id;
        this.target = target;
        this.requesterId = requesterId;
        this.destination = destination == null ? null : destination.immutable();
        this.dimension = dimension == null ? "" : dimension;
        this.items = List.copyOf(items);
        this.createdAt = createdAt;
    }

    public static FreightOrder toBuilding(UUID requesterId, String dimension,
                                          List<ItemCount> items, long createdAt) {
        return new FreightOrder(UUID.randomUUID(), Target.BUILDING, requesterId, null,
                dimension, items, createdAt);
    }

    public static FreightOrder toContainer(String dimension, BlockPos destination,
                                           List<ItemCount> items, long createdAt) {
        return new FreightOrder(UUID.randomUUID(), Target.CONTAINER, null, destination,
                dimension, items, createdAt);
    }

    public UUID id() {
        return id;
    }

    public Target target() {
        return target;
    }

    public UUID requesterId() {
        return requesterId;
    }

    public BlockPos destination() {
        return destination;
    }

    public String dimension() {
        return dimension;
    }

    public List<ItemCount> items() {
        return items;
    }

    public long createdAt() {
        return createdAt;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
        this.stateAt = 0L;
    }

    public long stateAt() {
        return stateAt;
    }

    public void setStateAt(long stateAt) {
        this.stateAt = stateAt;
    }

    public UUID warehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public UUID pickupId() {
        return pickupId;
    }

    public void setPickupId(UUID pickupId) {
        this.pickupId = pickupId;
    }

    public UUID carrierId() {
        return carrierId;
    }

    public void setCarrierId(UUID carrierId) {
        this.carrierId = carrierId;
    }

    public long nextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(long tick) {
        this.nextAttemptAt = tick;
    }

    public int attempts() {
        return attempts;
    }

    public int incrementAttempts() {
        return ++attempts;
    }

    public String reason() {
        return reason;
    }

    public boolean inProgress() {
        return state == State.ASSIGNED || state == State.PICKING_UP
                || state == State.DELIVERING || state == State.RETURNING;
    }

    public boolean finished() {
        return state == State.DONE || state == State.FAILED;
    }

    public boolean sameRequirement(FreightOrder other) {
        if (other == null || other.target != target || !other.items.equals(items)) {
            return false;
        }
        return target == Target.BUILDING
                ? requesterId != null && requesterId.equals(other.requesterId)
                : destination != null && destination.equals(other.destination);
    }

    public void fail(String reason) {
        this.reason = reason;
        this.state = State.FAILED;
        this.carrierId = null;
    }
}
