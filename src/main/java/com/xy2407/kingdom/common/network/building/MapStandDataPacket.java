package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.network.clientbound.MapStandClientBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 仓库地图数据（服务端→客户端）：领地范围、建筑清单、管辖状态与员工名册。 */
public record MapStandDataPacket(BlockPos standPos, UUID warehouseId, String warehouseName,
                                 int minX, int minZ, int maxX, int maxZ,
                                 List<Building> buildings, List<Staff> staff)
        implements CustomPacketPayload {

    public record Building(UUID id, String name, String type, int minX, int minZ, int maxX, int maxZ,
                           int coreX, int coreZ, boolean warehouse, boolean governed) {
    }

    public record Staff(UUID id, String name, String job) {
    }

    public static final Type<MapStandDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "map_stand_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MapStandDataPacket> STREAM_CODEC =
            StreamCodec.of(MapStandDataPacket::encode, MapStandDataPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegistryFriendlyByteBuf buf, MapStandDataPacket packet) {
        buf.writeBlockPos(packet.standPos());
        buf.writeUUID(packet.warehouseId());
        buf.writeUtf(packet.warehouseName(), 64);
        buf.writeInt(packet.minX());
        buf.writeInt(packet.minZ());
        buf.writeInt(packet.maxX());
        buf.writeInt(packet.maxZ());
        buf.writeInt(packet.buildings().size());
        for (Building building : packet.buildings()) {
            buf.writeUUID(building.id());
            buf.writeUtf(building.name(), 64);
            buf.writeUtf(building.type(), 32);
            buf.writeInt(building.minX());
            buf.writeInt(building.minZ());
            buf.writeInt(building.maxX());
            buf.writeInt(building.maxZ());
            buf.writeInt(building.coreX());
            buf.writeInt(building.coreZ());
            buf.writeBoolean(building.warehouse());
            buf.writeBoolean(building.governed());
        }
        buf.writeInt(packet.staff().size());
        for (Staff staff : packet.staff()) {
            buf.writeUUID(staff.id());
            buf.writeUtf(staff.name(), 48);
            buf.writeUtf(staff.job(), 48);
        }
    }

    public static MapStandDataPacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos standPos = buf.readBlockPos();
        UUID warehouseId = buf.readUUID();
        String warehouseName = buf.readUtf(64);
        int minX = buf.readInt();
        int minZ = buf.readInt();
        int maxX = buf.readInt();
        int maxZ = buf.readInt();
        int buildingCount = Math.min(Math.max(buf.readInt(), 0), 512);
        List<Building> buildings = new ArrayList<>(buildingCount);
        for (int i = 0; i < buildingCount; i++) {
            buildings.add(new Building(buf.readUUID(), buf.readUtf(64), buf.readUtf(32),
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readBoolean(), buf.readBoolean()));
        }
        int staffCount = Math.min(Math.max(buf.readInt(), 0), 256);
        List<Staff> staff = new ArrayList<>(staffCount);
        for (int i = 0; i < staffCount; i++) {
            staff.add(new Staff(buf.readUUID(), buf.readUtf(48), buf.readUtf(48)));
        }
        return new MapStandDataPacket(standPos, warehouseId, warehouseName, minX, minZ, maxX, maxZ,
                buildings, staff);
    }

    public static void handle(MapStandDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> MapStandClientBridge.accept(packet));
    }
}
