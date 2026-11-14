package com.xy2407.kingdom.common.network.wall;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：逐方块选中的结果——任务 key、节点/墙段类型与全量方块坐标
 */
public record WallPickResultPacket(String key, boolean node, List<Long> blocks)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WallPickResultPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "wall_pick_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WallPickResultPacket> STREAM_CODEC =
            StreamCodec.of(WallPickResultPacket::encode, WallPickResultPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, WallPickResultPacket packet) {
        buf.writeUtf(packet.key() == null ? "" : packet.key(), 256);
        buf.writeBoolean(packet.node());
        buf.writeVarInt(packet.blocks().size());
        for (long pos : packet.blocks()) {
            buf.writeLong(pos);
        }
    }

    private static WallPickResultPacket decode(RegistryFriendlyByteBuf buf) {
        String key = buf.readUtf(256);
        boolean node = buf.readBoolean();
        int count = Math.min(Math.max(buf.readVarInt(), 0), 65536);
        List<Long> blocks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            blocks.add(buf.readLong());
        }
        return new WallPickResultPacket(key.isEmpty() ? null : key, node, blocks);
    }

    public static void handle(WallPickResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> WallClientHooks.pickResultSink.accept(packet));
    }
}
