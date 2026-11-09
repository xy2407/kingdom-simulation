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
 * 客户端 → 服务端：清除选中市民对红框敌对生物的全部仇恨
 */
public record RtsClearAggroPacket(List<UUID> npcIds) implements CustomPacketPayload {

    private static final int MAX_IDS = 512;

    public static final CustomPacketPayload.Type<RtsClearAggroPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_clear_aggro"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsClearAggroPacket> STREAM_CODEC =
            StreamCodec.of(RtsClearAggroPacket::encode, RtsClearAggroPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsClearAggroPacket packet) {
        List<UUID> list = packet.npcIds() == null ? List.of() : packet.npcIds();
        int count = Math.min(list.size(), MAX_IDS);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            buf.writeUUID(list.get(index));
        }
    }

    private static RtsClearAggroPacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_IDS);
        List<UUID> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(buf.readUUID());
        }
        return new RtsClearAggroPacket(ids);
    }

    public static void handle(RtsClearAggroPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsAttackOrderService.clearAggro(player, packet.npcIds()));
    }
}
