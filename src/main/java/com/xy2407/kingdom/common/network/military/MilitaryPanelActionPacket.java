package com.xy2407.kingdom.common.network.military;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.military.MilitaryPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：军事面板上的操作（征召 / 遣散 / 一键穿戴）。
 */
public record MilitaryPanelActionPacket(int action, UUID npcId) implements CustomPacketPayload {

    public static final int ACTION_HIRE = 1;
    public static final int ACTION_DISMISS = 2;
    public static final int ACTION_AUTO_EQUIP = 3;

    public static final CustomPacketPayload.Type<MilitaryPanelActionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "military_panel_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MilitaryPanelActionPacket> STREAM_CODEC =
            StreamCodec.of(MilitaryPanelActionPacket::encode, MilitaryPanelActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static MilitaryPanelActionPacket hire(UUID npcId) {
        return new MilitaryPanelActionPacket(ACTION_HIRE, npcId);
    }

    public static MilitaryPanelActionPacket dismiss(UUID npcId) {
        return new MilitaryPanelActionPacket(ACTION_DISMISS, npcId);
    }

    public static MilitaryPanelActionPacket autoEquip() {
        return new MilitaryPanelActionPacket(ACTION_AUTO_EQUIP, null);
    }

    public UUID npcIdOrNull() {
        if (npcId == null) {
            return null;
        }
        return npcId.getMostSignificantBits() == 0L && npcId.getLeastSignificantBits() == 0L
                ? null : npcId;
    }

    private static void encode(RegistryFriendlyByteBuf buf, MilitaryPanelActionPacket packet) {
        buf.writeVarInt(packet.action());
        buf.writeUUID(packet.npcId() == null ? new UUID(0L, 0L) : packet.npcId());
    }

    private static MilitaryPanelActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new MilitaryPanelActionPacket(buf.readVarInt(), buf.readUUID());
    }

    public static void handle(MilitaryPanelActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MilitaryPanelService.apply(player, packet);
            }
        });
    }
}
