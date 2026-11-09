package com.xy2407.kingdom.common.network.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：NPC 本次自定义寻路算出的航点与动作类型，供调试渲染
 */
public record NpcPathDebugPacket(UUID npcId, List<Entry> entries) implements CustomPacketPayload {

    private static final int MAX_ENTRIES = 4096;

    public static final CustomPacketPayload.Type<NpcPathDebugPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "npc_path_debug"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcPathDebugPacket> STREAM_CODEC =
            StreamCodec.of(NpcPathDebugPacket::encode, NpcPathDebugPacket::decode);

    public record Entry(BlockPos pos, byte move, int index) {
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, NpcPathDebugPacket packet) {
        buf.writeUUID(packet.npcId() == null ? new UUID(0L, 0L) : packet.npcId());
        List<Entry> entries = packet.entries() == null ? List.of() : packet.entries();
        int count = Math.min(entries.size(), MAX_ENTRIES);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            Entry entry = entries.get(index);
            buf.writeBlockPos(entry.pos());
            buf.writeByte(entry.move());
            buf.writeVarInt(entry.index());
        }
    }

    private static NpcPathDebugPacket decode(RegistryFriendlyByteBuf buf) {
        UUID npcId = buf.readUUID();
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(buf.readBlockPos(), buf.readByte(), buf.readVarInt()));
        }
        return new NpcPathDebugPacket(npcId, entries);
    }

    public static void handle(NpcPathDebugPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> NpcPathDebugHooks.sink.accept(packet));
    }
}
