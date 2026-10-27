package com.xy2407.kingdom.common.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** RTS 队伍：队长锚点、成员 UUID 及每人相对队长的水平偏移，附阵型是否已保存。 */
public final class RtsTeam {

    private final UUID teamId;
    private final UUID ownerUuid;
    private UUID leaderUuid;
    private final List<UUID> members = new ArrayList<>();
    private final Map<UUID, float[]> offsets = new LinkedHashMap<>();
    private RtsFormation.Shape shape = RtsFormation.Shape.NONE;
    private boolean formationSaved;

    public RtsTeam(UUID teamId, UUID ownerUuid, UUID leaderUuid) {
        this.teamId = teamId;
        this.ownerUuid = ownerUuid;
        this.leaderUuid = leaderUuid;
        if (leaderUuid != null) {
            members.add(leaderUuid);
        }
    }

    public UUID teamId() {
        return teamId;
    }

    public UUID ownerUuid() {
        return ownerUuid;
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

    public RtsFormation.Shape shape() {
        return shape;
    }

    public void setShape(RtsFormation.Shape shape) {
        this.shape = shape == null ? RtsFormation.Shape.NONE : shape;
    }

    public boolean formationSaved() {
        return formationSaved;
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
}
