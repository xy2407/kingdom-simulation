package com.xy2407.kingdom.common.network.mapoverlay;

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
 * 服务端 → 客户端：地图叠层数据——领地列表（含区块集合）、可视范围内的建筑列表与世界道路区块（W14）
 */
public record MapOverlayDataPacket(
        long revision,
        List<TerritoryEntry> territories,
        List<BuildingEntry> buildings,
        List<RoadChunkEntry> roads
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MapOverlayDataPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "map_overlay_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MapOverlayDataPacket> STREAM_CODEC =
            StreamCodec.of(MapOverlayDataPacket::encode, MapOverlayDataPacket::decode);

    public static final MapOverlayDataPacket EMPTY =
            new MapOverlayDataPacket(0L, List.of(), List.of(), List.of());

    public MapOverlayDataPacket {
        territories = territories == null ? List.of() : List.copyOf(territories);
        buildings = buildings == null ? List.of() : List.copyOf(buildings);
        roads = roads == null ? List.of() : List.copyOf(roads);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record TerritoryEntry(String id, String name, String ownerName, String nationLabel,
                                 String tier, int population, int centerX, int centerZ,
                                 long[] chunks, boolean self, boolean ally) {

        public TerritoryEntry {
            chunks = chunks == null ? new long[0] : chunks;
        }
    }

    public record BuildingEntry(String id, String name, String type,
                                int minX, int minZ, int maxX, int maxZ, String territoryId) {
    }

    // cells = 该区块内已铺的道路格（BlockPos.asLong，含真实地表 y），渲染按格画、传送按区块中心
    public record RoadChunkEntry(int chunkX, int chunkZ, long[] cells) {

        public RoadChunkEntry {
            cells = cells == null ? new long[0] : cells;
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf, MapOverlayDataPacket packet) {
        buf.writeNbt(packet.toTag());
    }

    private static MapOverlayDataPacket decode(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        return tag == null ? EMPTY : fromTag(tag);
    }

    private CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        root.putLong("revision", revision);

        ListTag territoryTags = new ListTag();
        for (TerritoryEntry entry : territories) {
            CompoundTag item = new CompoundTag();
            item.putString("id", entry.id());
            item.putString("name", entry.name());
            item.putString("ownerName", entry.ownerName());
            item.putString("nationLabel", entry.nationLabel());
            item.putString("tier", entry.tier());
            item.putInt("population", entry.population());
            item.putInt("centerX", entry.centerX());
            item.putInt("centerZ", entry.centerZ());
            item.putLongArray("chunks", entry.chunks());
            item.putBoolean("self", entry.self());
            item.putBoolean("ally", entry.ally());
            territoryTags.add(item);
        }
        root.put("territories", territoryTags);

        ListTag buildingTags = new ListTag();
        for (BuildingEntry entry : buildings) {
            CompoundTag item = new CompoundTag();
            item.putString("id", entry.id());
            item.putString("name", entry.name());
            item.putString("type", entry.type());
            item.putInt("minX", entry.minX());
            item.putInt("minZ", entry.minZ());
            item.putInt("maxX", entry.maxX());
            item.putInt("maxZ", entry.maxZ());
            item.putString("territoryId", entry.territoryId());
            buildingTags.add(item);
        }
        root.put("buildings", buildingTags);

        ListTag roadTags = new ListTag();
        for (RoadChunkEntry entry : roads) {
            CompoundTag item = new CompoundTag();
            item.putInt("chunkX", entry.chunkX());
            item.putInt("chunkZ", entry.chunkZ());
            item.putLongArray("cells", entry.cells());
            roadTags.add(item);
        }
        root.put("roads", roadTags);
        return root;
    }

    private static MapOverlayDataPacket fromTag(CompoundTag root) {
        List<TerritoryEntry> territories = new ArrayList<>();
        ListTag territoryTags = root.getList("territories", Tag.TAG_COMPOUND);
        for (int index = 0; index < territoryTags.size(); index++) {
            CompoundTag item = territoryTags.getCompound(index);
            territories.add(new TerritoryEntry(
                    item.getString("id"), item.getString("name"),
                    item.getString("ownerName"), item.getString("nationLabel"),
                    item.getString("tier"), item.getInt("population"),
                    item.getInt("centerX"), item.getInt("centerZ"),
                    item.getLongArray("chunks"),
                    item.getBoolean("self"), item.getBoolean("ally")));
        }

        List<BuildingEntry> buildings = new ArrayList<>();
        ListTag buildingTags = root.getList("buildings", Tag.TAG_COMPOUND);
        for (int index = 0; index < buildingTags.size(); index++) {
            CompoundTag item = buildingTags.getCompound(index);
            buildings.add(new BuildingEntry(
                    item.getString("id"), item.getString("name"), item.getString("type"),
                    item.getInt("minX"), item.getInt("minZ"),
                    item.getInt("maxX"), item.getInt("maxZ"),
                    item.getString("territoryId")));
        }

        List<RoadChunkEntry> roads = new ArrayList<>();
        ListTag roadTags = root.getList("roads", Tag.TAG_COMPOUND);
        for (int index = 0; index < roadTags.size(); index++) {
            CompoundTag item = roadTags.getCompound(index);
            roads.add(new RoadChunkEntry(
                    item.getInt("chunkX"), item.getInt("chunkZ"),
                    item.getLongArray("cells")));
        }

        return new MapOverlayDataPacket(root.getLong("revision"), territories, buildings, roads);
    }

    public static void handle(MapOverlayDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> MapOverlayHooks.dataSink.accept(packet));
    }
}
