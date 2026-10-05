package com.xy2407.kingdom.common.network.farm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.farm.FarmRegion;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：农业面板的整份状态，每次全量下发不做增量
 */
public record FarmPanelStatePacket(boolean inside, String buildingName, int totalSlots, int usedSlots,
                                  List<Plot> plots, List<CropOption> crops, List<WorkerOption> workers)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FarmPanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "farm_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FarmPanelStatePacket> STREAM_CODEC =
            StreamCodec.of(FarmPanelStatePacket::encode, FarmPanelStatePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static FarmPanelStatePacket outside() {
        return new FarmPanelStatePacket(false, "", 0, 0, List.of(), List.of(), List.of());
    }

    public int remainingSlots() {
        return Math.max(0, totalSlots - usedSlots);
    }

    public record Plot(UUID id, String name, int slots, String cropId,
                       boolean hasArea, int minX, int minZ, int maxX, int maxZ, int groundY,
                       List<UUID> workers, List<FarmRegion> extra) {
        public Plot {
            name = name == null ? "" : name;
            cropId = cropId == null ? "" : cropId;
            workers = workers == null ? List.of() : List.copyOf(workers);
            extra = extra == null ? List.of() : List.copyOf(extra);
        }
    }

    public record CropOption(String id, String label) {
    }

    public record WorkerOption(UUID id, String name, String skin, String note) {
        public WorkerOption {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            note = note == null ? "" : note;
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf, FarmPanelStatePacket packet) {
        buf.writeBoolean(packet.inside());
        buf.writeUtf(packet.buildingName() == null ? "" : packet.buildingName(), 256);
        buf.writeInt(packet.totalSlots());
        buf.writeInt(packet.usedSlots());

        buf.writeVarInt(packet.plots().size());
        for (Plot plot : packet.plots()) {
            writeUuid(buf, plot.id());
            buf.writeUtf(plot.name(), 64);
            buf.writeInt(plot.slots());
            buf.writeUtf(plot.cropId(), 128);
            buf.writeBoolean(plot.hasArea());
            buf.writeInt(plot.minX());
            buf.writeInt(plot.minZ());
            buf.writeInt(plot.maxX());
            buf.writeInt(plot.maxZ());
            buf.writeInt(plot.groundY());
            buf.writeVarInt(plot.workers().size());
            for (UUID worker : plot.workers()) {
                writeUuid(buf, worker);
            }
            buf.writeVarInt(plot.extra().size());
            for (FarmRegion region : plot.extra()) {
                buf.writeInt(region.minX());
                buf.writeInt(region.minZ());
                buf.writeInt(region.maxX());
                buf.writeInt(region.maxZ());
            }
        }

        buf.writeVarInt(packet.crops().size());
        for (CropOption crop : packet.crops()) {
            buf.writeUtf(crop.id(), 128);
            buf.writeUtf(crop.label(), 64);
        }

        buf.writeVarInt(packet.workers().size());
        for (WorkerOption worker : packet.workers()) {
            writeUuid(buf, worker.id());
            buf.writeUtf(worker.name(), 64);
            buf.writeUtf(worker.skin(), 128);
            buf.writeUtf(worker.note(), 16);
        }
    }

    private static FarmPanelStatePacket decode(RegistryFriendlyByteBuf buf) {
        boolean inside = buf.readBoolean();
        String buildingName = buf.readUtf(256);
        int totalSlots = buf.readInt();
        int usedSlots = buf.readInt();

        int plotCount = buf.readVarInt();
        List<Plot> plots = new ArrayList<>(plotCount);
        for (int index = 0; index < plotCount; index++) {
            UUID id = readUuid(buf);
            String name = buf.readUtf(64);
            int slots = buf.readInt();
            String cropId = buf.readUtf(128);
            boolean hasArea = buf.readBoolean();
            int minX = buf.readInt();
            int minZ = buf.readInt();
            int maxX = buf.readInt();
            int maxZ = buf.readInt();
            int groundY = buf.readInt();
            int workerCount = buf.readVarInt();
            List<UUID> workers = new ArrayList<>(workerCount);
            for (int worker = 0; worker < workerCount; worker++) {
                workers.add(readUuid(buf));
            }
            int regionCount = buf.readVarInt();
            List<FarmRegion> extra = new ArrayList<>(regionCount);
            for (int region = 0; region < regionCount; region++) {
                extra.add(new FarmRegion(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));
            }
            plots.add(new Plot(id, name, slots, cropId, hasArea, minX, minZ, maxX, maxZ, groundY, workers, extra));
        }

        int cropCount = buf.readVarInt();
        List<CropOption> crops = new ArrayList<>(cropCount);
        for (int index = 0; index < cropCount; index++) {
            crops.add(new CropOption(buf.readUtf(128), buf.readUtf(64)));
        }

        int workerCount = buf.readVarInt();
        List<WorkerOption> workers = new ArrayList<>(workerCount);
        for (int index = 0; index < workerCount; index++) {
            workers.add(new WorkerOption(readUuid(buf), buf.readUtf(64), buf.readUtf(128), buf.readUtf(16)));
        }

        return new FarmPanelStatePacket(inside, buildingName, totalSlots, usedSlots, plots, crops, workers);
    }

    private static void writeUuid(RegistryFriendlyByteBuf buf, UUID id) {
        buf.writeUUID(id == null ? new UUID(0L, 0L) : id);
    }

    private static UUID readUuid(RegistryFriendlyByteBuf buf) {
        return buf.readUUID();
    }

    public static void handle(FarmPanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> FarmPanelHooks.stateSink.accept(packet));
    }
}
