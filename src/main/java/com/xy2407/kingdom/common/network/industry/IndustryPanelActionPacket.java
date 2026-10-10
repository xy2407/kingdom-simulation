package com.xy2407.kingdom.common.network.industry;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.industry.IndustryPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：工业面板的操作。
 * 配方用蓝图 json 里的 id 标识（不是下标），所以蓝图改了配方顺序也不会串岗。
 */
public record IndustryPanelActionPacket(int action, String recipeId, UUID workerId)
        implements CustomPacketPayload {

    /** 把某个市民派到 / 撤出这张配方（同一人再点一次即撤出）。 */
    public static final int ACTION_TOGGLE_WORKER = 0;
    /** 这张配方上的工人全部离岗。 */
    public static final int ACTION_CLEAR_WORKERS = 1;

    public static final CustomPacketPayload.Type<IndustryPanelActionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "industry_panel_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, IndustryPanelActionPacket> STREAM_CODEC =
            StreamCodec.of(IndustryPanelActionPacket::encode, IndustryPanelActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static IndustryPanelActionPacket toggleWorker(String recipeId, UUID workerId) {
        return new IndustryPanelActionPacket(ACTION_TOGGLE_WORKER, recipeId == null ? "" : recipeId, workerId);
    }

    public static IndustryPanelActionPacket clearWorkers(String recipeId) {
        return new IndustryPanelActionPacket(ACTION_CLEAR_WORKERS, recipeId == null ? "" : recipeId, null);
    }

    /** 配方 id 转成客户端会话里的稳定标识；空串表示没指定配方。 */
    public String recipeIdOrEmpty() {
        return recipeId == null ? "" : recipeId;
    }

    public UUID workerIdOrNull() {
        if (workerId == null) {
            return null;
        }
        return workerId.getMostSignificantBits() == 0L && workerId.getLeastSignificantBits() == 0L
                ? null : workerId;
    }

    private static void encode(RegistryFriendlyByteBuf buf, IndustryPanelActionPacket packet) {
        buf.writeVarInt(packet.action());
        buf.writeUtf(packet.recipeIdOrEmpty(), 128);
        buf.writeUUID(packet.workerId() == null ? new UUID(0L, 0L) : packet.workerId());
    }

    private static IndustryPanelActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new IndustryPanelActionPacket(buf.readVarInt(), buf.readUtf(128), buf.readUUID());
    }

    public static void handle(IndustryPanelActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                IndustryPanelService.apply(player, packet);
            }
        });
    }
}
