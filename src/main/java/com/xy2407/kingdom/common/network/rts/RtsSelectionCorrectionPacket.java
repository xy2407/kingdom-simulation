package com.xy2407.kingdom.common.network.rts;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端 → 客户端：裁决后的选中集合，客户端以此为准覆盖本地选择。
 */
public record RtsSelectionCorrectionPacket(Set<UUID> ids) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RtsSelectionCorrectionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "rts_selection_correction"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RtsSelectionCorrectionPacket> STREAM_CODEC =
            StreamCodec.of(RtsSelectionCorrectionPacket::encode, RtsSelectionCorrectionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RtsSelectionCorrectionPacket packet) {
        Set<UUID> ids = packet.ids() == null ? Set.of() : packet.ids();
        buf.writeInt(ids.size());
        for (UUID id : ids) {
            buf.writeUUID(id);
        }
    }

    private static RtsSelectionCorrectionPacket decode(RegistryFriendlyByteBuf buf) {
        int size = buf.readInt();
        Set<UUID> ids = new HashSet<>(Math.max(size, 1));
        for (int i = 0; i < size; i++) {
            ids.add(buf.readUUID());
        }
        return new RtsSelectionCorrectionPacket(ids);
    }

    public static void handle(RtsSelectionCorrectionPacket packet, IPayloadContext context) {
        Set<UUID> ids = packet.ids();
        context.enqueueWork(() -> RtsClientHooks.selectionSink.accept(ids == null ? Set.of() : ids));
    }
}
