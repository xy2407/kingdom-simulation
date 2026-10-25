package com.xy2407.kingdom.common.employment;

import java.util.UUID;

import com.xy2407.kingdom.common.npc.NpcShift;

/**
 * 雇佣目标：岗位种类 + 所属建筑 + 具体目标 + 岗位键 + 班次
 */
public record EmploymentTarget(EmploymentKind kind, UUID buildingId, UUID targetId,
                               String roleKey, NpcShift shift) {

    public EmploymentTarget {
        roleKey = roleKey == null ? "" : roleKey;
        shift = shift == null ? NpcShift.DAY_SHIFT : shift;
    }

    public static EmploymentTarget building(UUID buildingId, String roleKey, NpcShift shift) {
        return new EmploymentTarget(EmploymentKind.BUILDING, buildingId, buildingId, roleKey, shift);
    }

    public static EmploymentTarget residence(UUID buildingId) {
        return new EmploymentTarget(EmploymentKind.RESIDENCE, buildingId, buildingId, "", NpcShift.DAY_SHIFT);
    }

    public static EmploymentTarget builder() {
        return new EmploymentTarget(EmploymentKind.BUILDER, null, null, "", NpcShift.DAY_SHIFT);
    }

    public static EmploymentTarget farm(UUID buildingId, UUID plotId) {
        return new EmploymentTarget(EmploymentKind.FARM, buildingId, plotId, "", NpcShift.DAY_SHIFT);
    }

    public static EmploymentTarget ranch(UUID buildingId, UUID penId) {
        return new EmploymentTarget(EmploymentKind.RANCH, buildingId, penId, "", NpcShift.DAY_SHIFT);
    }

    public static EmploymentTarget mining(UUID buildingId, UUID plotId) {
        return new EmploymentTarget(EmploymentKind.MINING, buildingId, plotId, "", NpcShift.DAY_SHIFT);
    }

    public static EmploymentTarget lumber(UUID buildingId) {
        return new EmploymentTarget(EmploymentKind.LUMBER, buildingId, buildingId, "", NpcShift.DAY_SHIFT);
    }

    public boolean sameSlot(EmploymentTarget other) {
        if (other == null || other.kind != kind) {
            return false;
        }
        return targetId == null ? other.targetId == null : targetId.equals(other.targetId);
    }

    public boolean sameWorkplace(EmploymentTarget other) {
        if (other == null || other.kind != kind) {
            return false;
        }
        return buildingId == null ? other.buildingId == null : buildingId.equals(other.buildingId);
    }

    public String describe() {
        return kind.label();
    }
}
