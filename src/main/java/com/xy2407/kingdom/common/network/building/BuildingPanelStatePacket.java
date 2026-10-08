package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：当前所处建筑的判定结果，以及该开哪个操作面板。
 */
public record BuildingPanelStatePacket(String name, int level, int farmSlots, String page, String pos)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingPanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPanelStatePacket> STREAM_CODEC =
            StreamCodec.of(BuildingPanelStatePacket::encode, BuildingPanelStatePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static BuildingPanelStatePacket outside() {
        return new BuildingPanelStatePacket("", 0, 0, "", "");
    }

    public boolean inside() {
        return !name.isEmpty();
    }

    public boolean farmable() {
        return farmSlots > 0;
    }

    public boolean hasPage() {
        return page != null && !page.isBlank();
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingPanelStatePacket packet) {
        buf.writeUtf(packet.name() == null ? "" : packet.name(), 256);
        buf.writeInt(packet.level());
        buf.writeInt(packet.farmSlots());
        buf.writeUtf(packet.page() == null ? "" : packet.page(), 128);
        buf.writeUtf(packet.pos() == null ? "" : packet.pos(), 64);
    }

    private static BuildingPanelStatePacket decode(RegistryFriendlyByteBuf buf) {
        return new BuildingPanelStatePacket(buf.readUtf(256), buf.readInt(), buf.readInt(),
                buf.readUtf(128), buf.readUtf(64));
    }

    public static void handle(BuildingPanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuildingPanelHooks.stateSink.accept(packet));
    }
}
