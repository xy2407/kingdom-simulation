package com.xy2407.kingdom.common.network.faction;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 阵营与领地网络包注册：状态包与选国、立领地两个上行包。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class FactionPayloadRegistration {

    private FactionPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(FactionStatePacket.TYPE,
                FactionStatePacket.STREAM_CODEC, FactionStatePacket::handle);
        registrar.playToServer(FactionSelectPacket.TYPE,
                FactionSelectPacket.STREAM_CODEC, FactionSelectPacket::handle);
        registrar.playToServer(TerritoryCreatePacket.TYPE,
                TerritoryCreatePacket.STREAM_CODEC, TerritoryCreatePacket::handle);
        registrar.playToServer(TerritoryBuyPacket.TYPE,
                TerritoryBuyPacket.STREAM_CODEC, TerritoryBuyPacket::handle);
    }
}
