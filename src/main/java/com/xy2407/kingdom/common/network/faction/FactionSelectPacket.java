package com.xy2407.kingdom.common.network.faction;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：玩家在择旗界面上的决定，选现有阵营与自建国共用一个包。
 */
public record FactionSelectPacket(boolean custom, String id,
                                  String name, String ruler, String motto,
                                  String colorLeft, String colorRight,
                                  String emblem, String emblemColor)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FactionSelectPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "faction_select"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionSelectPacket> STREAM_CODEC =
            StreamCodec.of(FactionSelectPacket::encode, FactionSelectPacket::decode);

    public FactionSelectPacket {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        ruler = ruler == null ? "" : ruler;
        motto = motto == null ? "" : motto;
        colorLeft = colorLeft == null ? "" : colorLeft;
        colorRight = colorRight == null ? "" : colorRight;
        emblem = emblem == null ? "" : emblem;
        emblemColor = emblemColor == null ? "" : emblemColor;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, FactionSelectPacket packet) {
        buf.writeBoolean(packet.custom());
        buf.writeUtf(packet.id(), 64);
        buf.writeUtf(packet.name(), 64);
        buf.writeUtf(packet.ruler(), 64);
        buf.writeUtf(packet.motto(), 64);
        buf.writeUtf(packet.colorLeft(), 32);
        buf.writeUtf(packet.colorRight(), 32);
        buf.writeUtf(packet.emblem(), 32);
        buf.writeUtf(packet.emblemColor(), 32);
    }

    private static FactionSelectPacket decode(RegistryFriendlyByteBuf buf) {
        return new FactionSelectPacket(
                buf.readBoolean(),
                buf.readUtf(64), buf.readUtf(64), buf.readUtf(64), buf.readUtf(64),
                buf.readUtf(32), buf.readUtf(32), buf.readUtf(32), buf.readUtf(32));
    }

    public static void handle(FactionSelectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                FactionHooks.selectHandler.accept(player, packet);
            }
        });
    }
}
