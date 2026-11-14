package com.xy2407.kingdom.common.network.wall;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.wall.WallKind;
import com.xy2407.kingdom.server.wall.WallService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：Enter 确认后提交的城墙节点清单，服务端据此落盘并生成墙段任务
 */
public record WallSubmitPacket(List<Entry> nodes) implements CustomPacketPayload {

    private static final int MAX_NODES = 64;

    public record Entry(WallKind kind, int x, int y, int z, int rotation) {
    }

    public static final CustomPacketPayload.Type<WallSubmitPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "wall_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WallSubmitPacket> STREAM_CODEC =
            StreamCodec.of(WallSubmitPacket::encode, WallSubmitPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, WallSubmitPacket packet) {
        int count = Math.min(packet.nodes().size(), MAX_NODES);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            Entry entry = packet.nodes().get(index);
            buf.writeByte(entry.kind().ordinal());
            buf.writeInt(entry.x());
            buf.writeInt(entry.y());
            buf.writeInt(entry.z());
            buf.writeByte(entry.rotation());
        }
    }

    private static WallSubmitPacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_NODES);
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int kind = buf.readByte();
            WallKind[] values = WallKind.values();
            WallKind wallKind = values[Math.max(0, Math.min(values.length - 1, kind))];
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            int rotation = buf.readByte();
            entries.add(new Entry(wallKind, x, y, z, rotation));
        }
        return new WallSubmitPacket(entries);
    }

    public static void handle(WallSubmitPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                WallService.submit(player, packet.nodes());
            }
        });
    }
}
