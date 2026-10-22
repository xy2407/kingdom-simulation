package com.xy2407.kingdom.common.network.mining;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.mining.MiningPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：矿坑派工、暂停恢复与删除动作。
 */
public record MiningPanelActionPacket(int action, UUID plotId, String workerId) implements CustomPacketPayload {

    public static final int ACTION_TOGGLE_WORKER = 0;

    public static final int ACTION_DELETE = 1;

    public static final int ACTION_PAUSE = 2;

    public static final int ACTION_RESUME = 3;

    public static final CustomPacketPayload.Type<MiningPanelActionPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "mining_panel_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MiningPanelActionPacket> STREAM_CODEC =
            StreamCodec.of(MiningPanelActionPacket::encode, MiningPanelActionPacket::decode);

    public static MiningPanelActionPacket toggleWorker(UUID plotId, UUID workerId) {
        return new MiningPanelActionPacket(ACTION_TOGGLE_WORKER, plotId,
                workerId == null ? "" : workerId.toString());
    }

    public static MiningPanelActionPacket delete(UUID plotId) {
        return new MiningPanelActionPacket(ACTION_DELETE, plotId, "");
    }

    public static MiningPanelActionPacket pause(UUID plotId) {
        return new MiningPanelActionPacket(ACTION_PAUSE, plotId, "");
    }

    public static MiningPanelActionPacket resume(UUID plotId) {
        return new MiningPanelActionPacket(ACTION_RESUME, plotId, "");
    }

    public UUID plotIdOrNull() {
        return plotId;
    }

    public UUID workerIdOrNull() {
        if (workerId == null || workerId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(workerId.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, MiningPanelActionPacket packet) {
        buf.writeVarInt(packet.action());
        buf.writeBoolean(packet.plotId() != null);
        if (packet.plotId() != null) {
            buf.writeUUID(packet.plotId());
        }
        buf.writeUtf(packet.workerId() == null ? "" : packet.workerId(), 64);
    }

    private static MiningPanelActionPacket decode(RegistryFriendlyByteBuf buf) {
        int action = buf.readVarInt();
        UUID plotId = buf.readBoolean() ? buf.readUUID() : null;
        String workerId = buf.readUtf(64);
        return new MiningPanelActionPacket(action, plotId, workerId);
    }

    public static void handle(MiningPanelActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MiningPanelService.apply(player, packet);
            }
        });
    }
}
