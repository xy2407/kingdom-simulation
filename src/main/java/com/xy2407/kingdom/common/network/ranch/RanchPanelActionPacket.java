package com.xy2407.kingdom.common.network.ranch;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.ranch.RanchPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：一次养殖操作，行为按 action 区分，作用对象是某一栏
 */
public record RanchPanelActionPacket(int action, UUID penId, String text) implements CustomPacketPayload {

    public static final int ACTION_TOGGLE_RUN = 0;
    public static final int ACTION_SET_KEEPER = 1;
    public static final int ACTION_CLEAR_KEEPERS = 2;

    public static final CustomPacketPayload.Type<RanchPanelActionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "ranch_panel_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RanchPanelActionPacket> STREAM_CODEC =
            StreamCodec.of(RanchPanelActionPacket::encode, RanchPanelActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static RanchPanelActionPacket toggleRun(UUID penId) {
        return new RanchPanelActionPacket(ACTION_TOGGLE_RUN, penId, "");
    }

    public static RanchPanelActionPacket toggleKeeper(UUID penId, UUID workerId) {
        return new RanchPanelActionPacket(ACTION_SET_KEEPER, penId,
                workerId == null ? "" : workerId.toString());
    }

    public static RanchPanelActionPacket clearKeepers(UUID penId) {
        return new RanchPanelActionPacket(ACTION_CLEAR_KEEPERS, penId, "");
    }

    public UUID penIdOrNull() {
        return penId == null || (penId.getMostSignificantBits() == 0L && penId.getLeastSignificantBits() == 0L)
                ? null : penId;
    }

    public UUID workerIdOrNull() {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf, RanchPanelActionPacket packet) {
        buf.writeVarInt(packet.action());
        buf.writeUUID(packet.penId() == null ? new UUID(0L, 0L) : packet.penId());
        buf.writeUtf(packet.text() == null ? "" : packet.text(), 64);
    }

    private static RanchPanelActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new RanchPanelActionPacket(buf.readVarInt(), buf.readUUID(), buf.readUtf(64));
    }

    public static void handle(RanchPanelActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RanchPanelService.apply(player, packet);
            }
        });
    }
}
