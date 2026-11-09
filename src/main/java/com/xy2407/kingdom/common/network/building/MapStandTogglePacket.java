package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.building.MapStandService;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 地图架管辖变更（客户端→服务端）：按点击顺序提交要加入或移除的建筑。 */
public record MapStandTogglePacket(BlockPos standPos, List<UUID> buildingIds, boolean govern)
        implements CustomPacketPayload {

    public static final Type<MapStandTogglePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "map_stand_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MapStandTogglePacket> STREAM_CODEC =
            StreamCodec.of(MapStandTogglePacket::encode, MapStandTogglePacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buf, MapStandTogglePacket packet) {
        buf.writeBlockPos(packet.standPos());
        buf.writeBoolean(packet.govern());
        List<UUID> ids = packet.buildingIds() == null ? List.of() : packet.buildingIds();
        int count = Math.min(ids.size(), 512);
        buf.writeInt(count);
        for (int index = 0; index < count; index++) {
            buf.writeUUID(ids.get(index));
        }
    }

    public static MapStandTogglePacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean govern = buf.readBoolean();
        int count = Math.min(Math.max(buf.readInt(), 0), 512);
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readUUID());
        }
        return new MapStandTogglePacket(pos, ids, govern);
    }

    public static void handle(MapStandTogglePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> MapStandService.toggle(player, packet.standPos(),
                packet.buildingIds(), packet.govern()));
    }
}
