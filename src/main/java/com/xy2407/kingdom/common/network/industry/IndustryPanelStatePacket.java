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
 * 服务端 → 客户端：工业面板的配方卡片清单与可雇市民名册
 */
public record IndustryPanelStatePacket(boolean inside, String buildingName, int buildingLevel,
                                       String position, List<Recipe> recipes, List<Worker> workers)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<IndustryPanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "industry_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, IndustryPanelStatePacket> STREAM_CODEC =
            StreamCodec.of(IndustryPanelStatePacket::encode, IndustryPanelStatePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static IndustryPanelStatePacket outside() {
        return new IndustryPanelStatePacket(false, "", 0, "", List.of(), List.of());
    }

    public record Recipe(String id, String name, String icon, String inputs, String outputs,
                         int ticks, String status, List<UUID> workers) {
        public Recipe {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            icon = icon == null ? "" : icon;
            inputs = inputs == null ? "" : inputs;
            outputs = outputs == null ? "" : outputs;
            status = status == null ? "" : status;
            workers = workers == null ? List.of() : List.copyOf(workers);
        }
    }

    public record Worker(UUID id, String name, String skin, String note) {
        public Worker {
            name = name == null ? "" : name;
            skin = skin == null ? "" : skin;
            note = note == null ? "" : note;
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf, IndustryPanelStatePacket packet) {
        buf.writeBoolean(packet.inside());
        buf.writeUtf(packet.buildingName() == null ? "" : packet.buildingName(), 256);
        buf.writeVarInt(packet.buildingLevel());
        buf.writeUtf(packet.position() == null ? "" : packet.position(), 128);

        buf.writeVarInt(packet.recipes().size());
        for (Recipe recipe : packet.recipes()) {
            buf.writeUtf(recipe.id(), 128);
            buf.writeUtf(recipe.name(), 64);
            buf.writeUtf(recipe.icon(), 128);
            buf.writeUtf(recipe.inputs(), 256);
            buf.writeUtf(recipe.outputs(), 256);
            buf.writeVarInt(recipe.ticks());
            buf.writeUtf(recipe.status(), 32);
            buf.writeVarInt(recipe.workers().size());
            for (UUID worker : recipe.workers()) {
                buf.writeUUID(worker == null ? new UUID(0L, 0L) : worker);
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

    private static IndustryPanelStatePacket decode(RegistryFriendlyByteBuf buf) {
        boolean inside = buf.readBoolean();
        String buildingName = buf.readUtf(256);
        int buildingLevel = buf.readVarInt();
        String position = buf.readUtf(128);

        int recipeCount = buf.readVarInt();
        List<Recipe> recipes = new ArrayList<>(recipeCount);
        for (int index = 0; index < recipeCount; index++) {
            String id = buf.readUtf(128);
            String name = buf.readUtf(64);
            String icon = buf.readUtf(128);
            String inputs = buf.readUtf(256);
            String outputs = buf.readUtf(256);
            int ticks = buf.readVarInt();
            String status = buf.readUtf(32);
            int workerCount = buf.readVarInt();
            List<UUID> workers = new ArrayList<>(workerCount);
            for (int worker = 0; worker < workerCount; worker++) {
                workers.add(buf.readUUID());
            }
            recipes.add(new Recipe(id, name, icon, inputs, outputs, ticks, status, workers));
        }

        int rosterCount = buf.readVarInt();
        List<Worker> workers = new ArrayList<>(rosterCount);
        for (int index = 0; index < rosterCount; index++) {
            workers.add(new Worker(buf.readUUID(), buf.readUtf(64), buf.readUtf(128), buf.readUtf(16)));
        }

        return new IndustryPanelStatePacket(inside, buildingName, buildingLevel, position, recipes, workers);
    }

    public static void handle(IndustryPanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> IndustryPanelHooks.stateSink.accept(packet));
    }
}
