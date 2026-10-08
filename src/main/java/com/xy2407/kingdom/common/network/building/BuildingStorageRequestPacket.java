package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.building.BuildingStorageMenu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请打开当前所处建筑的仓库，建筑由服务端按玩家位置判定。
 */
public record BuildingStorageRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingStorageRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_storage_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingStorageRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new BuildingStorageRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuildingStorageRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                BuildingStorageMenu.openFor(player);
            }
        });
    }
}
