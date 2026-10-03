package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.rts.RtsSelectionService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端 → 服务端：这是我现在选中的一批单位。
 *
 * <p>移植自 {@code xy's_nsuk} 的同名包，去掉了城市归属校验与建筑界限同步（kingdom 没有阵营概念）。
 * 服务端只做"存在 + 类型正确 + 是本人替身"这三条基本检查，然后回发一份裁决结果。</p>
 */
public record RtsSelectionSyncPacket(Set<UUID> ids) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsSelectionSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_selection_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsSelectionSyncPacket> STREAM_CODEC =
            StreamCodec.of(RtsSelectionSyncPacket::encode, RtsSelectionSyncPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsSelectionSyncPacket packet) {
        Set<UUID> ids = packet.ids() == null ? Set.of() : packet.ids();
        buf.writeInt(ids.size());
        for (UUID id : ids) {
            buf.writeUUID(id);
        }
    }

    private static RtsSelectionSyncPacket decode(RegistryFriendlyByteBuf buf) {
        int size = buf.readInt();
        Set<UUID> ids = new HashSet<>(Math.max(size, 1));
        for (int i = 0; i < size; i++) {
            ids.add(buf.readUUID());
        }
        return new RtsSelectionSyncPacket(ids);
    }

    public static void handle(RtsSelectionSyncPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Set<UUID> requested = packet.ids();
        context.enqueueWork(() -> {
            Set<UUID> allowed = RtsSelectionService.filterControllable(level, player, requested);
            RtsSelectionService.setSelected(player, allowed);
            PacketDistributor.sendToPlayer(player, new RtsSelectionCorrectionPacket(allowed));
        });
    }
}
