package com.xy2407.kingdom.client.building;

import com.xy2407.kingdom.client.farm.FarmPanelScreen;
import com.xy2407.kingdom.client.ranch.RanchPanelScreen;
import com.xy2407.kingdom.common.network.building.BuildingPanelRequestPacket;
import com.xy2407.kingdom.common.network.building.BuildingPanelStatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建筑操作界面的客户端入口：按 Tab 请服务端判定所处建筑并分发对应面板。
 *
 * <p><b>页面由服务端在 {@code page} 里给出</b>，客户端只负责开——农业 / 工业 / 养殖 /
 * 出售铺 / 餐厅都走同一个 {@link BuildingPanelScreen}，区别只在页面路径。
 * 没有对应面板的建筑（军事、住宅）退回成一条提示。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuildingPanelClient {

    private BuildingPanelClient() {
    }

    public static void requestAtPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(new BuildingPanelRequestPacket());
    }

    public static void accept(BuildingPanelStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (packet.hasPage()) {
            /* 农业有自己那套完整面板（农田列表、框选、删除、保存），必须单独一支——
               它有专属的 Screen 与一套 action 包，交给通用宿主会把这些功能全丢掉。 */
            if (FarmPanelScreen.PAGE.equals(packet.page())) {
                minecraft.setScreen(new FarmPanelScreen());
                return;
            }
            /* 养殖同样有专属面板：蓝图 json 的配方清单、点选开跑、取出与存栏刷新都在它手里，
               交给通用宿主只会剩下"该功能尚未接入"。 */
            if (RanchPanelScreen.PAGE.equals(packet.page())) {
                minecraft.setScreen(new RanchPanelScreen());
                return;
            }
            minecraft.setScreen(new BuildingPanelScreen(packet.page(), packet.name(), packet.level(),
                    packet.pos()));
            return;
        }
        String message = packet.inside()
                ? String.format("%s  Lv.%d · 建筑操作界面尚未完成", packet.name(), packet.level())
                : "这里没有已完工的建筑";
        minecraft.player.displayClientMessage(Component.literal(message), true);
    }
}
