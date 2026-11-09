package com.xy2407.kingdom.common.network.npc;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：某个市民实体已经死亡/被移除。
 * <p>
 * 客户端用它把该实体从编组、红框目标、RTS 选中里摘掉——否则这些纯客户端的缓存会一直追着一个
 * 已经不存在的实体（头像留着、点它还在跳相机）。
 */
public record NpcRemovalPacket(UUID entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NpcRemovalPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "npc_removed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcRemovalPacket> STREAM_CODEC =
            StreamCodec.of(NpcRemovalPacket::encode, NpcRemovalPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, NpcRemovalPacket packet) {
        buf.writeUUID(packet.entityId() == null ? new UUID(0L, 0L) : packet.entityId());
    }

    private static NpcRemovalPacket decode(RegistryFriendlyByteBuf buf) {
        return new NpcRemovalPacket(buf.readUUID());
    }

    public static void handle(NpcRemovalPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> NpcRosterHooks.removedSink.accept(packet.entityId()));
    }
}
