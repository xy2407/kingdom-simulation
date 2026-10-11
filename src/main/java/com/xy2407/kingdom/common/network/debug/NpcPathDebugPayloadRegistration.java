package com.xy2407.kingdom.common.network.debug;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 注册 NPC 自定义寻路调试航点的客户端接收包
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class NpcPathDebugPayloadRegistration {

    private NpcPathDebugPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(NpcPathDebugPacket.TYPE,
                NpcPathDebugPacket.STREAM_CODEC, NpcPathDebugPacket::handle);
    }
}
