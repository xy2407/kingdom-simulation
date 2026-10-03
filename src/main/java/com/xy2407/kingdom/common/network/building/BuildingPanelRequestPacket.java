package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求判定当前所处的建筑。
 *
 * <p>空载荷——玩家位置由服务端从 {@code context.player()} 直接取，客户端不必也不该传坐标。</p>
 */
public record BuildingPanelRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingPanelRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_panel_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPanelRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new BuildingPanelRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuildingPanelRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuildingPanelService.respond(context));
    }
}
