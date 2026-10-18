package com.xy2407.kingdom.common.network.builder;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 区域任务创建包：玩家框选确认后提交填充或拆除范围
 */
public record RegionTaskCreatePacket(int kind, int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ) implements CustomPacketPayload {

    public static final int KIND_FILL = 0;
    public static final int KIND_DEMOLISH = 1;

    public static final CustomPacketPayload.Type<RegionTaskCreatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "region_task_create"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RegionTaskCreatePacket> STREAM_CODEC =
            StreamCodec.of(RegionTaskCreatePacket::encode, RegionTaskCreatePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RegionTaskCreatePacket packet) {
        buf.writeVarInt(packet.kind());
        buf.writeInt(packet.minX());
        buf.writeInt(packet.minY());
        buf.writeInt(packet.minZ());
        buf.writeInt(packet.maxX());
        buf.writeInt(packet.maxY());
        buf.writeInt(packet.maxZ());
    }

    private static RegionTaskCreatePacket decode(RegistryFriendlyByteBuf buf) {
        return new RegionTaskCreatePacket(buf.readVarInt(),
                buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(RegionTaskCreatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player
                    && context.player().level() instanceof net.minecraft.server.level.ServerLevel level) {
                com.xy2407.kingdom.server.task.RegionTaskService.INSTANCE.create(level, player, packet);
            }
        });
    }
}
