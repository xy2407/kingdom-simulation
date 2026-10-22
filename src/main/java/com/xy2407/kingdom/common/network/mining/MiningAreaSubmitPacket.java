package com.xy2407.kingdom.common.network.mining;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.mining.MiningAreaService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：采矿笔刷一次提交的格集坐标，服务端取包围盒与最高方块建坑。
 */
public record MiningAreaSubmitPacket(long[] cells, java.util.UUID buildingId)
        implements CustomPacketPayload {

    private static final int MAX_CELLS = 8192;

    public static final CustomPacketPayload.Type<MiningAreaSubmitPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "mining_area_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MiningAreaSubmitPacket> STREAM_CODEC =
            StreamCodec.of(MiningAreaSubmitPacket::encode, MiningAreaSubmitPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, MiningAreaSubmitPacket packet) {
        buf.writeBoolean(packet.buildingId() != null);
        if (packet.buildingId() != null) {
            buf.writeUUID(packet.buildingId());
        }
        int count = Math.min(packet.cells().length, MAX_CELLS);
        buf.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            buf.writeLong(packet.cells()[index]);
        }
    }

    private static MiningAreaSubmitPacket decode(RegistryFriendlyByteBuf buf) {
        java.util.UUID buildingId = buf.readBoolean() ? buf.readUUID() : null;
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_CELLS);
        long[] cells = new long[count];
        for (int index = 0; index < count; index++) {
            cells[index] = buf.readLong();
        }
        return new MiningAreaSubmitPacket(cells, buildingId);
    }

    public static void handle(MiningAreaSubmitPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MiningAreaService.submit(player, packet.cells(), packet.buildingId());
            }
        });
    }
}
