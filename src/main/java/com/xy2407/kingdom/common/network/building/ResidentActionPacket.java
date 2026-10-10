package com.xy2407.kingdom.common.network.building;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.building.ResidentService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：住宅住户的操作。建筑由服务端按玩家位置判定，客户端只说"对谁做什么"。
 */
public record ResidentActionPacket(int action, UUID workerId) implements CustomPacketPayload {

    /** 安排入住 / 让他搬走（同一人再点一次即撤销）。 */
    public static final int ACTION_TOGGLE_RESIDENT = 0;
    /** 这栋住宅的住户全部搬出。 */
    public static final int ACTION_CLEAR_RESIDENTS = 1;

    public static final CustomPacketPayload.Type<ResidentActionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "resident_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentActionPacket> STREAM_CODEC =
            StreamCodec.of(ResidentActionPacket::encode, ResidentActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static ResidentActionPacket toggleResident(UUID workerId) {
        return new ResidentActionPacket(ACTION_TOGGLE_RESIDENT, workerId);
    }

    public static ResidentActionPacket clearResidents() {
        return new ResidentActionPacket(ACTION_CLEAR_RESIDENTS, null);
    }

    public UUID workerIdOrNull() {
        if (workerId == null) {
            return null;
        }
        return workerId.getMostSignificantBits() == 0L && workerId.getLeastSignificantBits() == 0L
                ? null : workerId;
    }

    private static void encode(RegistryFriendlyByteBuf buf, ResidentActionPacket packet) {
        buf.writeVarInt(packet.action());
        buf.writeUUID(packet.workerId() == null ? new UUID(0L, 0L) : packet.workerId());
    }

    private static ResidentActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new ResidentActionPacket(buf.readVarInt(), buf.readUUID());
    }

    public static void handle(ResidentActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ResidentService.apply(player, packet);
            }
        });
    }
}
