package com.xy2407.kingdom.common.network.builder;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 头像拖拽指派包：把雇员加入或移出指定区域任务
 */
public record BuilderAssignPacket(UUID taskId, UUID npcId, boolean add) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuilderAssignPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "builder_assign"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderAssignPacket> STREAM_CODEC =
            StreamCodec.of(BuilderAssignPacket::encode, BuilderAssignPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuilderAssignPacket packet) {
        buf.writeUUID(packet.taskId() == null ? new UUID(0L, 0L) : packet.taskId());
        buf.writeUUID(packet.npcId() == null ? new UUID(0L, 0L) : packet.npcId());
        buf.writeBoolean(packet.add());
    }

    private static BuilderAssignPacket decode(RegistryFriendlyByteBuf buf) {
        return new BuilderAssignPacket(buf.readUUID(), buf.readUUID(), buf.readBoolean());
    }

    public static void handle(BuilderAssignPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                com.xy2407.kingdom.server.task.BuilderBoardService.applyAssign(player, packet);
            }
        });
    }
}
