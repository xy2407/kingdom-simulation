package com.xy2407.kingdom.common.network.forge;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.forge.ForgePanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 锻造面板操作包，提交新的生产委托或取消当前委托
 */
public record ForgePanelActionPacket(int action, String itemId, int count)
        implements CustomPacketPayload {

    public static final int ACTION_SUBMIT = 1;
    public static final int ACTION_CANCEL = 2;

    public static final CustomPacketPayload.Type<ForgePanelActionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "forge_panel_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ForgePanelActionPacket> STREAM_CODEC =
            StreamCodec.of(ForgePanelActionPacket::encode, ForgePanelActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static ForgePanelActionPacket submit(String itemId, int count) {
        return new ForgePanelActionPacket(ACTION_SUBMIT, itemId, count);
    }

    public static ForgePanelActionPacket cancel() {
        return new ForgePanelActionPacket(ACTION_CANCEL, "", 0);
    }

    private static void encode(RegistryFriendlyByteBuf buf, ForgePanelActionPacket packet) {
        buf.writeVarInt(packet.action());
        buf.writeUtf(packet.itemId() == null ? "" : packet.itemId(), 96);
        buf.writeVarInt(packet.count());
    }

    private static ForgePanelActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new ForgePanelActionPacket(buf.readVarInt(), buf.readUtf(96), buf.readVarInt());
    }

    public static void handle(ForgePanelActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ForgePanelService.apply(player, packet);
            }
        });
    }
}
