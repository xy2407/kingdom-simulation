package com.xy2407.kingdom.common.network.db;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 数据库查看器网络包注册，绑定请求与快照两个方向
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class DbPayloadRegistration {

    private DbPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(DbViewerRequestPacket.TYPE,
                DbViewerRequestPacket.STREAM_CODEC, DbViewerRequestPacket::handle);
        registrar.playToClient(DbViewerDataPacket.TYPE,
                DbViewerDataPacket.STREAM_CODEC, DbViewerDataPacket::handle);
    }
}
