package com.xy2407.kingdom.common.network.faction;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：玩家在择旗界面上的决定。
 *
 * <p>两种走法合在一个包里：</p>
 * <ul>
 *   <li>{@code custom=false}：选了预置或已有的阵营，此时只有 {@code id} 有意义，
 *       服务端拿 {@code NationRegistry.isKnown} 校验；</li>
 *   <li>{@code custom=true}：自建国，{@code id} 由服务端分配（{@code realm-N}），
 *       客户端带的国号/君主/箴言/双色/纹章/纹章色一律作数。</li>
 * </ul>
 *
 * <p>服务端处理完用 {@link FactionStatePacket} 回推一次状态，客户端据此刷新。</p>
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
