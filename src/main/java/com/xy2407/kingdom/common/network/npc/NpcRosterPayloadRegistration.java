package com.xy2407.kingdom.common.network.npc;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 市民名册网络包的注册点，写法沿用 NpcBackpackPayloadRegistration。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class NpcRosterPayloadRegistration {

    private NpcRosterPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(NpcRosterRequestPacket.TYPE,
                NpcRosterRequestPacket.STREAM_CODEC, NpcRosterRequestPacket::handle);
        registrar.playToClient(NpcRosterPacket.TYPE,
                NpcRosterPacket.STREAM_CODEC, NpcRosterPacket::handle);
        registrar.playToClient(NpcRemovalPacket.TYPE,
                NpcRemovalPacket.STREAM_CODEC, NpcRemovalPacket::handle);
    }
}
