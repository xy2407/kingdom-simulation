package com.xy2407.kingdom.common.network.forge;

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
 * 锻造坊面板快照，下发可合成物品清单、当前委托进度与各步骤完成情况
 */
public record ForgePanelStatePacket(boolean inside, UUID buildingId, String name, int level,
                                    List<String> catalog, List<Task> tasks)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ForgePanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "forge_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ForgePanelStatePacket> STREAM_CODEC =
            StreamCodec.of(ForgePanelStatePacket::encode, ForgePanelStatePacket::decode);

    public record Step(String kind, String output, int done, int total) {
        public Step {
            kind = kind == null ? "" : kind;
            output = output == null ? "" : output;
        }
    }

    public record Task(String target, int requested, String state, String missing, List<Step> steps) {
        public Task {
            target = target == null ? "" : target;
            state = state == null ? "" : state;
            missing = missing == null ? "" : missing;
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public ForgePanelStatePacket {
        name = name == null ? "" : name;
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public static ForgePanelStatePacket outside() {
        return new ForgePanelStatePacket(false, null, "", 1, List.of(), List.of());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, ForgePanelStatePacket packet) {
        buf.writeBoolean(packet.inside());
        buf.writeUUID(packet.buildingId() == null ? new UUID(0L, 0L) : packet.buildingId());
        buf.writeUtf(packet.name(), 64);
        buf.writeVarInt(packet.level());
        buf.writeVarInt(packet.catalog().size());
        for (String item : packet.catalog()) {
            buf.writeUtf(item, 96);
        }
        buf.writeVarInt(packet.tasks().size());
        for (Task task : packet.tasks()) {
            buf.writeUtf(task.target(), 96);
            buf.writeVarInt(task.requested());
            buf.writeUtf(task.state(), 16);
            buf.writeUtf(task.missing(), 128);
            buf.writeVarInt(task.steps().size());
            for (Step step : task.steps()) {
                buf.writeUtf(step.kind(), 8);
                buf.writeUtf(step.output(), 96);
                buf.writeVarInt(step.done());
                buf.writeVarInt(step.total());
            }
        }
    }

    private static ForgePanelStatePacket decode(RegistryFriendlyByteBuf buf) {
        boolean inside = buf.readBoolean();
        UUID buildingId = buf.readUUID();
        String name = buf.readUtf(64);
        int level = buf.readVarInt();
        int catalogSize = buf.readVarInt();
        List<String> catalog = new ArrayList<>(catalogSize);
        for (int i = 0; i < catalogSize; i++) {
            catalog.add(buf.readUtf(96));
        }
        int taskSize = buf.readVarInt();
        List<Task> tasks = new ArrayList<>(taskSize);
        for (int i = 0; i < taskSize; i++) {
            String target = buf.readUtf(96);
            int requested = buf.readVarInt();
            String state = buf.readUtf(16);
            String missing = buf.readUtf(128);
            int stepSize = buf.readVarInt();
            List<Step> steps = new ArrayList<>(stepSize);
            for (int s = 0; s < stepSize; s++) {
                steps.add(new Step(buf.readUtf(8), buf.readUtf(96), buf.readVarInt(),
                        buf.readVarInt()));
            }
            tasks.add(new Task(target, requested, state, missing, steps));
        }
        return new ForgePanelStatePacket(inside, buildingId, name, level, catalog, tasks);
    }

    public static void handle(ForgePanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ForgePanelHooks.stateSink.accept(packet));
    }
}
