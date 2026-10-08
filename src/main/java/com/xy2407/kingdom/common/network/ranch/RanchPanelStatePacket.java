package com.xy2407.kingdom.common.network.ranch;

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
 * 服务端 → 客户端：养殖面板的整份状态，配方清单、每栏的存栏与饲养员、可雇市民名册一起下发
 */
public record RanchPanelStatePacket(boolean inside, String buildingName, int buildingLevel,
                                    String position, List<Animal> animals, List<Worker> workers)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RanchPanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "ranch_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RanchPanelStatePacket> STREAM_CODEC =
            StreamCodec.of(RanchPanelStatePacket::encode, RanchPanelStatePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static RanchPanelStatePacket outside() {
        return new RanchPanelStatePacket(false, "", 0, "", List.of(), List.of());
    }

    public record Animal(UUID penId, String id, String name, String entity, String feed, String output,
                         String mode, String status, int capacity, int total, int adults, boolean running,
                         List<UUID> keepers) {
        public Animal {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            entity = entity == null ? "" : entity;
            feed = feed == null ? "" : feed;
            output = output == null ? "" : output;
            mode = mode == null ? "" : mode;
            status = status == null ? "" : status;
            keepers = keepers == null ? List.of() : List.copyOf(keepers);
        }
    }

    public record Worker(UUID id, String name, String skin, String note) {
        public Worker {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            note = note == null ? "" : note;
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf, RanchPanelStatePacket packet) {
        buf.writeBoolean(packet.inside());
        buf.writeUtf(packet.buildingName() == null ? "" : packet.buildingName(), 256);
        buf.writeVarInt(packet.buildingLevel());
        buf.writeUtf(packet.position() == null ? "" : packet.position(), 128);

        buf.writeVarInt(packet.animals().size());
        for (Animal animal : packet.animals()) {
            buf.writeUUID(animal.penId() == null ? new UUID(0L, 0L) : animal.penId());
            buf.writeUtf(animal.id(), 128);
            buf.writeUtf(animal.name(), 64);
            buf.writeUtf(animal.entity(), 128);
            buf.writeUtf(animal.feed(), 128);
            buf.writeUtf(animal.output(), 128);
            buf.writeUtf(animal.mode(), 16);
            buf.writeUtf(animal.status(), 32);
            buf.writeVarInt(animal.capacity());
            buf.writeVarInt(animal.total());
            buf.writeVarInt(animal.adults());
            buf.writeBoolean(animal.running());
            buf.writeVarInt(animal.keepers().size());
            for (UUID keeper : animal.keepers()) {
                buf.writeUUID(keeper == null ? new UUID(0L, 0L) : keeper);
            }
        }

        buf.writeVarInt(packet.workers().size());
        for (Worker worker : packet.workers()) {
            buf.writeUUID(worker.id() == null ? new UUID(0L, 0L) : worker.id());
            buf.writeUtf(worker.name(), 64);
            buf.writeUtf(worker.skin(), 128);
            buf.writeUtf(worker.note(), 16);
        }
    }

    private static RanchPanelStatePacket decode(RegistryFriendlyByteBuf buf) {
        boolean inside = buf.readBoolean();
        String buildingName = buf.readUtf(256);
        int buildingLevel = buf.readVarInt();
        String position = buf.readUtf(128);

        int animalCount = buf.readVarInt();
        List<Animal> animals = new ArrayList<>(animalCount);
        for (int index = 0; index < animalCount; index++) {
            UUID penId = buf.readUUID();
            String id = buf.readUtf(128);
            String name = buf.readUtf(64);
            String entity = buf.readUtf(128);
            String feed = buf.readUtf(128);
            String output = buf.readUtf(128);
            String mode = buf.readUtf(16);
            String status = buf.readUtf(32);
            int capacity = buf.readVarInt();
            int total = buf.readVarInt();
            int adults = buf.readVarInt();
            boolean running = buf.readBoolean();
            int keeperCount = buf.readVarInt();
            List<UUID> keepers = new ArrayList<>(keeperCount);
            for (int keeper = 0; keeper < keeperCount; keeper++) {
                keepers.add(buf.readUUID());
            }
            animals.add(new Animal(penId, id, name, entity, feed, output, mode, status, capacity, total,
                    adults, running, keepers));
        }

        int workerCount = buf.readVarInt();
        List<Worker> workers = new ArrayList<>(workerCount);
        for (int index = 0; index < workerCount; index++) {
            workers.add(new Worker(buf.readUUID(), buf.readUtf(64), buf.readUtf(128), buf.readUtf(16)));
        }

        return new RanchPanelStatePacket(inside, buildingName, buildingLevel, position, animals, workers);
    }

    public static void handle(RanchPanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> RanchPanelHooks.stateSink.accept(packet));
    }
}
