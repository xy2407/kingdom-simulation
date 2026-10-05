package com.xy2407.kingdom.client.faction;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.cityhall.CityHallScreen;
import com.xy2407.kingdom.common.network.faction.FactionHooks;
import com.xy2407.kingdom.common.network.faction.FactionStatePacket;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端收到阵营状态后决定弹哪张界面、关哪张界面。
 *
 * <p>{@link FactionStatePacket} 一个包承担三种意图，分派全在这里：</p>
 * <ul>
 *   <li>{@code openCityCreate} → 开建城界面（右键市政厅但还没领地）；</li>
 *   <li>{@code !onboarded} → 开择旗界面（首登）；</li>
 *   <li>其余 → 只更新缓存；若刚做完某个流程，顺手把对应界面关掉。</li>
 * </ul>
 *
 * <p>关界面的判断放在这里而不是各处界面里：每个界面只知道自己开没开，
 * 不知道"服务端已经把这件事办成了"，由状态包统一收口最不容易漏。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FactionClientState {

    /* 空快照：全部中性值。构造器参数与 FactionStatePacket 的记录组件一一对应，
       加字段时这里、工厂方法、编解码三处都要跟着改。 */
    private static volatile FactionStatePacket snapshot = new FactionStatePacket(
            false, "", "", "", false, 0, 0, 0, java.util.List.of());

    private FactionClientState() {
    }

    /** 在客户端 setup 阶段把钩子指过来。 */
    public static void install() {
        FactionHooks.stateSink = FactionClientState::accept;
    }

    public static FactionStatePacket snapshot() {
        return snapshot;
    }

    public static void accept(FactionStatePacket packet) {
        if (packet == null) {
            return;
        }
        snapshot = packet;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        ModMain.LOGGER.info("[市政厅] 客户端收到状态包 onboarded={} openCityCreate={} 领地={}",
                packet.onboarded(), packet.openCityCreate(),
                packet.territoryId().isBlank() ? "无" : packet.territoryId());

        if (packet.openCityCreate()) {
            CityCreateScreen.open(packet.hallX(), packet.hallY(), packet.hallZ());
            return;
        }

        if (!packet.onboarded()) {
            /* 玩家正开着市政厅面板时**不许顶掉它**：这个状态包由 FactionService.tick()
               每 tick 驱动，只要没 onboarded 就一直发；若照旧弹择旗，就会出现
               "右键市政厅、面板闪一下就被择旗界面盖住"——看着就是市政厅打不开。
               已经在择旗界面里也跳过，免得把玩家选的卡刷掉。 */
            if (minecraft.screen instanceof CityHallScreen) {
                return;
            }
            if (!(minecraft.screen instanceof FactionSelectScreen)) {
                FactionSelectPage.open(null);
            }
            return;
        }

        /* 已 onboarded：择旗流程走完了，把界面收掉 */
        if (minecraft.screen instanceof FactionSelectScreen) {
            minecraft.setScreen(null);
            return;
        }
        /* 领地建好了，把建城界面也收掉 */
        if (minecraft.screen instanceof CityCreateScreen && !packet.territoryId().isBlank()) {
            minecraft.setScreen(null);
        }
    }
}
