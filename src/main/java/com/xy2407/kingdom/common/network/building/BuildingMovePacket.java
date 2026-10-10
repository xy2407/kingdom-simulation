package com.xy2407.kingdom.common.network.building;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.building.BuildingMoveService;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：把某栋建筑整体平移到新落点。
 * 落点由客户端按投影位置给出（同 {@code BuildingPlaceConfirmPacket} 的口径），
 * 服务端只做"同维度 + 体积"这类基本校验 —— 本模组目前没有领地限制。
 */
public record BuildingMovePacket(UUID buildingId, BlockPos newMin) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingMovePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_move"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingMovePacket> STREAM_CODEC =
            StreamCodec.of(BuildingMovePacket::encode, BuildingMovePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingMovePacket packet) {
        buf.writeUUID(packet.buildingId());
        buf.writeBlockPos(packet.newMin());
    }

    private static BuildingMovePacket decode(RegistryFriendlyByteBuf buf) {
        return new BuildingMovePacket(buf.readUUID(), buf.readBlockPos());
    }

    public static void handle(BuildingMovePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                BuildingMoveService.move(player.serverLevel(), player, packet.buildingId(), packet.newMin());
            }
        });
    }
}
