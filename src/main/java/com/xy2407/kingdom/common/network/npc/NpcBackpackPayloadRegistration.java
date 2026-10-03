package com.xy2407.kingdom.common.network.npc;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 行装相关网络包注册点，写法沿用 {@code RtsPayloadRegistration}。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class NpcBackpackPayloadRegistration {

    private NpcBackpackPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(NpcBackpackOpenPacket.TYPE,
                NpcBackpackOpenPacket.STREAM_CODEC, NpcBackpackOpenPacket::handle);
    }
}
