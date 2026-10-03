package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsFakePlayerService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：进入 RTS，请在我的位置放一个替身。
 *
 * <p>无字段，带 {@code StreamCodec.unit}。服务端会先清掉我已有的替身再新建，
 * 所以重复发送等价于"把替身挪回我脚下"。</p>
 */
public record RtsFakePlayerSpawnPacket() implements CustomPacketPayload {

    public static final RtsFakePlayerSpawnPacket INSTANCE = new RtsFakePlayerSpawnPacket();

    public static final CustomPacketPayload.Type<RtsFakePlayerSpawnPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_fake_player_spawn"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsFakePlayerSpawnPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RtsFakePlayerSpawnPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> RtsFakePlayerService.spawnFor(player));
    }
}
