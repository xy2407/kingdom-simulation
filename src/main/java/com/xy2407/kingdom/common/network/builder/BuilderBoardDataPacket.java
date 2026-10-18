package com.xy2407.kingdom.common.network.builder;

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
 * 建筑工面板全量数据：雇员名单与全部任务卡片（建筑拆除填充）一次同步
 */
public record BuilderBoardDataPacket(List<EmployeeEntry> employees,
                                     List<TaskEntry> tasks) implements CustomPacketPayload {

    public static final int KIND_BUILDING = 0;
    public static final int KIND_DEMOLISH = 1;
    public static final int KIND_FILL = 2;

    public static final CustomPacketPayload.Type<BuilderBoardDataPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "builder_board_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderBoardDataPacket> STREAM_CODEC =
            StreamCodec.of(BuilderBoardDataPacket::encode, BuilderBoardDataPacket::decode);

    private static final int MAX_EMPLOYEES = 32;
    private static final int MAX_TASKS = 64;
    private static final int MAX_NPCS = 16;
    private static final int MAX_MATS = 16;

    public record EmployeeEntry(UUID npcId, String name, String skin, String status) {

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUUID(npcId == null ? new UUID(0L, 0L) : npcId);
            buf.writeUtf(name == null ? "" : name, 64);
            buf.writeUtf(skin == null ? "" : skin, 128);
            buf.writeUtf(status == null ? "" : status, 16);
        }

        private static EmployeeEntry read(RegistryFriendlyByteBuf buf) {
            UUID npcId = buf.readUUID();
            String name = buf.readUtf(64);
            String skin = buf.readUtf(128);
            return new EmployeeEntry(npcId, name, skin, buf.readUtf(16));
        }
    }

    public record NpcRef(UUID npcId, String name, String skin) {

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUUID(npcId == null ? new UUID(0L, 0L) : npcId);
            buf.writeUtf(name == null ? "" : name, 64);
            buf.writeUtf(skin == null ? "" : skin, 128);
        }

        private static NpcRef read(RegistryFriendlyByteBuf buf) {
            UUID npcId = buf.readUUID();
            String name = buf.readUtf(64);
            return new NpcRef(npcId, name, buf.readUtf(128));
        }
    }

    public record MatEntry(String itemId, int count) {

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(itemId == null ? "" : itemId, 128);
            buf.writeVarInt(count);
        }

        private static MatEntry read(RegistryFriendlyByteBuf buf) {
            return new MatEntry(buf.readUtf(128), buf.readVarInt());
        }
    }

    public record TaskEntry(UUID id, int kind, String title, String dimension,
                            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                            String status, int cursor, int total,
                            List<NpcRef> npcs, List<MatEntry> materials) {

        private static final int MAX = 4096;

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUUID(id == null ? new UUID(0L, 0L) : id);
            buf.writeVarInt(kind);
            buf.writeUtf(title == null ? "" : title, 256);
            buf.writeUtf(dimension == null ? "" : dimension, 128);
            buf.writeInt(minX);
            buf.writeInt(minY);
            buf.writeInt(minZ);
            buf.writeInt(maxX);
            buf.writeInt(maxY);
            buf.writeInt(maxZ);
            buf.writeUtf(status == null ? "" : status, 16);
            buf.writeInt(Math.max(0, Math.min(cursor, MAX)));
            buf.writeInt(Math.max(0, Math.min(total, MAX)));
            int npcCount = npcs == null ? 0 : Math.min(npcs.size(), MAX_NPCS);
            buf.writeVarInt(npcCount);
            for (int i = 0; i < npcCount; i++) {
                npcs.get(i).write(buf);
            }
            int matCount = materials == null ? 0 : Math.min(materials.size(), MAX_MATS);
            buf.writeVarInt(matCount);
            for (int i = 0; i < matCount; i++) {
                materials.get(i).write(buf);
            }
        }

        private static TaskEntry read(RegistryFriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            int kind = buf.readVarInt();
            String title = buf.readUtf(256);
            String dimension = buf.readUtf(128);
            int minX = buf.readInt();
            int minY = buf.readInt();
            int minZ = buf.readInt();
            int maxX = buf.readInt();
            int maxY = buf.readInt();
            int maxZ = buf.readInt();
            String status = buf.readUtf(16);
            int cursor = buf.readInt();
            int total = buf.readInt();
            return new TaskEntry(id, kind, title, dimension, minX, minY, minZ, maxX, maxY, maxZ,
                    status, cursor, total, readNpcs(buf), readMats(buf));
        }

        private static List<NpcRef> readNpcs(RegistryFriendlyByteBuf buf) {
            int size = Math.max(0, Math.min(buf.readVarInt(), MAX_NPCS));
            List<NpcRef> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(NpcRef.read(buf));
            }
            return list;
        }

        private static List<MatEntry> readMats(RegistryFriendlyByteBuf buf) {
            int size = Math.max(0, Math.min(buf.readVarInt(), MAX_MATS));
            List<MatEntry> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(MatEntry.read(buf));
            }
            return list;
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuilderBoardDataPacket packet) {
        writeEmployees(buf, packet.employees());
        int taskCount = packet.tasks() == null ? 0 : Math.min(packet.tasks().size(), MAX_TASKS);
        buf.writeVarInt(taskCount);
        if (packet.tasks() != null) {
            for (int i = 0; i < taskCount; i++) {
                packet.tasks().get(i).write(buf);
            }
        }
    }

    private static void writeEmployees(RegistryFriendlyByteBuf buf, List<EmployeeEntry> list) {
        int size = list == null ? 0 : Math.min(list.size(), MAX_EMPLOYEES);
        buf.writeVarInt(size);
        if (list != null) {
            for (int i = 0; i < size; i++) {
                list.get(i).write(buf);
            }
        }
    }

    private static BuilderBoardDataPacket decode(RegistryFriendlyByteBuf buf) {
        int employeeCount = Math.max(0, Math.min(buf.readVarInt(), MAX_EMPLOYEES));
        List<EmployeeEntry> employees = new ArrayList<>(employeeCount);
        for (int i = 0; i < employeeCount; i++) {
            employees.add(EmployeeEntry.read(buf));
        }
        int taskCount = Math.max(0, Math.min(buf.readVarInt(), MAX_TASKS));
        List<TaskEntry> tasks = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            tasks.add(TaskEntry.read(buf));
        }
        return new BuilderBoardDataPacket(employees, tasks);
    }

    public static void handle(BuilderBoardDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuilderHooks.dataSink.accept(packet));
    }
}
