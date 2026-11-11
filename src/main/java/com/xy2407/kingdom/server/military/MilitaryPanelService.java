package com.xy2407.kingdom.server.military;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingLevelRules;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.BuildingWork;
import com.xy2407.kingdom.common.combat.CombatWeapons;
import com.xy2407.kingdom.common.employment.EmploymentKind;
import com.xy2407.kingdom.common.employment.EmploymentResult;
import com.xy2407.kingdom.common.employment.EmploymentTarget;
import com.xy2407.kingdom.common.network.military.MilitaryPanelActionPacket;
import com.xy2407.kingdom.common.network.military.MilitaryPanelStatePacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcRole;
import com.xy2407.kingdom.common.npc.NpcShift;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.building.BuildingStorageStore;
import com.xy2407.kingdom.server.employment.EmploymentService;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 军事面板服务端，负责征召士兵、遣散以及从军营仓库一键发放装备
 */
public final class MilitaryPanelService {

    private MilitaryPanelService() {
    }

    public static void respond(ServerPlayer player) {
        send(player, locate(player));
    }

    private static BuildingRecord locate(ServerPlayer player) {
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            return null;
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        return meta != null && meta.isWork(BuildingWork.BARRACKS) ? building : null;
    }

    private static void send(ServerPlayer player, BuildingRecord building) {
        if (building == null) {
            PacketDistributor.sendToPlayer(player, MilitaryPanelStatePacket.outside());
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot(player, building));
    }

    private static MilitaryPanelStatePacket snapshot(ServerPlayer player, BuildingRecord building) {
        List<MilitaryPanelStatePacket.Member> soldiers = new ArrayList<>();
        for (NpcData npc : SoldierService.soldiersOf(building.id())) {
            soldiers.add(new MilitaryPanelStatePacket.Member(npc.getId(), npc.getName(),
                    npc.getSkin(), soldierNote(npc)));
        }
        List<MilitaryPanelStatePacket.Member> candidates = new ArrayList<>();
        String owner = building.id().toString();
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || npc.getRole() == NpcRole.TOURIST || owner.equals(npc.getWorkplaceId())) {
                continue;
            }
            String note = EmploymentService.INSTANCE.conflictAt(npc, null);
            candidates.add(new MilitaryPanelStatePacket.Member(npc.getId(), npc.getName(),
                    npc.getSkin(), note == null ? "" : note));
        }
        int[] stock = stockOf(player.server, building);
        String name = BuildingRegistry.INSTANCE.metaOf(building).name();
        return new MilitaryPanelStatePacket(true, building.id(),
                name == null || name.isBlank() ? building.name() : name,
                building.level(), BuildingLevelRules.militaryTroops(building.level()),
                soldiers, candidates, stock[0], stock[1]);
    }

    private static int[] stockOf(MinecraftServer server, BuildingRecord building) {
        int food = 0;
        int gear = 0;
        if (server == null) {
            return new int[]{0, 0};
        }
        BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
        int slots = Math.max(1, meta == null ? 27 : meta.storageSlots());
        ItemStackHandler storage = BuildingStorageStore.INSTANCE.handlerOf(server, building.id(), slots);
        for (int slot = 0; slot < storage.getSlots(); slot++) {
            var stack = storage.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.has(DataComponents.FOOD)) {
                food += stack.getCount();
            }
            if (CombatWeapons.isCombatWeapon(stack)
                    || stack.getItem() instanceof net.minecraft.world.item.ArmorItem) {
                gear += stack.getCount();
            }
        }
        return new int[]{food, gear};
    }

    private static String soldierNote(NpcData npc) {
        StringBuilder note = new StringBuilder();
        if (npc.getHunger() < 4.0D) {
            note.append("饥饿");
        }
        var entity = NpcManager.INSTANCE.liveEntity(npc);
        if (entity != null && !entity.isRemoved() && entity.isArmed()) {
            if (note.length() > 0) {
                note.append(" · ");
            }
            note.append("武装");
        }
        return note.toString();
    }

    public static void apply(ServerPlayer player, MilitaryPanelActionPacket packet) {
        BuildingRecord building = locate(player);
        if (building == null) {
            PacketDistributor.sendToPlayer(player, MilitaryPanelStatePacket.outside());
            return;
        }
        switch (packet.action()) {
            case MilitaryPanelActionPacket.ACTION_HIRE -> hire(player, building, packet.npcIdOrNull());
            case MilitaryPanelActionPacket.ACTION_DISMISS ->
                    dismiss(player, building, packet.npcIdOrNull());
            case MilitaryPanelActionPacket.ACTION_AUTO_EQUIP -> autoEquip(player, building);
            default -> {
            }
        }
        send(player, building);
    }

    private static void hire(ServerPlayer player, BuildingRecord building, UUID npcId) {
        if (npcId == null) {
            return;
        }
        EmploymentResult result = EmploymentService.INSTANCE.hire(player.server, npcId,
                EmploymentTarget.building(building.id(), "", NpcShift.DAY_SHIFT));
        if (!result.success()) {
            NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
            String who = npc == null ? "这名市民" : npc.getName();
            player.displayClientMessage(Component.literal(who + " " + result.reason()), true);
            return;
        }
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        if (npc != null) {
            SoldierService.markSoldier(npc);
        }
    }

    private static void dismiss(ServerPlayer player, BuildingRecord building, UUID npcId) {
        if (npcId == null) {
            return;
        }
        NpcData npc = NpcManager.INSTANCE.getNpc(npcId);
        if (npc == null || !building.id().toString().equals(npc.getWorkplaceId())) {
            return;
        }
        EmploymentService.INSTANCE.dismissAssignment(player.server, npcId, EmploymentKind.BUILDING);
        SoldierService.clearSoldier(npc);
    }

    private static void autoEquip(ServerPlayer player, BuildingRecord building) {
        ServerLevel level = SoldierService.levelOf(player.server, building);
        int equipped = SoldierService.autoEquip(level, building);
        player.displayClientMessage(Component.literal(equipped > 0
                ? "已从仓库发放 " + equipped + " 件装备"
                : "仓库里没有可发的装备（或士兵都已经穿好了）"), true);
    }
}
