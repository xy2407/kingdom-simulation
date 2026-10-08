package com.xy2407.kingdom.common.network.cityhall;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.ModMain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：市政厅界面要显示的城镇数据（人口、建筑数与两份名录）。
 */
public record CityHallDataPacket(
        String townName,
        int day,
        int population,
        int populationMax,
        int buildings,
        int buildingsMax,
        int vassals,
        int vassalsMax,
        long gold,
        long goldMax,
        int unity,
        int loyalty,
        int happiness,
        String nation,
        String ally,
        String foe,
        String policy,
        List<Citizen> citizens,
        List<Structure> structures,
        List<Line> events,
        List<Line> todos,
        List<Line> ledger
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CityHallDataPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "city_hall_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CityHallDataPacket> STREAM_CODEC =
            StreamCodec.of(CityHallDataPacket::encode, CityHallDataPacket::decode);

    public static final CityHallDataPacket EMPTY = new CityHallDataPacket(
            "", 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0, 0, 0,
            "", "", "", "",
            List.of(), List.of(), List.of(), List.of(), List.of());

    public CityHallDataPacket {
        citizens = citizens == null ? List.of() : List.copyOf(citizens);
        structures = structures == null ? List.of() : List.copyOf(structures);
        events = events == null ? List.of() : List.copyOf(events);
        todos = todos == null ? List.of() : List.copyOf(todos);
        ledger = ledger == null ? List.of() : List.copyOf(ledger);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Citizen(String name, String skin, String job, String shift) {
    }

    public record Structure(String name, String category, int x, int y, int z) {
    }

    public record Line(String key, String value, int tone) {
    }

    private static void encode(RegistryFriendlyByteBuf buf, CityHallDataPacket packet) {
        buf.writeNbt(packet.toTag());
    }

    private static CityHallDataPacket decode(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        return tag == null ? EMPTY : fromTag(tag);
    }

    private CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        root.putString("townName", townName);
        root.putInt("day", day);
        root.putInt("population", population);
        root.putInt("populationMax", populationMax);
        root.putInt("buildings", buildings);
        root.putInt("buildingsMax", buildingsMax);
        root.putInt("vassals", vassals);
        root.putInt("vassalsMax", vassalsMax);
        root.putLong("gold", gold);
        root.putLong("goldMax", goldMax);
        root.putInt("unity", unity);
        root.putInt("loyalty", loyalty);
        root.putInt("happiness", happiness);
        root.putString("nation", nation);
        root.putString("ally", ally);
        root.putString("foe", foe);
        root.putString("policy", policy);

        ListTag citizenTags = new ListTag();
        for (Citizen citizen : citizens) {
            CompoundTag item = new CompoundTag();
            item.putString("name", citizen.name());
            item.putString("skin", citizen.skin());
            item.putString("job", citizen.job());
            item.putString("shift", citizen.shift());
            citizenTags.add(item);
        }
        root.put("citizens", citizenTags);

        ListTag structureTags = new ListTag();
        for (Structure structure : structures) {
            CompoundTag item = new CompoundTag();
            item.putString("name", structure.name());
            item.putString("category", structure.category());
            item.putInt("x", structure.x());
            item.putInt("y", structure.y());
            item.putInt("z", structure.z());
            structureTags.add(item);
        }
        root.put("structures", structureTags);

        root.put("events", linesToTag(events));
        root.put("todos", linesToTag(todos));
        root.put("ledger", linesToTag(ledger));
        return root;
    }

    private static ListTag linesToTag(List<Line> lines) {
        ListTag tags = new ListTag();
        for (Line line : lines) {
            CompoundTag item = new CompoundTag();
            item.putString("key", line.key());
            item.putString("value", line.value());
            item.putInt("tone", line.tone());
            tags.add(item);
        }
        return tags;
    }

    private static CityHallDataPacket fromTag(CompoundTag root) {
        List<Citizen> citizens = new ArrayList<>();
        ListTag citizenTags = root.getList("citizens", Tag.TAG_COMPOUND);
        for (int index = 0; index < citizenTags.size(); index++) {
            CompoundTag item = citizenTags.getCompound(index);
            citizens.add(new Citizen(
                    item.getString("name"),
                    item.getString("skin"),
                    item.getString("job"),
                    item.getString("shift")));
        }

        List<Structure> structures = new ArrayList<>();
        ListTag structureTags = root.getList("structures", Tag.TAG_COMPOUND);
        for (int index = 0; index < structureTags.size(); index++) {
            CompoundTag item = structureTags.getCompound(index);
            structures.add(new Structure(
                    item.getString("name"),
                    item.getString("category"),
                    item.getInt("x"),
                    item.getInt("y"),
                    item.getInt("z")));
        }

        return new CityHallDataPacket(
                root.getString("townName"),
                root.getInt("day"),
                root.getInt("population"),
                root.getInt("populationMax"),
                root.getInt("buildings"),
                root.getInt("buildingsMax"),
                root.getInt("vassals"),
                root.getInt("vassalsMax"),
                root.getLong("gold"),
                root.getLong("goldMax"),
                root.getInt("unity"),
                root.getInt("loyalty"),
                root.getInt("happiness"),
                root.getString("nation"),
                root.getString("ally"),
                root.getString("foe"),
                root.getString("policy"),
                citizens,
                structures,
                linesFromTag(root.getList("events", Tag.TAG_COMPOUND)),
                linesFromTag(root.getList("todos", Tag.TAG_COMPOUND)),
                linesFromTag(root.getList("ledger", Tag.TAG_COMPOUND)));
    }

    private static List<Line> linesFromTag(ListTag tags) {
        List<Line> lines = new ArrayList<>(tags.size());
        for (int index = 0; index < tags.size(); index++) {
            CompoundTag item = tags.getCompound(index);
            lines.add(new Line(item.getString("key"), item.getString("value"), item.getInt("tone")));
        }
        return lines;
    }

    public static void handle(CityHallDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> CityHallHooks.dataSink.accept(packet));
    }
}
