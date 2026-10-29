package com.xy2407.kingdom.common.npc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.home.HomeBeds;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NpcSchema;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * 市民内存索引与变更入口，dirty增量写库
 */
public final class NpcManager {
    public static final NpcManager INSTANCE = new NpcManager();

    private static final int MAX_DEBUG_NPCS = 40;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLUSH_INTERVAL_TICKS = 60;

    private final Map<UUID, NpcData> byId = new ConcurrentHashMap<>();
    private final Map<UUID, NpcEntity> liveByCitizen = new ConcurrentHashMap<>();
    private boolean loaded;
    private int tickCounter;

    private NpcManager() {
    }

    public void reset() {
        byId.clear();
        liveByCitizen.clear();
        loaded = false;
        tickCounter = 0;
    }

    public void registerLive(NpcEntity entity) {
        if (entity != null && entity.getCitizenId() != null) {
            liveByCitizen.put(entity.getCitizenId(), entity);
        }
    }

    public void unregisterLive(NpcEntity entity) {
        if (entity != null && entity.getCitizenId() != null) {
            liveByCitizen.remove(entity.getCitizenId(), entity);
        }
    }

    public NpcEntity liveEntity(NpcData npc) {
        return npc == null ? null : liveByCitizen.get(npc.getId());
    }

    public BlockPos positionOf(NpcData npc) {
        NpcEntity entity = liveEntity(npc);
        return entity == null || entity.isRemoved() ? null : entity.blockPosition();
    }

    public void tick(ServerLevel overworld) {
        if (!loaded && KingdomPersistence.isReady()) {
            load();
        }
        if (!loaded) {
            return;
        }
        long dayTime = overworld == null ? 0L : overworld.getDayTime() % NpcSchedule.DAY_LENGTH;
        for (NpcData npc : byId.values()) {
            npc.setDayTime(dayTime);
            npc.getBrain().tick();
        }
        if (++tickCounter % FLUSH_INTERVAL_TICKS != 0) {
            return;
        }
        flushDirty();
    }

    public void onNewDay(int day) {
        for (NpcData npc : byId.values()) {
            if (!npc.isDead() && !npc.isTourist()) {
                npc.setAge(npc.getAge() + 1);
            }
        }
    }

    public NpcData getNpc(UUID id) {
        return id == null ? null : byId.get(id);
    }

    public List<String> debugReport(ServerPlayer player) {
        List<String> lines = new ArrayList<>();
        long dayTime = player.level().getDayTime() % NpcSchedule.DAY_LENGTH;
        lines.add("世界时刻=" + dayTime
                + "　白班此刻应=" + NpcSchedule.subState(NpcShift.DAY_SHIFT, dayTime)
                + "　夜班此刻应=" + NpcSchedule.subState(NpcShift.NIGHT_SHIFT, dayTime));
        String dimension = player.level().dimension().location().toString();
        ServerLevel level = player.level() instanceof ServerLevel server ? server : null;
        int shown = 0;
        for (NpcData npc : byId.values()) {
            if (npc.isDead() || !dimension.equals(npc.getDimension())) {
                continue;
            }
            if (shown++ >= MAX_DEBUG_NPCS) {
                lines.add("…… 同维度还有更多市民，只列前 " + MAX_DEBUG_NPCS + " 名");
                break;
            }
            lines.add(describe(npc, dayTime, level));
        }
        if (shown == 0) {
            lines.add("这个维度还没有市民");
        }
        return lines;
    }

    private String describe(NpcData npc, long dayTime, ServerLevel level) {
        NpcSubState expected = NpcSchedule.subState(npc.getShift(), dayTime);
        StringBuilder text = new StringBuilder(npc.getName())
                .append("　").append(npc.getState())
                .append(" / ").append(npc.getSubState())
                .append(" / ").append(npc.getDetail())
                .append("　班次=").append(npc.getShift())
                .append("　排班应=").append(expected);
        if (expected != npc.getSubState()) {
            text.append("　← 与当前子状态不符");
        }
        text.append("\n    饥饿=").append(String.format(java.util.Locale.ROOT, "%.1f", npc.getHunger()));
        text.append("　住处=");
        String homeId = npc.getHomeId();
        if (homeId == null || homeId.isBlank()) {
            text.append("无");
        } else {
            BlockPos bed = new BlockPos(npc.getHomeX(), npc.getHomeY(), npc.getHomeZ());
            text.append(short8(homeId)).append(" 床=").append(bed.toShortString());
            if (level != null) {
                text.append(HomeBeds.assignedBed(level, npc) == null ? " 床已失效" : " 床有效");
            }
        }
        String workplace = npc.getWorkplaceId();
        text.append("　岗位=").append(workplace == null || workplace.isBlank() ? "无" : short8(workplace));
        text.append("　配方=").append(npc.getWorkRecipeId().isBlank() ? "无" : npc.getWorkRecipeId());
        text.append("\n    ").append(npc.getBrain().debugLine());
        return text.toString();
    }

