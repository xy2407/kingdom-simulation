package com.xy2407.kingdom.common.network.builder;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端对任务卡片的操作指令：开始、暂停或终止指定任务
 */
public record BuilderTaskActionPacket(UUID taskId, int action) implements CustomPacketPayload {

    public static final int ACTION_START = 0;
    public static final int ACTION_PAUSE = 1;
    public static final int ACTION_CANCEL = 2;

    public static final CustomPacketPayload.Type<BuilderTaskActionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "builder_task_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderTaskActionPacket> STREAM_CODEC =
            StreamCodec.of(BuilderTaskActionPacket::encode, BuilderTaskActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuilderTaskActionPacket packet) {
        buf.writeUUID(packet.taskId() == null ? new UUID(0L, 0L) : packet.taskId());
        buf.writeVarInt(packet.action());
    }

    private static BuilderTaskActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new BuilderTaskActionPacket(buf.readUUID(), buf.readVarInt());
    }

    public static void handle(BuilderTaskActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                com.xy2407.kingdom.server.task.BuilderBoardService.applyAction(player, packet);
            }
        });
    }
}
