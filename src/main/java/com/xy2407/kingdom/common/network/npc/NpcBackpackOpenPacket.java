package com.xy2407.kingdom.common.network.npc;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：打开行装界面所需的展示数据，文案全部在服务端拼好。
 */
public record NpcBackpackOpenPacket(int entityId,
                                    String no,
                                    String name,
                                    String job,
                                    String gender,
                                    String age,
                                    String personality,
                                    String lifecycle,
                                    String nation,
                                    String wealth,
                                    double health,
                                    double healthMax,
                                    double hunger,
                                    double hungerMax,
                                    String desc) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NpcBackpackOpenPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "npc_backpack_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcBackpackOpenPacket> STREAM_CODEC =
            StreamCodec.of(NpcBackpackOpenPacket::encode, NpcBackpackOpenPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, NpcBackpackOpenPacket packet) {
        buf.writeInt(packet.entityId());
        buf.writeUtf(safe(packet.no()));
        buf.writeUtf(safe(packet.name()));
        buf.writeUtf(safe(packet.job()));
        buf.writeUtf(safe(packet.gender()));
        buf.writeUtf(safe(packet.age()));
        buf.writeUtf(safe(packet.personality()));
        buf.writeUtf(safe(packet.lifecycle()));
        buf.writeUtf(safe(packet.nation()));
        buf.writeUtf(safe(packet.wealth()));
        buf.writeDouble(packet.health());
        buf.writeDouble(packet.healthMax());
        buf.writeDouble(packet.hunger());
        buf.writeDouble(packet.hungerMax());
        buf.writeUtf(safe(packet.desc()));
    }

    private static NpcBackpackOpenPacket decode(RegistryFriendlyByteBuf buf) {
        return new NpcBackpackOpenPacket(
                buf.readInt(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readUtf());
    }

    public static void handle(NpcBackpackOpenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> NpcBackpackClientHooks.openSink.accept(packet));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
