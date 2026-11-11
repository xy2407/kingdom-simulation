package com.xy2407.kingdom.server.military;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.combat.CombatWeapons;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcInventory;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.core.component.DataComponents;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 士兵服务，管理职业标记、军营就地进食与从仓库自动发放装备
 */
public final class SoldierService {

    public static final String JOB = NpcData.JOB_SOLDIER;

    public static final int FEED_RETRY_TICKS = 1200;

    private SoldierService() {
    }

    public static boolean isSoldier(NpcData npc) {
        return npc != null && JOB.equals(npc.getJob());
    }

    public static void markSoldier(NpcData npc) {
        if (npc == null) {
            return;
        }
        npc.setJob(JOB);
        npc.getBrain().setSoldierControlled(true);
    }

    public static void clearSoldier(NpcData npc) {
        if (npc == null) {
            return;
        }
        if (JOB.equals(npc.getJob())) {
            npc.setJob("");
        }
        npc.getBrain().setSoldierControlled(false);
        NpcEntity entity = NpcManager.INSTANCE.liveEntity(npc);
        if (entity != null && !entity.isRemoved()) {
            entity.removeEffect(MobEffects.WEAKNESS);
        }
    }

    private static int feedCounter;

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (--feedCounter > 0) {
            return;
        }
        feedCounter = FEED_RETRY_TICKS;
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !isSoldier(npc)) {
                continue;
            }
            tryFeed(npc);
        }
    }

    public static List<NpcData> soldiersOf(UUID buildingId) {
        List<NpcData> result = new ArrayList<>();
        if (buildingId == null) {
            return result;
        }
        String owner = buildingId.toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !owner.equals(npc.getWorkplaceId())) {
                continue;
            }
            if (isSoldier(npc)) {
                result.add(npc);
            }
        }
        return result;
    }

    public static void tryFeed(NpcData npc) {
        if (npc == null || npc.isDead()) {
            return;
        }
        NpcEntity entity = NpcManager.INSTANCE.liveEntity(npc);
        if (entity == null || entity.isRemoved()) {
            return;
        }
        if (npc.getHunger() >= NpcData.HUNGER_MAX) {
            entity.removeEffect(MobEffects.WEAKNESS);
            return;
        }
        boolean fed = eatFromBuilding(npc, entity) || eatFromBackpack(npc, entity);
        if (fed) {
            npc.setHunger(NpcData.HUNGER_MAX);
            entity.removeEffect(MobEffects.WEAKNESS);
            return;
        }
        entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, FEED_RETRY_TICKS * 4, 1,
                false, false, true));
    }

    private static boolean eatFromBuilding(NpcData npc, NpcEntity entity) {
        BuildingRecord building = buildingOf(npc);
        if (building == null || !inside(entity, building)) {
            return false;
        }
        MinecraftServer server = entity.getServer();
        if (server == null) {
            return false;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        int slots = Math.max(1, meta == null ? 27 : meta.storageSlots());
        ItemStackHandler storage = BuildingStorageStore.INSTANCE.handlerOf(server, building.id(), slots);
        for (int slot = 0; slot < storage.getSlots(); slot++) {
            ItemStack stack = storage.getStackInSlot(slot);
            if (!isFood(stack)) {
                continue;
            }
            storage.extractItem(slot, 1, false);
            return true;
        }
        return false;
    }

    private static boolean eatFromBackpack(NpcData npc, NpcEntity entity) {
        NpcInventory inventory = entity.getNpcInventory();
        for (int slot = NpcInventory.BAG_START; slot < NpcInventory.SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!isFood(stack)) {
                continue;
            }
            inventory.extractItem(slot, 1, false);
            return true;
        }
        return false;
    }

    private static boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    private static boolean inside(NpcEntity entity, BuildingRecord building) {
        return entity.level().dimension().location().toString().equals(building.dimension())
                && entity.getX() >= building.min().getX() - 1
                && entity.getX() <= building.max().getX() + 2
                && entity.getY() >= building.min().getY() - 2
                && entity.getY() <= building.max().getY() + 2
                && entity.getZ() >= building.min().getZ() - 1
                && entity.getZ() <= building.max().getZ() + 2;
    }

    public static BuildingRecord buildingOf(NpcData npc) {
        if (npc == null || npc.getWorkplaceId() == null || npc.getWorkplaceId().isBlank()) {
            return null;
        }
        try {
            return BuildingRegistry.INSTANCE.byId(UUID.fromString(npc.getWorkplaceId()));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final int[] NPC_ARMOR_SLOTS = {
            NpcInventory.HEAD, NpcInventory.CHEST, NpcInventory.LEGS, NpcInventory.FEET
    };

    public static int autoEquip(ServerLevel level, BuildingRecord building) {
        if (level == null || building == null) {
            return 0;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return 0;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        int slots = Math.max(1, meta == null ? 27 : meta.storageSlots());
        ItemStackHandler storage = BuildingStorageStore.INSTANCE.handlerOf(server, building.id(), slots);

        List<NpcData> soldiers = soldiersOf(building.id());
        if (soldiers.isEmpty()) {
            return 0;
        }
        int equipped = 0;
        for (int suit = 0; suit < ARMOR_SLOTS.length; suit++) {
            EquipmentSlot slot = ARMOR_SLOTS[suit];
            int npcSlot = NPC_ARMOR_SLOTS[suit];
            List<Integer> candidates = new ArrayList<>();
            for (int index = 0; index < storage.getSlots(); index++) {
                ItemStack stack = storage.getStackInSlot(index);
                if (armorFor(stack, slot)) {
                    candidates.add(index);
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            candidates.sort(Comparator.comparingDouble(
                    (Integer index) -> -defenseOf(storage.getStackInSlot(index))));
            int cursor = 0;
            for (NpcData soldier : soldiers) {
                if (cursor >= candidates.size()) {
                    break;
                }
                NpcEntity entity = NpcManager.INSTANCE.liveEntity(soldier);
                if (entity == null || entity.isRemoved()) {
                    continue;
                }
                NpcInventory inventory = entity.getNpcInventory();
                if (!inventory.getStackInSlot(npcSlot).isEmpty()) {
                    continue;
                }
                int storageSlot = candidates.get(cursor++);
                ItemStack stack = storage.extractItem(storageSlot, 1, false);
                if (stack.isEmpty()) {
                    continue;
                }
                inventory.setStackInSlot(npcSlot, stack);
                equipped++;
            }
        }
        List<Integer> weapons = new ArrayList<>();
        for (int index = 0; index < storage.getSlots(); index++) {
            if (CombatWeapons.isCombatWeapon(storage.getStackInSlot(index))) {
                weapons.add(index);
            }
        }
        weapons.sort(Comparator.comparingDouble(
                (Integer index) -> -attackOf(storage.getStackInSlot(index))));
        int weaponCursor = 0;
        for (NpcData soldier : soldiers) {
            if (weaponCursor >= weapons.size()) {
                break;
            }
            NpcEntity entity = NpcManager.INSTANCE.liveEntity(soldier);
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            NpcInventory inventory = entity.getNpcInventory();
            if (!inventory.getStackInSlot(NpcInventory.MAIN_HAND).isEmpty()) {
                continue;
            }
            ItemStack stack = storage.extractItem(weapons.get(weaponCursor++), 1, false);
            if (stack.isEmpty()) {
                continue;
            }
            inventory.setStackInSlot(NpcInventory.MAIN_HAND, stack);
            equipped++;
        }
        for (NpcData soldier : soldiers) {
            NpcEntity entity = NpcManager.INSTANCE.liveEntity(soldier);
            if (entity != null && !entity.isRemoved()) {
                entity.applyEquipment();
            }
        }
        return equipped;
    }

    private static boolean armorFor(ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == slot;
    }

    private static double defenseOf(ItemStack stack) {
        if (stack.getItem() instanceof ArmorItem armor) {
            return armor.getDefense();
        }
        return 0.0D;
    }

    private static double attackOf(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) {
            return 0.0D;
        }
        double best = 0.0D;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (!entry.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) {
                continue;
            }
            best = Math.max(best, entry.modifier().amount());
        }
        return best;
    }

    public static ServerLevel levelOf(MinecraftServer server, BuildingRecord building) {
        if (server == null || building == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(building.dimension());
        if (id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
