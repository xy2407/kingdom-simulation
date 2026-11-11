package com.xy2407.kingdom.common.network.industry;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.industry.IndustryStaffService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 工业用工面板操作包，上报招聘解雇与把人分到或撤回作业点
 */
public record IndustryStaffActionPacket(int action, UUID npcId, UUID plotId)
        implements CustomPacketPayload {

    public static final int ACTION_HIRE = 1;
    public static final int ACTION_FIRE = 2;
    public static final int ACTION_ASSIGN = 3;
    public static final int ACTION_UNASSIGN = 4;

    public static final CustomPacketPayload.Type<IndustryStaffActionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "industry_staff_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, IndustryStaffActionPacket> STREAM_CODEC =
            StreamCodec.of(IndustryStaffActionPacket::encode, IndustryStaffActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static IndustryStaffActionPacket hire(UUID npcId) {
        return new IndustryStaffActionPacket(ACTION_HIRE, npcId, null);
    }

    public static IndustryStaffActionPacket fire(UUID npcId) {
        return new IndustryStaffActionPacket(ACTION_FIRE, npcId, null);
    }

    public static IndustryStaffActionPacket assign(UUID npcId, UUID plotId) {
        return new IndustryStaffActionPacket(ACTION_ASSIGN, npcId, plotId);
    }

    public static IndustryStaffActionPacket unassign(UUID npcId) {
        return new IndustryStaffActionPacket(ACTION_UNASSIGN, npcId, null);
    }

    public UUID npcIdOrNull() {
        return emptyToNull(npcId);
    }

    public UUID plotIdOrNull() {
        return emptyToNull(plotId);
    }

    private static UUID emptyToNull(UUID id) {
        if (id == null) {
            return null;
        }
        return id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L ? null : id;
    }

    private static void encode(RegistryFriendlyByteBuf buf, IndustryStaffActionPacket packet) {
        buf.writeVarInt(packet.action());
        buf.writeUUID(packet.npcId() == null ? new UUID(0L, 0L) : packet.npcId());
        buf.writeUUID(packet.plotId() == null ? new UUID(0L, 0L) : packet.plotId());
    }

    private static IndustryStaffActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new IndustryStaffActionPacket(buf.readVarInt(), buf.readUUID(), buf.readUUID());
    }

    public static void handle(IndustryStaffActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                IndustryStaffService.apply(player, packet);
            }
        });
    }
}
