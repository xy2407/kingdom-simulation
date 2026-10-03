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
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.persistence.NpcSchema;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 市民内存索引与变更入口，dirty增量写库
 */
public final class NpcManager {
    public static final NpcManager INSTANCE = new NpcManager();

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLUSH_INTERVAL_TICKS = 60;
    private static final int RECOVER_BIND_BUDGET = 64;
    private static final int RECOVER_SPAWN_BUDGET = 8;
    private static final int RESTORE_RETRY_TICKS = 200;

    private final Map<UUID, NpcData> byId = new ConcurrentHashMap<>();
    private final List<NpcData> restoreList = new ArrayList<>();
    private int restoreCursor;
    private int restoreRetryIn;
    private boolean loaded;
    private boolean restored;
    private int tickCounter;

    private NpcManager() {
    }

    public void reset() {
        byId.clear();
        restoreList.clear();
        restoreCursor = 0;
        restoreRetryIn = 0;
        loaded = false;
        restored = false;
        tickCounter = 0;
    }

    public void tick(ServerLevel overworld) {
        if (!loaded && KingdomPersistence.isReady()) {
            load();
        }
        if (!loaded) {
            return;
        }
        if (!restored && overworld != null) {
            if (restoreRetryIn > 0) {
                restoreRetryIn--;
            } else {
                recoverStep(overworld);
            }
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

    private void recoverStep(ServerLevel level) {
        int bindBudget = RECOVER_BIND_BUDGET;
        int spawnBudget = RECOVER_SPAWN_BUDGET;
        while (restoreCursor < restoreList.size()) {
            NpcData npc = restoreList.get(restoreCursor);
            if (npc.isRestoreDone()) {
                restoreCursor++;
                continue;
            }
            if (byId.get(npc.getId()) != npc) {
                npc.markRestoreDone();
                restoreCursor++;
                continue;
            }
            if (bindBudget <= 0 && spawnBudget <= 0) {
                return;
            }
            UUID entityUuid = npc.getEntityUuid();
            Entity found = entityUuid == null ? null : level.getEntity(entityUuid);
            if (found instanceof NpcEntity entity) {
                if (entity.getCitizenId() == null) {
                    entity.setCitizenId(npc.getId());
                }
                npc.markRestoreDone();
                restoreCursor++;
                bindBudget--;
                continue;
            }
            if (!level.isLoaded(BlockPos.containing(npc.getX(), npc.getY(), npc.getZ()))) {
                restoreCursor++;
                continue;
            }
            if (spawnBudget <= 0) {
                return;
            }
            spawnShell(level, npc);
            npc.markRestoreDone();
            restoreCursor++;
            spawnBudget--;
        }
        restoreCursor = 0;
        int remaining = 0;
        for (NpcData npc : restoreList) {
            if (!npc.isRestoreDone()) {
                remaining++;
            }
        }
        if (remaining == 0) {
            restored = true;
        } else {
            restoreRetryIn = RESTORE_RETRY_TICKS;
        }
    }

    private void spawnShell(ServerLevel level, NpcData npc) {
        NpcEntity entity = ModEntities.NPC.get().create(level);
        if (entity == null) {
            return;
        }
        entity.setCitizenId(npc.getId());
        entity.setPos(npc.getX(), npc.getY(), npc.getZ());
        level.addFreshEntity(entity);
        npc.setEntityUuid(entity.getUUID());
    }

    public void onNewDay(int day) {
        for (NpcData npc : byId.values()) {
            if (!npc.isDead()) {
                npc.setAge(npc.getAge() + 1);
            }
        }
    }

    public NpcData getNpc(UUID id) {
        return byId.get(id);
    }

    public Collection<NpcData> allNpcs() {
        return byId.values();
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
            if (npc.isDirty() || npc.isPositionDirty()) {
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
            restoreList.clear();
            restoreList.addAll(byId.values());
            restoreCursor = 0;
            restoreRetryIn = 0;
            restored = false;
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
                rs.getDouble(NpcSchema.COL_POS_X),
                rs.getDouble(NpcSchema.COL_POS_Y),
                rs.getDouble(NpcSchema.COL_POS_Z),
                rs.getInt(NpcSchema.COL_HOME_X),
                rs.getInt(NpcSchema.COL_HOME_Y),
                rs.getInt(NpcSchema.COL_HOME_Z),
                rs.getInt(NpcSchema.COL_WORK_X),
                rs.getInt(NpcSchema.COL_WORK_Y),
                rs.getInt(NpcSchema.COL_WORK_Z));
        npc.setState(NpcState.parse(rs.getString(NpcSchema.COL_STATE)));
        npc.setSubState(NpcSubState.parse(rs.getString(NpcSchema.COL_SUB_STATE)));
        npc.setDetail(NpcDetail.parse(rs.getString(NpcSchema.COL_DETAIL)));
        npc.setShift(NpcShift.parse(rs.getString(NpcSchema.COL_SHIFT)));
        npc.setInventoryJson(rs.getString(NpcSchema.COL_INVENTORY));
        npc.setSkin(rs.getString(NpcSchema.COL_SKIN));
        return npc;
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null || value.isEmpty() ? null : UUID.fromString(value);
    }
}
