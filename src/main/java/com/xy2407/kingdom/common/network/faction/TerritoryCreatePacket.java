package com.xy2407.kingdom.common.network.faction;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：在某个市政厅方块上立一块领地。
 *
 * <p>领地范围<b>不由客户端决定</b>——服务端只取市政厅这一点，
 * 自己按"所在区块 ±2 格"算出 5×5 的地界。客户端给的名字、坐标都只是请求，
 * 服务端会先确认那个位置真的是自家模组的市政厅方块，再动手。</p>
 *
 * <p><b>Y 必须带上</b>：服务端要拿它去取那一格方块来验"这里真是市政厅"。
 * 早先靠 {@code player.getBlockY()} 猜，那是玩家<em>脚下</em>的高度，
 * 跟方块所在格差一格，校验必定失败——领地建不出来就是这么来的。</p>
 */
public record TerritoryCreatePacket(String name, int hallX, int hallY, int hallZ)
        implements CustomPacketPayload {

    /** 名称长度上限。界面里 maxlength=12，这里再兜一道，防改包。 */
    public static final int MAX_NAME_LENGTH = 12;

    public static final CustomPacketPayload.Type<TerritoryCreatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "territory_create"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryCreatePacket> STREAM_CODEC =
            StreamCodec.of(TerritoryCreatePacket::encode, TerritoryCreatePacket::decode);

    public TerritoryCreatePacket {
        name = name == null ? "" : name;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, TerritoryCreatePacket packet) {
        buf.writeUtf(packet.name(), MAX_NAME_LENGTH);
        buf.writeVarInt(packet.hallX());
        buf.writeVarInt(packet.hallY());
        buf.writeVarInt(packet.hallZ());
    }

    private static TerritoryCreatePacket decode(RegistryFriendlyByteBuf buf) {
        return new TerritoryCreatePacket(buf.readUtf(MAX_NAME_LENGTH),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(TerritoryCreatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                FactionHooks.territoryCreateHandler.accept(player, packet);
            }
        });
    }
}
