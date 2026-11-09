package com.xy2407.kingdom.client.rts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.xy2407.kingdom.common.network.clientbound.RtsTeamPosBridge;
import com.xy2407.kingdom.common.network.rts.RtsTeamMemberPosPacket;

import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 队伍成员坐标缓存：服务端每 20 tick 推一次，供阵型编辑器即使实体未加载也能画点
 */
@OnlyIn(Dist.CLIENT)
public final class RtsTeamPosCache {

    private static final Map<UUID, Map<UUID, Vec3>> POSITIONS = new ConcurrentHashMap<>();

    private RtsTeamPosCache() {
    }

    public static void install() {
        RtsTeamPosBridge.setSink(RtsTeamPosCache::accept);
    }

    private static void accept(UUID teamId, List<RtsTeamMemberPosPacket.MemberPos> members) {
        if (teamId == null) {
            return;
        }
        Map<UUID, Vec3> map = new HashMap<>();
        for (RtsTeamMemberPosPacket.MemberPos member : members) {
            map.put(member.uuid(), new Vec3(member.x(), member.y(), member.z()));
        }
        POSITIONS.put(teamId, map);
    }

    public static Vec3 of(UUID teamId, UUID member) {
        if (teamId == null || member == null) {
            return null;
        }
        Map<UUID, Vec3> map = POSITIONS.get(teamId);
        return map == null ? null : map.get(member);
    }

    public static void clear(UUID teamId) {
        if (teamId != null) {
            POSITIONS.remove(teamId);
        }
    }

    /** 队伍被解散/删除：只保留还存在队伍的坐标表，避免删掉的队伍永久占着缓存。 */
    public static void retain(java.util.Collection<UUID> teamIds) {
        java.util.Set<UUID> keep = new java.util.HashSet<>(teamIds);
        POSITIONS.keySet().removeIf(id -> !keep.contains(id));
    }

    public static void clearAll() {
        POSITIONS.clear();
    }
}
