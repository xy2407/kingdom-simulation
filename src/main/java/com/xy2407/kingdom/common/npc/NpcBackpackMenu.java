package com.xy2407.kingdom.common.npc;

import com.sighs.apricityui.network.handler.PendingMenu;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.network.npc.NpcBackpackOpenPacket;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 打开市民行装界面：先发行装展示数据，再用 AUI 容器菜单绑上实体与玩家两个容器。
 */
public final class NpcBackpackMenu {

    public static final String PAGE = "ui/backpack.html";

    private NpcBackpackMenu() {
    }

    public static void open(ServerPlayer player, NpcEntity npc) {
        NpcData data = npc.getCitizenId() == null ? null : NpcManager.INSTANCE.getNpc(npc.getCitizenId());
        PacketDistributor.sendToPlayer(player, buildPacket(npc, data));
        new PendingMenu(player, PAGE).bind(binding -> binding
                .entity(npc.getId(), NpcInventory.SLOT_COUNT)
                .player());
    }

    private static NpcBackpackOpenPacket buildPacket(NpcEntity npc, NpcData data) {
        if (data == null) {
            return new NpcBackpackOpenPacket(npc.getId(), "", npc.getName().getString(), "", "", "", "", "",
                    "", "", 100.0D, 100.0D, 0.0D, 100.0D, "");
        }
        return new NpcBackpackOpenPacket(
                npc.getId(),
                citizenNo(npc),
                data.getName(),
                data.getJob(),
                data.getGender(),
                String.valueOf(data.getAge()),
                data.getPersonality(),
                data.getLifecycle(),
                data.getNation(),
                String.valueOf(data.getWealth()),
                data.getHealth(),
                100.0D,
                data.getHunger(),
                NpcData.HUNGER_MAX,
                description(data));
    }

    private static String citizenNo(NpcEntity npc) {
        String raw = npc.getCitizenId() == null ? "" : npc.getCitizenId().toString().replace("-", "");
        return raw.isEmpty() ? "" : raw.substring(0, 4).toUpperCase();
    }

    private static String description(NpcData data) {
        String hobbies = join(data.getHobbies());
        String liked = join(data.getLikedItems());
        if (hobbies.isEmpty() && liked.isEmpty()) {
            return data.getPersonality();
        }
        return "爱好 · " + (hobbies.isEmpty() ? "—" : hobbies) + "　　喜好 · " + (liked.isEmpty() ? "—" : liked);
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("、", values);
    }
}
