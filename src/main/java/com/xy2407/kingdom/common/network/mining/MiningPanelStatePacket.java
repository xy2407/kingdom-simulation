package com.xy2407.kingdom.common.network.mining;

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
 * 服务端 → 客户端：当前采矿建筑的矿坑与可派工名册快照。
 */
public record MiningPanelStatePacket(boolean inside, String buildingName,
                                     List<Mine> mines, List<WorkerOption> workers) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MiningPanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "mining_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MiningPanelStatePacket> STREAM_CODEC =
            StreamCodec.of(MiningPanelStatePacket::encode, MiningPanelStatePacket::decode);

    public record Mine(UUID id, String name, int minX, int minZ, int maxX, int maxZ,
                       int surfaceY, int currentY, int cursor, int layerSize,
                       String status, List<UUID> workers) {
    }

    public record WorkerOption(UUID id, String name, String skin, String note) {
    }

    public static MiningPanelStatePacket outside() {
        return new MiningPanelStatePacket(false, "", List.of(), List.of());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, MiningPanelStatePacket packet) {
        buf.writeBoolean(packet.inside());
        buf.writeUtf(packet.buildingName() == null ? "" : packet.buildingName(), 256);
        buf.writeVarInt(packet.mines().size());
        for (Mine mine : packet.mines()) {
            buf.writeUUID(mine.id());
            buf.writeUtf(mine.name(), 64);
            buf.writeVarInt(mine.minX());
            buf.writeVarInt(mine.minZ());
            buf.writeVarInt(mine.maxX());
            buf.writeVarInt(mine.maxZ());
            buf.writeVarInt(mine.surfaceY());
            buf.writeVarInt(mine.currentY());
            buf.writeVarInt(mine.cursor());
            buf.writeVarInt(mine.layerSize());
            buf.writeUtf(mine.status(), 16);
            buf.writeVarInt(mine.workers().size());
            for (UUID worker : mine.workers()) {
                buf.writeUUID(worker);
            }
        }
        buf.writeVarInt(packet.workers().size());
        for (WorkerOption worker : packet.workers()) {
            buf.writeUUID(worker.id());
            buf.writeUtf(worker.name(), 64);
            buf.writeUtf(worker.skin(), 256);
            buf.writeUtf(worker.note(), 64);
        }
    }

    private static MiningPanelStatePacket decode(RegistryFriendlyByteBuf buf) {
        boolean inside = buf.readBoolean();
        String name = buf.readUtf(256);
        int mineCount = buf.readVarInt();
        List<Mine> mines = new ArrayList<>(mineCount);
        for (int index = 0; index < mineCount; index++) {
            UUID id = buf.readUUID();
            String mineName = buf.readUtf(64);
            int minX = buf.readVarInt();
            int minZ = buf.readVarInt();
            int maxX = buf.readVarInt();
            int maxZ = buf.readVarInt();
            int surfaceY = buf.readVarInt();
            int currentY = buf.readVarInt();
            int cursor = buf.readVarInt();
            int layerSize = buf.readVarInt();
            String status = buf.readUtf(16);
            int workerCount = buf.readVarInt();
            List<UUID> workers = new ArrayList<>(workerCount);
            for (int w = 0; w < workerCount; w++) {
                workers.add(buf.readUUID());
            }
            mines.add(new Mine(id, mineName, minX, minZ, maxX, maxZ,
                    surfaceY, currentY, cursor, layerSize, status, List.copyOf(workers)));
        }
        int workerTotal = buf.readVarInt();
        List<WorkerOption> workers = new ArrayList<>(workerTotal);
        for (int index = 0; index < workerTotal; index++) {
            workers.add(new WorkerOption(buf.readUUID(), buf.readUtf(64), buf.readUtf(256), buf.readUtf(64)));
        }
        return new MiningPanelStatePacket(inside, name, List.copyOf(mines), List.copyOf(workers));
    }

    public static void handle(MiningPanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> MiningPanelHooks.stateSink().accept(packet));
    }
}
