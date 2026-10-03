package com.xy2407.kingdom.common.network.db;

import java.util.List;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.db.DbViewerService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求的数据库表快照，服务端按白名单表名回包
 */
public record DbViewerRequestPacket(String table) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DbViewerRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "db_viewer_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DbViewerRequestPacket> STREAM_CODEC =
            StreamCodec.of(DbViewerRequestPacket::encode, DbViewerRequestPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, DbViewerRequestPacket packet) {
        buf.writeUtf(packet.table() == null ? "" : packet.table(), 64);
    }

    private static DbViewerRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new DbViewerRequestPacket(buf.readUtf(64));
    }

    public static void handle(DbViewerRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(Component.literal("数据库查看器需要 OP 权限"));
                return;
            }
            List<DbViewerDataPacket> chunks = DbViewerService.snapshot(packet.table());
            for (DbViewerDataPacket data : chunks) {
                PacketDistributor.sendToPlayer(player, data);
            }
        });
    }
}
