package com.xy2407.kingdom.common.network.wall;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.wall.WallKind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：所属城墙节点与墙段快照，供高亮选中与删除按钮使用
 */
public record WallStatePacket(List<NodeView> nodes, List<SegmentView> segments)
        implements CustomPacketPayload {

    public record NodeView(String key, WallKind kind, int x, int y, int z, int rotation,
                           String status, int progress, int total) {
    }

    public record SegmentView(String key, int ax, int ay, int az, int bx, int by, int bz,
                              String status, int progress, int total) {
    }

    public static final CustomPacketPayload.Type<WallStatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "wall_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WallStatePacket> STREAM_CODEC =
            StreamCodec.of(WallStatePacket::encode, WallStatePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, WallStatePacket packet) {
        buf.writeVarInt(packet.nodes().size());
        for (NodeView node : packet.nodes()) {
            buf.writeUtf(node.key(), 256);
            buf.writeByte(node.kind().ordinal());
            buf.writeInt(node.x());
            buf.writeInt(node.y());
            buf.writeInt(node.z());
            buf.writeByte(node.rotation());
            buf.writeUtf(node.status(), 16);
            buf.writeVarInt(node.progress());
            buf.writeVarInt(node.total());
        }
        buf.writeVarInt(packet.segments().size());
        for (SegmentView segment : packet.segments()) {
            buf.writeUtf(segment.key(), 256);
            buf.writeInt(segment.ax());
            buf.writeInt(segment.ay());
            buf.writeInt(segment.az());
            buf.writeInt(segment.bx());
            buf.writeInt(segment.by());
            buf.writeInt(segment.bz());
            buf.writeUtf(segment.status(), 16);
            buf.writeVarInt(segment.progress());
            buf.writeVarInt(segment.total());
        }
    }

    private static WallStatePacket decode(RegistryFriendlyByteBuf buf) {
        int nodeCount = Math.min(Math.max(buf.readVarInt(), 0), 4096);
        List<NodeView> nodes = new ArrayList<>(nodeCount);
        WallKind[] kinds = WallKind.values();
        for (int index = 0; index < nodeCount; index++) {
            String key = buf.readUtf(256);
            int kind = buf.readByte();
            WallKind wallKind = kinds[Math.max(0, Math.min(kinds.length - 1, kind))];
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            int rotation = buf.readByte();
            String status = buf.readUtf(16);
            int progress = buf.readVarInt();
            int total = buf.readVarInt();
            nodes.add(new NodeView(key, wallKind, x, y, z, rotation, status, progress, total));
        }
        int segmentCount = Math.min(Math.max(buf.readVarInt(), 0), 8192);
        List<SegmentView> segments = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            segments.add(new SegmentView(buf.readUtf(256),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readUtf(16), buf.readVarInt(), buf.readVarInt()));
        }
        return new WallStatePacket(nodes, segments);
    }

    public static void handle(WallStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> WallClientHooks.stateSink.accept(packet));
    }
}
