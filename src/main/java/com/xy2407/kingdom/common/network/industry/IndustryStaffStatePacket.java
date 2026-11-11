package com.xy2407.kingdom.common.network.industry;

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
 * 工业用工面板快照包，下发工位上限、驻建筑工人、各作业点人数与可雇市民
 */
public record IndustryStaffStatePacket(boolean inside, UUID buildingId, String name, int level,
                                       int capacity, List<Worker> staff, List<Plot> plots,
                                       List<Worker> candidates) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<IndustryStaffStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "industry_staff_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, IndustryStaffStatePacket> STREAM_CODEC =
            StreamCodec.of(IndustryStaffStatePacket::encode, IndustryStaffStatePacket::decode);

    public record Worker(UUID id, String name, String skin, String note, UUID assignedTo,
                         String assignedName) {
        public Worker {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            note = note == null ? "" : note;
            assignedName = assignedName == null ? "" : assignedName;
        }
    }

    public record Plot(UUID id, String name, int workers, String status) {
        public Plot {
            name = name == null ? "" : name;
            status = status == null ? "" : status;
        }
    }

    public IndustryStaffStatePacket {
        name = name == null ? "" : name;
        staff = staff == null ? List.of() : List.copyOf(staff);
        plots = plots == null ? List.of() : List.copyOf(plots);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static IndustryStaffStatePacket outside() {
        return new IndustryStaffStatePacket(false, null, "", 1, 0, List.of(), List.of(), List.of());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, IndustryStaffStatePacket packet) {
        buf.writeBoolean(packet.inside());
        buf.writeUUID(packet.buildingId() == null ? new UUID(0L, 0L) : packet.buildingId());
        buf.writeUtf(packet.name(), 256);
        buf.writeVarInt(packet.level());
        buf.writeVarInt(packet.capacity());
        writeWorkers(buf, packet.staff());
        buf.writeVarInt(packet.plots().size());
        for (Plot plot : packet.plots()) {
            buf.writeUUID(plot.id());
            buf.writeUtf(plot.name(), 96);
            buf.writeVarInt(plot.workers());
            buf.writeUtf(plot.status(), 32);
        }
        writeWorkers(buf, packet.candidates());
    }

    private static void writeWorkers(RegistryFriendlyByteBuf buf, List<Worker> workers) {
        buf.writeVarInt(workers.size());
        for (Worker worker : workers) {
            buf.writeUUID(worker.id());
            buf.writeUtf(worker.name(), 96);
            buf.writeUtf(worker.skin(), 64);
            buf.writeUtf(worker.note(), 96);
            buf.writeUUID(worker.assignedTo() == null ? new UUID(0L, 0L) : worker.assignedTo());
            buf.writeUtf(worker.assignedName(), 96);
        }
    }

    private static List<Worker> readWorkers(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Worker> workers = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UUID id = buf.readUUID();
            String name = buf.readUtf(96);
            String skin = buf.readUtf(64);
            String note = buf.readUtf(96);
            UUID assigned = buf.readUUID();
            String assignedName = buf.readUtf(96);
            workers.add(new Worker(id, name, skin, note,
                    assigned.getMostSignificantBits() == 0L && assigned.getLeastSignificantBits() == 0L
                            ? null : assigned,
                    assignedName));
        }
        return workers;
    }

    private static IndustryStaffStatePacket decode(RegistryFriendlyByteBuf buf) {
        boolean inside = buf.readBoolean();
        UUID buildingId = buf.readUUID();
        String name = buf.readUtf(256);
        int level = buf.readVarInt();
        int capacity = buf.readVarInt();
        List<Worker> staff = readWorkers(buf);
        int plotSize = buf.readVarInt();
        List<Plot> plots = new ArrayList<>(plotSize);
        for (int i = 0; i < plotSize; i++) {
            UUID id = buf.readUUID();
            String plotName = buf.readUtf(96);
            int workers = buf.readVarInt();
            String status = buf.readUtf(32);
            plots.add(new Plot(id, plotName, workers, status));
        }
        List<Worker> candidates = readWorkers(buf);
        return new IndustryStaffStatePacket(inside, buildingId, name, level, capacity,
                staff, plots, candidates);
    }

    public static void handle(IndustryStaffStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> IndustryStaffHooks.stateSink.accept(packet));
    }
}
