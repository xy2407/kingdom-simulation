package com.xy2407.kingdom.common.network.building;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.building.BuildingConstructionService;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端确认放置的建筑指令，服务端登记任务并随机指派建筑师
 */
public record BuildingPlaceConfirmPacket(String blueprintName, BlockPos origin, int rotation) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingPlaceConfirmPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_place_confirm"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPlaceConfirmPacket> STREAM_CODEC =
            StreamCodec.of(BuildingPlaceConfirmPacket::encode, BuildingPlaceConfirmPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingPlaceConfirmPacket packet) {
        buf.writeUtf(packet.blueprintName() == null ? "" : packet.blueprintName(), 256);
        BlockPos origin = packet.origin();
        buf.writeInt(origin == null ? 0 : origin.getX());
        buf.writeInt(origin == null ? 0 : origin.getY());
        buf.writeInt(origin == null ? 0 : origin.getZ());
        buf.writeInt(packet.rotation());
    }

    private static BuildingPlaceConfirmPacket decode(RegistryFriendlyByteBuf buf) {
        String name = buf.readUtf(256);
        BlockPos origin = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        return new BuildingPlaceConfirmPacket(name, origin, buf.readInt());
    }

    public static void handle(BuildingPlaceConfirmPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        context.enqueueWork(() -> BuildingConstructionService.INSTANCE.start(level, packet.blueprintName(),
                packet.origin(), packet.rotation()));
    }
}
