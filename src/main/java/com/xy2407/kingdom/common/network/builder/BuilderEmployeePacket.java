package com.xy2407.kingdom.common.network.builder;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 雇佣解雇指令：把空闲市民收入建筑工队或从工队除名
 */
public record BuilderEmployeePacket(UUID npcId, boolean hire) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuilderEmployeePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "builder_employee"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderEmployeePacket> STREAM_CODEC =
            StreamCodec.of(BuilderEmployeePacket::encode, BuilderEmployeePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuilderEmployeePacket packet) {
        buf.writeUUID(packet.npcId() == null ? new UUID(0L, 0L) : packet.npcId());
        buf.writeBoolean(packet.hire());
    }

    private static BuilderEmployeePacket decode(RegistryFriendlyByteBuf buf) {
        return new BuilderEmployeePacket(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(BuilderEmployeePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                com.xy2407.kingdom.server.task.BuilderBoardService.applyEmployee(player, packet);
            }
        });
    }
}
