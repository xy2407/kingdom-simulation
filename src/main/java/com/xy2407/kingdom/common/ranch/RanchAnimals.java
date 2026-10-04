package com.xy2407.kingdom.common.ranch;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * 养殖动物的实体优化：同类多只压成一个"基座实体"，其余以虚拟条目存在它的 NBT 里
 */
public final class RanchAnimals {

    public static final String ENTRIES_KEY = "kingdom_ranch_entries";
    public static final String PEN_KEY = "kingdom_ranch_pen";

    private static final int BABY_AGE = -24000;
    private static final int GROWTH_STEP = 2400;

    private RanchAnimals() {
    }

    public static String penOf(Mob mob) {
        return mob == null ? "" : mob.getPersistentData().getString(PEN_KEY);
    }

    public static List<CompoundTag> readEntries(Mob mob) {
        List<CompoundTag> entries = new ArrayList<>();
        if (mob == null) {
            return entries;
        }
        CompoundTag data = mob.getPersistentData();
        if (data.contains(ENTRIES_KEY, Tag.TAG_LIST)) {
            ListTag list = data.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                entries.add(list.getCompound(index).copy());
            }
        }
        return entries;
    }

    public static void setEntries(Mob mob, List<CompoundTag> entries) {
        if (mob == null) {
            return;
        }
        ListTag list = new ListTag();
        for (CompoundTag entry : entries) {
            list.add(entry.copy());
        }
        mob.getPersistentData().put(ENTRIES_KEY, list);
    }

    public static int count(Mob mob) {
        return readEntries(mob).size();
    }

    public static boolean isBase(Mob mob) {
        return mob != null && !readEntries(mob).isEmpty();
    }

    /** 成年数量：条目 Age ≥ 0 视为成年。 */
    public static int adults(Mob mob) {
        int adults = 0;
        for (CompoundTag entry : readEntries(mob)) {
            if (entry.getInt("Age") >= 0) {
                adults++;
            }
        }
        return adults;
    }

    /** 投喂一次：所有幼年条目长大一档，到 0 即成年。 */
    public static void grow(Mob mob) {
        List<CompoundTag> entries = readEntries(mob);
        boolean changed = false;
        for (CompoundTag entry : entries) {
            int age = entry.getInt("Age");
            if (age < 0) {
                entry.putInt("Age", Math.min(0, age + GROWTH_STEP));
                changed = true;
            }
        }
        if (changed) {
            setEntries(mob, entries);
        }
    }

    /** 把条目数补齐/裁剪到指定值，新增的一律按成年算（栏刚建成即成年牲畜）。 */
    public static void keep(Mob mob, int target) {
        List<CompoundTag> entries = readEntries(mob);
        while (entries.size() < target) {
            entries.add(new CompoundTag());
        }
        while (entries.size() > target) {
            entries.remove(entries.size() - 1);
        }
        setEntries(mob, entries);
    }

    /** 宰杀：拿掉 count 条成年，并补回等量幼年，形成"育肥 → 宰杀 → 繁殖"循环。 */
    public static int takeAdults(Mob mob, int count) {
        if (count <= 0) {
            return 0;
        }
        List<CompoundTag> entries = readEntries(mob);
        int taken = 0;
        for (int index = 0; index < entries.size() && taken < count; index++) {
            if (entries.get(index).getInt("Age") >= 0) {
                CompoundTag baby = new CompoundTag();
                baby.putInt("Age", BABY_AGE);
                entries.set(index, baby);
                taken++;
            }
        }
        if (taken > 0) {
            setEntries(mob, entries);
        }
        return taken;
    }

    /** 把栏内属于本栏的同类实体合并成一个基座，返回基座（栏内没有就返回 null）。 */
    public static Mob consolidate(ServerLevel level, AABB bounds, String penKey, EntityType<?> type) {
        if (level == null || type == null || penKey.isBlank()) {
            return null;
        }
        List<Mob> all = level.getEntitiesOfClass(Mob.class, bounds,
                mob -> mob.getType() == type && mob.isAlive() && penKey.equals(penOf(mob)));
        if (all.isEmpty()) {
            return null;
        }
        Mob base = null;
        int total = 0;
        for (Mob mob : all) {
            total += Math.max(1, count(mob));
            if (base == null || isBase(mob)) {
                base = mob;
            }
        }
        for (Mob mob : all) {
            if (mob != base) {
                mob.discard();
            }
        }
        base.getPersistentData().putString(PEN_KEY, penKey);
        keep(base, total);
        tame(base);
        return base;
    }

    public static Mob spawnBase(ServerLevel level, BlockPos pos, EntityType<?> type, String penKey, int count) {
        if (level == null || type == null || pos == null) {
            return null;
        }
        if (!(type.create(level) instanceof Mob mob)) {
            return null;
        }
        mob.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        mob.getPersistentData().putString(PEN_KEY, penKey);
        keep(mob, Math.max(1, count));
        tame(mob);
        level.addFreshEntity(mob);
        return mob;
    }

    /** 基座不参与 AI（不跑不吃不散），纯粹当数量载体，和参照实现的虚拟条目一个意思。 */
    private static void tame(Mob mob) {
        mob.setNoAi(true);
        mob.setPersistenceRequired();
    }

    public static void updateDisplay(Mob base, String label) {
        if (base == null) {
            return;
        }
        base.setCustomName(Component.literal(label + " ×" + count(base)));
        base.setCustomNameVisible(true);
    }

    /** 基座死亡：数量减一，用剩下的条目在原地补一个新基座（没有剩余就正常消失）。 */
    public static void handleBaseDeath(Mob base) {
        if (base == null || !(base.level() instanceof ServerLevel level)) {
            return;
        }
        List<CompoundTag> entries = readEntries(base);
        if (entries.size() <= 1) {
            return;
        }
        entries.remove(entries.size() - 1);
        if (!(base.getType().create(level) instanceof Mob fresh)) {
            return;
        }
        String penKey = penOf(base);
        fresh.moveTo(base.getX(), base.getY(), base.getZ(), base.getYRot(), base.getXRot());
        fresh.getPersistentData().putString(PEN_KEY, penKey);
        setEntries(fresh, entries);
        tame(fresh);
        level.addFreshEntity(fresh);
    }
}
