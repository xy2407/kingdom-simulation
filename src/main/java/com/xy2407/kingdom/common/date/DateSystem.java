package com.xy2407.kingdom.common.date;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 统一日期系统，按dayTime增量累计并持久化跨天游标
 */
public final class DateSystem {
    public static final long TICK_INTERVAL = 20L;
    public static final long DAY_TICKS = 24000L;

    private DateSystem() {
    }

    public static Data get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Data.FACTORY, Data.DATA_NAME);
    }

    public static int totalDay(ServerLevel level) {
        return get(level).totalDay;
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % TICK_INTERVAL != 0L) {
            return;
        }
        Data data = get(level);
        long dayTime = level.getDayTime();
        if (data.lastDayTime < 0L) {
            data.lastDayTime = dayTime;
            data.setDirty();
            return;
        }
        long delta = dayTime - data.lastDayTime;
        if (delta > 0L) {
            data.addTime(delta);
        }
        data.lastDayTime = dayTime;
        data.setDirty();
    }

    public static final class Data extends SavedData {
        private static final String DATA_NAME = "kingdom_date";
        private static final String KEY_TOTAL_DAY = "TotalDay";
        private static final String KEY_PENDING_TICKS = "PendingTicks";
        private static final String KEY_LAST_DAY_TIME = "LastDayTime";
        private static final String KEY_LAST_PROCESSED_DAY = "LastProcessedDay";

        static final Factory<Data> FACTORY = new Factory<>(Data::new, Data::load, null);

        private int totalDay;
        private long pendingTicks;
        private long lastDayTime = -1L;
        private int lastProcessedDay;

        private Data() {
        }

        private static Data load(CompoundTag tag, HolderLookup.Provider provider) {
            Data data = new Data();
            data.totalDay = tag.getInt(KEY_TOTAL_DAY);
            data.pendingTicks = tag.getLong(KEY_PENDING_TICKS);
            data.lastDayTime = tag.getLong(KEY_LAST_DAY_TIME);
            data.lastProcessedDay = tag.getInt(KEY_LAST_PROCESSED_DAY);
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            tag.putInt(KEY_TOTAL_DAY, totalDay);
            tag.putLong(KEY_PENDING_TICKS, pendingTicks);
            tag.putLong(KEY_LAST_DAY_TIME, lastDayTime);
            tag.putInt(KEY_LAST_PROCESSED_DAY, lastProcessedDay);
            return tag;
        }

        public int totalDay() {
            return totalDay;
        }

        public int lastProcessedDay() {
            return lastProcessedDay;
        }

        public void addTime(long delta) {
            pendingTicks += delta;
            if (pendingTicks >= DAY_TICKS) {
                totalDay += (int) (pendingTicks / DAY_TICKS);
                pendingTicks %= DAY_TICKS;
            }
            setDirty();
        }

        public void commitDay() {
            lastProcessedDay = totalDay;
            setDirty();
        }
    }
}
