package com.xy2407.kingdom.common.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * RTS 队伍：队长锚点、成员 UUID 与每相对队长的水平偏移，附所属领地与阵型保存标记
 */
public final class RtsTeam {

    private final UUID teamId;
    private final UUID ownerUuid;
    private final String territoryId;
    private UUID leaderUuid;
    private final List<UUID> members = new ArrayList<>();
    private final Map<UUID, float[]> offsets = new LinkedHashMap<>();
    private RtsFormation.Shape shape = RtsFormation.Shape.NONE;
    private boolean formationSaved;
    /** 巡逻路线常驻在队伍上：服重启/区块卸载都不会丢，巡逻是永久任务直到玩家删除。 */
    private final List<BlockPos> patrolNodes = new ArrayList<>();
    private int patrolIndex;
    private int patrolForward = 1;
    /** 路线还记着、但当前不在巡逻（被坚守/进攻顶掉）：重启后不自动恢复，等玩家再点巡逻。 */
    private boolean patrolActive;

    public RtsTeam(UUID teamId, UUID ownerUuid, UUID leaderUuid) {
        this(teamId, ownerUuid, leaderUuid, "", List.of(), Map.of(), false);
    }

    public RtsTeam(UUID teamId, UUID ownerUuid, UUID leaderUuid, String territoryId,
                   List<UUID> members, Map<UUID, float[]> offsets, boolean formationSaved) {
        this.teamId = teamId;
        this.ownerUuid = ownerUuid;
        this.leaderUuid = leaderUuid;
        this.territoryId = territoryId == null ? "" : territoryId;
        this.members.addAll(members == null ? List.of() : members);
        if (this.members.isEmpty() && leaderUuid != null) {
            this.members.add(leaderUuid);
        }
        if (offsets != null) {
            this.offsets.putAll(offsets);
        }
        this.formationSaved = formationSaved;
    }

    public UUID teamId() {
        return teamId;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String territoryId() {
        return territoryId;
    }

    public UUID leaderUuid() {
        return leaderUuid;
    }

    public void setLeader(UUID leader) {
        this.leaderUuid = leader;
    }

    public List<UUID> members() {
        return List.copyOf(members);
    }

    public boolean contains(UUID member) {
        return members.contains(member);
    }

    public boolean add(UUID member) {
        if (member == null || members.contains(member)) {
            return false;
        }
        members.add(member);
        return true;
    }

    public boolean remove(UUID member) {
        offsets.remove(member);
        boolean removed = members.remove(member);
        if (removed && member != null && member.equals(leaderUuid)) {
            leaderUuid = members.isEmpty() ? null : members.get(0);
        }
        return removed;
    }

    public float[] offsetOf(UUID member) {
        float[] off = offsets.get(member);
        return off != null ? off : new float[]{0.0F, 0.0F};
    }

    public void setOffset(UUID member, float x, float z) {
        if (member != null) {
            offsets.put(member, new float[]{x, z});
        }
    }

    public Map<UUID, float[]> offsets() {
        return Map.copyOf(offsets);
    }

    public RtsFormation.Shape shape() {
        return shape;
    }

    public void setShape(RtsFormation.Shape shape) {
        this.shape = shape == null ? RtsFormation.Shape.NONE : shape;
    }

    public boolean formationSaved() {
        return formationSaved;
    }

    /**
     * 巡逻纵队里的槽位（队长不占槽位，0 开始按成员顺序排）。
     * 直接用内部列表算，避免巡逻每拍照抄一份 members。
     */
    public int patrolSlot(UUID member) {
        if (member == null) {
            return -1;
        }
        int slot = 0;
        for (UUID id : members) {
            if (id.equals(leaderUuid)) {
                continue;
            }
            if (id.equals(member)) {
                return slot;
            }
            slot++;
        }
        return -1;
    }

    public void setFormationSaved(boolean saved) {
        this.formationSaved = saved;
    }

    public float[] offsetFor(UUID member) {
        float[] off = offsets.get(member);
        if (off != null) {
            return off;
        }
        return RtsFormation.spiral(Math.max(0, members.indexOf(member)));
    }

    public void applyShape() {
        if (shape == null || shape == RtsFormation.Shape.NONE || members.isEmpty()) {
            return;
        }
        List<float[]> generated = RtsFormation.offsets(members.size(), shape);
        offsets.clear();
        for (int i = 0; i < members.size(); i++) {
            float[] off = generated.get(Math.min(i, generated.size() - 1));
            offsets.put(members.get(i), new float[]{off[0], off[1]});
        }
    }

    // ------------------------------------------------------------------ 巡逻路线

    public List<BlockPos> patrolNodes() {
        return List.copyOf(patrolNodes);
    }

    public boolean hasPatrol() {
        return patrolNodes.size() >= 2;
    }

    /** 队伍此刻是否在执行巡逻任务（有路线且任务在跑）。 */
    public boolean patrolActive() {
        return patrolActive && hasPatrol();
    }

    public int patrolIndex() {
        return patrolIndex;
    }

    public int patrolForward() {
        return patrolForward;
    }

    /** 覆盖整条巡逻路线并开始执行（玩家重新录点/开始巡逻走这里，写库由调用方负责）。 */
    public void setPatrol(List<BlockPos> nodes, int index, int forward) {
        patrolNodes.clear();
        if (nodes != null) {
            for (BlockPos node : nodes) {
                if (node != null) {
                    patrolNodes.add(node.immutable());
                }
            }
        }
        patrolForward = forward < 0 ? -1 : 1;
        patrolIndex = clampIndex(index);
        patrolActive = hasPatrol();
    }

    /** 只推进下标/方向（每过一个节点写一次，路线本身不变）。 */
    public void setPatrolProgress(int index, int forward) {
        patrolForward = forward < 0 ? -1 : 1;
        patrolIndex = clampIndex(index);
    }

    /**
     * 结束巡逻但保留路线：玩家给这支队伍下坚守/进攻时用，
     * 路线是玩家辛苦录的，不该被一次指令抹掉；但任务已停，重启后不自动恢复。
     */
    public void endPatrol() {
        patrolActive = false;
    }

    /** 彻底删除巡逻任务（玩家点删除 / 队伍解散）。 */
    public void clearPatrol() {
        patrolNodes.clear();
        patrolIndex = 0;
        patrolForward = 1;
        patrolActive = false;
    }

    private int clampIndex(int index) {
        if (patrolNodes.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(patrolNodes.size() - 1, index));
    }
}