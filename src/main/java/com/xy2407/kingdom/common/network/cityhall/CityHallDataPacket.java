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
 * 服务端 → 客户端：市政厅界面要显示的城镇数据。
 *
 * <p>和 {@link CityHallOpenPacket} 分工明确：那个只管"把界面打开、并告诉客户端地图以哪一点为中心"，
 * 这个管"界面里填什么数"。方块右键时两个一起发，数据包在前——这样界面刚打开时数据已经就位，
 * 不会先闪一帧占位值。客户端仍然做了兜底：万一数据晚到，{@code CityHallScreen} 会收到后重填一次。</p>
 *
 * <p><b>这些数从哪来。</b>服务端目前**没有**城镇级的金库 / 凝聚力 / 忠诚度 / 幸福度 / 世界事件 /
 * 政务 / 资金流水这些概念，所以本包把它们拆成两类：</p>
 * <ul>
 *   <li><b>真数据</b>：{@code population}（{@code NpcManager} 的市民总数）、{@code buildings}
 *       （{@code BuildingRegistry} 的已完工建筑数）、{@code day}（{@code DateSystem}）、
 *       {@code citizens} / {@code structures} 两个列表——都是现成查得到的。</li>
 *   <li><b>待接入</b>：{@code gold} / {@code unity} / {@code loyalty} / {@code happiness} /
 *       {@code nation} / {@code ally} / {@code foe} / {@code policy} 与三个条目列表。
 *       对应的服务端存储**尚不存在**，这里一律给中性值（0 / 空串 / 空列表），
 *       界面会显示成"—"或空态提示。等城镇数据落库后，只要换掉
 *       {@code CityHallDataCollector} 里的取值即可，包结构与页面都不用再动。</li>
 * </ul>
 *
 * <p>编码走 NBT：字段多、还带三个列表，逐个 {@code writeUtf} 会又长又容易漏；
 * NBT 一次 {@code writeNbt}/{@code readNbt} 搞定，列表直接用 {@code ListTag}。</p>
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

    /** 无数据时的空包：各字段都是中性值，界面呈现为空态而不是报错。 */
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

    // ---------------------------------------------------------------- 行

    /** 市民名录的一行。{@code skin} 交给 &lt;kingdom-entity-avatar skin="..."&gt;，空串则用默认脸。 */
    public record Citizen(String name, String skin, String job, String shift) {
    }

    /** 建筑名录的一行。{@code x} / {@code y} / {@code z} 是方块坐标（界面里显示成 "x, z"）。 */
    public record Structure(String name, String category, int x, int y, int z) {
    }

    /**
     * 通用条目行，事件 / 政务 / 流水三处共用。
     *
     * @param key   左侧的标签或名目
     * @param value 右侧的值
     * @param tone  0 中性、1 收（绿）、-1 支（红），只影响流水那处的配色
     */
    public record Line(String key, String value, int tone) {
    }

    // ---------------------------------------------------------------- 编码

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

    /** 收到数据后在客户端线程上落地。 */
    public static void handle(CityHallDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> CityHallHooks.dataSink.accept(packet));
    }
}
