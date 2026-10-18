package com.xy2407.kingdom.common.network.builder;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 建筑工面板全部网络包的注册入口，统一版本号 1
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class BuilderPayloadRegistration {

    private BuilderPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(BuilderOpenPacket.TYPE,
                BuilderOpenPacket.STREAM_CODEC, BuilderOpenPacket::handle);
        registrar.playToServer(BuilderBoardRequestPacket.TYPE,
                BuilderBoardRequestPacket.STREAM_CODEC, BuilderBoardRequestPacket::handle);
        registrar.playToClient(BuilderBoardDataPacket.TYPE,
                BuilderBoardDataPacket.STREAM_CODEC, BuilderBoardDataPacket::handle);
        registrar.playToServer(BuilderTaskActionPacket.TYPE,
                BuilderTaskActionPacket.STREAM_CODEC, BuilderTaskActionPacket::handle);
        registrar.playToServer(BuilderAssignPacket.TYPE,
                BuilderAssignPacket.STREAM_CODEC, BuilderAssignPacket::handle);
        registrar.playToServer(BuilderEmployeePacket.TYPE,
                BuilderEmployeePacket.STREAM_CODEC, BuilderEmployeePacket::handle);
        registrar.playToServer(RegionTaskCreatePacket.TYPE,
                RegionTaskCreatePacket.STREAM_CODEC, RegionTaskCreatePacket::handle);
    }
}
