package com.xy2407.kingdom.common.network.farm;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.farm.FarmPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：一次农田操作，用一个包按 action 区分，字段按对应构造方法取用
 */
public record FarmPanelActionPacket(int action, UUID plotId, String text, int value,
                                    int minX, int minZ, int maxX, int maxZ) implements CustomPacketPayload {

    public static final int ACTION_CREATE = 0;
    public static final int ACTION_SET_SLOTS = 1;
    public static final int ACTION_SET_CROP = 2;
    public static final int ACTION_TOGGLE_WORKER = 3;
    public static final int ACTION_SET_AREA = 4;
    public static final int ACTION_DELETE = 5;

    public static final CustomPacketPayload.Type<FarmPanelActionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "farm_panel_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FarmPanelActionPacket> STREAM_CODEC =
            StreamCodec.of(FarmPanelActionPacket::encode, FarmPanelActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static FarmPanelActionPacket create(String name, int slots) {
        return new FarmPanelActionPacket(ACTION_CREATE, null, name == null ? "" : name, slots, 0, 0, 0, 0);
    }

    public static FarmPanelActionPacket setSlots(UUID plotId, int slots) {
        return new FarmPanelActionPacket(ACTION_SET_SLOTS, plotId, "", slots, 0, 0, 0, 0);
    }

    public static FarmPanelActionPacket setCrop(UUID plotId, String cropId) {
        return new FarmPanelActionPacket(ACTION_SET_CROP, plotId, cropId == null ? "" : cropId, 0, 0, 0, 0, 0);
    }

    public static FarmPanelActionPacket toggleWorker(UUID plotId, UUID workerId) {
        return new FarmPanelActionPacket(ACTION_TOGGLE_WORKER, plotId,
                workerId == null ? "" : workerId.toString(), 0, 0, 0, 0, 0);
    }

    public static FarmPanelActionPacket setArea(UUID plotId, int minX, int minZ, int maxX, int maxZ) {
        return new FarmPanelActionPacket(ACTION_SET_AREA, plotId, "", 0, minX, minZ, maxX, maxZ);
    }

    public static FarmPanelActionPacket delete(UUID plotId) {
        return new FarmPanelActionPacket(ACTION_DELETE, plotId, "", 0, 0, 0, 0, 0);
    }

    private static void encode(RegistryFriendlyByteBuf buf, FarmPanelActionPacket packet) {
        buf.writeVarInt(packet.action());
        buf.writeUUID(packet.plotId() == null ? new UUID(0L, 0L) : packet.plotId());
        buf.writeUtf(packet.text() == null ? "" : packet.text(), 128);
        buf.writeInt(packet.value());
        buf.writeInt(packet.minX());
        buf.writeInt(packet.minZ());
        buf.writeInt(packet.maxX());
        buf.writeInt(packet.maxZ());
    }

    private static FarmPanelActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new FarmPanelActionPacket(buf.readVarInt(), buf.readUUID(), buf.readUtf(128), buf.readInt(),
                buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
    }

    public UUID plotIdOrNull() {
        return plotId == null || (plotId.getMostSignificantBits() == 0L && plotId.getLeastSignificantBits() == 0L)
                ? null : plotId;
    }

    public static void handle(FarmPanelActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                FarmPanelService.apply(player, packet);
            }
        });
    }
}
