package com.xy2407.kingdom.common.npc;

import com.sighs.apricityui.network.handler.PendingMenu;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.network.npc.NpcBackpackOpenPacket;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 打开市民行装界面（服务端入口）。
 *
 * <p>两件事缺一不可：</p>
 * <ol>
 *   <li>把展示数据发给客户端（谁、档案、状态、左侧模型渲染哪个实体）；</li>
 *   <li>用 AUI 的 {@link PendingMenu} 打开容器屏——只发 {@code screen(path)} 是"长得像容器的
 *       UI"，槽位没有真实数据源，点不动也拿不出东西。</li>
 * </ol>
 *
 * <p>容器 id 必须与页面里的 {@code <container id="entity">} 一致：{@code entity} 对应
 * {@code binding.entity(...)}，{@code player} 对应 {@code binding.player()}。先绑实体再绑玩家，
 * 于是实体容器自动成为 primary——shift-click 从玩家背包优先塞进行装。</p>
 */
public final class NpcBackpackMenu {

    /** 页面路径，相对 {@code assets/apricityui/apricity/}。 */
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

    /** 档案编号：取市民 UUID 前 4 位，稳定且不必额外存字段。 */
    private static String citizenNo(NpcEntity npc) {
        String raw = npc.getCitizenId() == null ? "" : npc.getCitizenId().toString().replace("-", "");
        return raw.isEmpty() ? "" : raw.substring(0, 4).toUpperCase();
    }

    /** 简介行：用"爱好/喜好"这类列表字段拼一行，避免写死文案。 */
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
