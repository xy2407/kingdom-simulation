package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：仓库界面的展示参数（建筑名、可用格数、页面实际画了多少格）。
 */
public record BuildingStorageOpenPacket(String buildingName, int capacity, int maxSlots)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingStorageOpenPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_storage_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingStorageOpenPacket> STREAM_CODEC =
            StreamCodec.of(BuildingStorageOpenPacket::encode, BuildingStorageOpenPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingStorageOpenPacket packet) {
        buf.writeUtf(packet.buildingName() == null ? "" : packet.buildingName());
        buf.writeVarInt(packet.capacity());
        buf.writeVarInt(packet.maxSlots());
    }

    private static BuildingStorageOpenPacket decode(RegistryFriendlyByteBuf buf) {
        return new BuildingStorageOpenPacket(buf.readUtf(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(BuildingStorageOpenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuildingStorageClientHooks.openSink.accept(packet));
    }
}
