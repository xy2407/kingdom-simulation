package com.xy2407.kingdom.common.network.road;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.road.RoadPolyline;
import com.xy2407.kingdom.server.road.RoadService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：一次提交的世界道路折线（只发线不发方块，服务端反向取样），半径固定 RoadPolyline.RADIUS。
 */
public record RoadWorldSubmitPacket(List<RoadPolyline> polylines) implements CustomPacketPayload {

    private static final int MAX_LINES = 64;

    private static final int MAX_POINTS = 4096;

    public static final CustomPacketPayload.Type<RoadWorldSubmitPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "road_world_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadWorldSubmitPacket> STREAM_CODEC =
            StreamCodec.of(RoadWorldSubmitPacket::encode, RoadWorldSubmitPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RoadWorldSubmitPacket packet) {
        int count = Math.min(packet.polylines().size(), MAX_LINES);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            RoadPolyline line = packet.polylines().get(index);
            int points = Math.min(line.pointCount(), MAX_POINTS);
            buf.writeVarInt(points);
            for (int point = 0; point < points; point++) {
                buf.writeFloat(line.xs()[point]);
                buf.writeFloat(line.zs()[point]);
            }
        }
    }

    private static RoadWorldSubmitPacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_LINES);
        List<RoadPolyline> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int points = Math.min(Math.max(buf.readVarInt(), 0), MAX_POINTS);
            float[] xs = new float[points];
            float[] zs = new float[points];
            for (int point = 0; point < points; point++) {
                xs[point] = buf.readFloat();
                zs[point] = buf.readFloat();
            }
            lines.add(new RoadPolyline(xs, zs));
        }
        return new RoadWorldSubmitPacket(lines);
    }

    public static void handle(RoadWorldSubmitPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RoadService.submitWorld(player, packet.polylines());
            }
        });
    }
}