    private static String short8(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public Collection<NpcData> allNpcs() {
        List<NpcData> citizens = new ArrayList<>(byId.size());
        for (NpcData npc : byId.values()) {
            if (!npc.isTourist()) {
                citizens.add(npc);
            }
        }
        return citizens;
    }

    public void registerNpc(NpcData npc) {
        applySkin(npc);
        npc.markDirty();
        byId.put(npc.getId(), npc);
        KingdomPersistence.submitNow(NpcSchema.TABLE_NPC, NpcSchema.COL_UUID, npc.getId().toString(), npc.toValues());
        npc.clearDirty();
    }

    public void removeNpc(UUID id) {
        byId.remove(id);
        KingdomPersistence.delete(NpcSchema.TABLE_NPC, NpcSchema.COL_UUID, id.toString());
    }

    public void flushDirty() {
        for (NpcData npc : byId.values()) {
            if (npc.isDirty()) {
                KingdomPersistence.submit(NpcSchema.TABLE_NPC, NpcSchema.COL_UUID, npc.getId().toString(), npc.toValues());
                npc.clearDirty();
            }
        }
    }

    public void flushSync() {
        flushDirty();
    }

    private static boolean applySkin(NpcData npc) {
        if (!npc.getSkin().isEmpty()) {
            return false;
        }
        npc.setSkin(NpcSkins.assign(npc.getGender(), npc.getId()));
        return true;
    }

    private void load() {
        try {
            KingdomPersistence.querySync(connection -> {
                List<NpcData> rows = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + NpcSchema.columns() + " FROM " + NpcSchema.TABLE_NPC)) {
                    while (rs.next()) {
                        rows.add(mapRow(rs));
                    }
                }
                for (NpcData npc : rows) {
                    if (!applySkin(npc)) {
                        npc.clearDirty();
                    }
                    byId.put(npc.getId(), npc);
                }
                return rows.size();
            });
            loaded = true;
            LOGGER.info("已加载 {} 名市民", byId.size());
        } catch (Exception e) {
            LOGGER.error("加载市民数据失败", e);
        }
    }

    private static NpcData mapRow(ResultSet rs) throws SQLException {
        NpcData npc = new NpcData(
                UUID.fromString(rs.getString(NpcSchema.COL_UUID)),
                uuid(rs, NpcSchema.COL_ENTITY_UUID),
                rs.getString(NpcSchema.COL_NAME),
                rs.getString(NpcSchema.COL_NATION),
                rs.getString(NpcSchema.COL_GENDER),
                uuid(rs, NpcSchema.COL_FAMILY_ID),
                uuid(rs, NpcSchema.COL_FATHER_ID),
                uuid(rs, NpcSchema.COL_MOTHER_ID),
                uuid(rs, NpcSchema.COL_SPOUSE_ID),
                rs.getString(NpcSchema.COL_JOB),
                rs.getString(NpcSchema.COL_WORKPLACE_ID),
                rs.getString(NpcSchema.COL_HOME_ID),
                rs.getInt(NpcSchema.COL_AGE),
                rs.getString(NpcSchema.COL_LIFECYCLE),
                rs.getString(NpcSchema.COL_HOBBIES),
                rs.getString(NpcSchema.COL_LIKED_ITEMS),
                rs.getString(NpcSchema.COL_PERSONALITY),
                rs.getInt(NpcSchema.COL_STRENGTH),
                rs.getInt(NpcSchema.COL_INTELLIGENCE),
                rs.getInt(NpcSchema.COL_AGILITY),
                rs.getDouble(NpcSchema.COL_HEALTH),
                rs.getDouble(NpcSchema.COL_MOOD),
                rs.getDouble(NpcSchema.COL_HUNGER),
                rs.getDouble(NpcSchema.COL_STAMINA),
                rs.getLong(NpcSchema.COL_WEALTH),
                rs.getInt(NpcSchema.COL_DEAD) != 0,
                rs.getInt(NpcSchema.COL_BIRTH_DAY),
                rs.getInt(NpcSchema.COL_DEATH_DAY),
                rs.getLong(NpcSchema.COL_UPDATED_AT),
                rs.getString(NpcSchema.COL_DIMENSION),
                rs.getLong(NpcSchema.COL_CHUNK),
                rs.getInt(NpcSchema.COL_HOME_X),
                rs.getInt(NpcSchema.COL_HOME_Y),
                rs.getInt(NpcSchema.COL_HOME_Z),
                rs.getInt(NpcSchema.COL_WORK_X),
                rs.getInt(NpcSchema.COL_WORK_Y),
                rs.getInt(NpcSchema.COL_WORK_Z),
                rs.getInt(NpcSchema.COL_WORK_TICKS));
        npc.setRole(NpcRole.parse(rs.getString(NpcSchema.COL_ROLE)));
        npc.setOwnerId(rs.getString(NpcSchema.COL_OWNER_ID));
        npc.setTerritoryId(rs.getString(NpcSchema.COL_TERRITORY_ID));
        npc.setNationId(rs.getString(NpcSchema.COL_NATION_ID));
        npc.setState(NpcState.parse(rs.getString(NpcSchema.COL_STATE)));
        npc.setSubState(NpcSubState.parse(rs.getString(NpcSchema.COL_SUB_STATE)));
        npc.setDetail(NpcDetail.parse(rs.getString(NpcSchema.COL_DETAIL)));
        npc.setShift(NpcShift.parse(rs.getString(NpcSchema.COL_SHIFT)));
        npc.setWorkRecipeId(rs.getString(NpcSchema.COL_WORK_RECIPE_ID));
        npc.setInventoryJson(rs.getString(NpcSchema.COL_INVENTORY));
        npc.setSkin(rs.getString(NpcSchema.COL_SKIN));
        return npc;
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null || value.isEmpty() ? null : UUID.fromString(value);
    }
}
