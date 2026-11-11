package com.xy2407.kingdom.common.network.building;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 工种候选包，下发未定型建筑的分类名称与可选工种，供客户端弹出选择界面
 */
public record BuildingWorkChoicePacket(UUID buildingId, String category, List<String> works)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingWorkChoicePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "building_work_choice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingWorkChoicePacket> STREAM_CODEC =
            StreamCodec.of(BuildingWorkChoicePacket::encode, BuildingWorkChoicePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingWorkChoicePacket packet) {
        buf.writeUUID(packet.buildingId() == null ? new UUID(0L, 0L) : packet.buildingId());
        buf.writeUtf(packet.category() == null ? "" : packet.category(), 32);
        buf.writeVarInt(packet.works() == null ? 0 : packet.works().size());
        if (packet.works() != null) {
            for (String work : packet.works()) {
                buf.writeUtf(work == null ? "" : work, 48);
            }
        }
    }

    private static BuildingWorkChoicePacket decode(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String category = buf.readUtf(32);
        int size = buf.readVarInt();
        List<String> works = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            works.add(buf.readUtf(48));
        }
        return new BuildingWorkChoicePacket(id, category, works);
    }

    public static void handle(BuildingWorkChoicePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuildingPanelHooks.workChoiceSink.accept(packet));
    }
}
