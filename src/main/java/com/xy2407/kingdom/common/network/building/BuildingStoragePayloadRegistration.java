package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 建筑仓库相关网络包注册点，写法沿用 {@code NpcBackpackPayloadRegistration}。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class BuildingStoragePayloadRegistration {

    private BuildingStoragePayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(BuildingStorageOpenPacket.TYPE,
                BuildingStorageOpenPacket.STREAM_CODEC, BuildingStorageOpenPacket::handle);
        /* 各建筑面板底栏的「打开仓库」共用的请求包（无参，建筑由服务端按位置判定） */
        registrar.playToServer(BuildingStorageRequestPacket.TYPE,
                BuildingStorageRequestPacket.STREAM_CODEC, BuildingStorageRequestPacket::handle);
    }
}
