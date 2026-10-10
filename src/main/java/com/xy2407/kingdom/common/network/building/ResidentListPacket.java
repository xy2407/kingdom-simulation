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
 * 服务端 → 客户端：住宅的床位占用与可入住的市民名册。
 * 与养殖的 {@code RanchPanelStatePacket} 同一套骨架 —— 那边的"可雇市民"在这里是"可入住市民"，
 * 面板拿 {@code residents} 与名册比对就能画出谁已经住在这里。
 */
public record ResidentListPacket(boolean inside, String buildingName, int buildingLevel,
                                 String position, int bedTotal, List<UUID> residents,
                                 List<Worker> workers)
        implements CustomPacketPayload {

    /** 名册长度上限，防手改包刷屏。 */
    private static final int MAX_ENTRIES = 512;

    public static final CustomPacketPayload.Type<ResidentListPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "resident_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentListPacket> STREAM_CODEC =
            StreamCodec.of(ResidentListPacket::encode, ResidentListPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static ResidentListPacket outside() {
        return new ResidentListPacket(false, "", 0, "", 0, List.of(), List.of());
    }

    /** 名册里的一个市民：是否住在这里由客户端拿 {@link #residents()} 比对。 */
    public record Worker(UUID id, String name, String skin, String note) {
        public Worker {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            note = note == null ? "" : note;
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf, ResidentListPacket packet) {
        buf.writeBoolean(packet.inside());
        buf.writeUtf(packet.buildingName() == null ? "" : packet.buildingName(), 256);
        buf.writeVarInt(packet.buildingLevel());
        buf.writeUtf(packet.position() == null ? "" : packet.position(), 128);
        buf.writeVarInt(packet.bedTotal());

        buf.writeVarInt(packet.residents().size());
        for (UUID resident : packet.residents()) {
            buf.writeUUID(resident == null ? new UUID(0L, 0L) : resident);
        }

        buf.writeVarInt(packet.workers().size());
        for (Worker worker : packet.workers()) {
            buf.writeUUID(worker.id() == null ? new UUID(0L, 0L) : worker.id());
            buf.writeUtf(worker.name(), 64);
            buf.writeUtf(worker.skin(), 128);
            buf.writeUtf(worker.note(), 16);
        }
    }

    private static ResidentListPacket decode(RegistryFriendlyByteBuf buf) {
        boolean inside = buf.readBoolean();
        String buildingName = buf.readUtf(256);
        int buildingLevel = buf.readVarInt();
        String position = buf.readUtf(128);
        int bedTotal = buf.readVarInt();

        int residentCount = Math.min(Math.max(buf.readVarInt(), 0), MAX_ENTRIES);
        List<UUID> residents = new ArrayList<>(residentCount);
        for (int index = 0; index < residentCount; index++) {
            residents.add(buf.readUUID());
        }

        int workerCount = Math.min(Math.max(buf.readVarInt(), 0), MAX_ENTRIES);
        List<Worker> workers = new ArrayList<>(workerCount);
        for (int index = 0; index < workerCount; index++) {
            workers.add(new Worker(buf.readUUID(), buf.readUtf(64), buf.readUtf(128), buf.readUtf(16)));
        }

        return new ResidentListPacket(inside, buildingName, buildingLevel, position, bedTotal,
                residents, workers);
    }

    public static void handle(ResidentListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ResidentHooks.listSink.accept(packet));
    }
}
