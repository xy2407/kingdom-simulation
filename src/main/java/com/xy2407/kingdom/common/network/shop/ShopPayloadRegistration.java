package com.xy2407.kingdom.common.network.shop;

import com.xy2407.kingdom.ModMain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 商铺网络包注册：请求、开关动作与状态回推三个方向
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class ShopPayloadRegistration {

    private ShopPayloadRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ShopPanelRequestPacket.TYPE,
                ShopPanelRequestPacket.STREAM_CODEC, ShopPanelRequestPacket::handle);
        registrar.playToServer(ShopPanelActionPacket.TYPE,
                ShopPanelActionPacket.STREAM_CODEC, ShopPanelActionPacket::handle);
        registrar.playToClient(ShopPanelStatePacket.TYPE,
                ShopPanelStatePacket.STREAM_CODEC, ShopPanelStatePacket::handle);
    }
}
