package com.xy2407.kingdom.common.network.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsAttackOrderService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：把红框选中的敌对生物分配给选中的市民作为指定目标
 */
public record RtsAttackOrderPacket(List<UUID> npcIds, List<UUID> targetIds)
        implements CustomPacketPayload {

    private static final int MAX_IDS = 512;

    public static final CustomPacketPayload.Type<RtsAttackOrderPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_attack_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsAttackOrderPacket> STREAM_CODEC =
            StreamCodec.of(RtsAttackOrderPacket::encode, RtsAttackOrderPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsAttackOrderPacket packet) {
        writeIds(buf, packet.npcIds());
        writeIds(buf, packet.targetIds());
    }

    private static RtsAttackOrderPacket decode(RegistryFriendlyByteBuf buf) {
        return new RtsAttackOrderPacket(readIds(buf), readIds(buf));
    }

    private static void writeIds(RegistryFriendlyByteBuf buf, List<UUID> ids) {
        List<UUID> list = ids == null ? List.of() : ids;
        int count = Math.min(list.size(), MAX_IDS);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            buf.writeUUID(list.get(index));
        }
    }

    private static List<UUID> readIds(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_IDS);
        List<UUID> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(buf.readUUID());
        }
        return ids;
    }

    public static void handle(RtsAttackOrderPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsAttackOrderService.assign(player, packet.npcIds(), packet.targetIds()));
    }
}
