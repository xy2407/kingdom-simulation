package com.xy2407.kingdom.client.faction;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.cityhall.CityHallScreen;
import com.xy2407.kingdom.common.network.faction.FactionHooks;
import com.xy2407.kingdom.common.network.faction.FactionStatePacket;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端收到阵营状态包后决定弹哪张界面、关哪张界面。
 */
@OnlyIn(Dist.CLIENT)
public final class FactionClientState {

    private static volatile FactionStatePacket snapshot = new FactionStatePacket(
            false, "", "", "", false, 0, 0, 0, java.util.List.of());

    private FactionClientState() {
    }

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
            if (minecraft.screen instanceof CityHallScreen) {
                return;
            }
            if (!(minecraft.screen instanceof FactionSelectScreen)) {
                FactionSelectPage.open(null);
            }
            return;
        }

        if (minecraft.screen instanceof FactionSelectScreen) {
            minecraft.setScreen(null);
            return;
        }
        if (minecraft.screen instanceof CityCreateScreen && !packet.territoryId().isBlank()) {
            minecraft.setScreen(null);
        }
    }
}
