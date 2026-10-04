package com.xy2407.kingdom.common.network.faction;

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
 * 服务端 → 客户端：玩家的阵营状态，以及"接下来该弹哪张界面"。
 *
 * <p>这一个包同时承担三件事，省掉了两个只会传一个布尔值的回包：</p>
 * <ol>
 *   <li><b>首登弹窗</b>：{@code onboarded=false} 时客户端弹 {@code ui/start.html}；</li>
 *   <li><b>自建国列表</b>：{@code nations} 是别的玩家建的国家，追加到 start 页的自建卡槽，
 *       这样新玩家也能选它们；</li>
 *   <li><b>开建城界面</b>：{@code openCityCreate=true} 时客户端弹 {@code ui/city-create.html}
 *       （右键市政厅、但自己还没领地的情况），连同建城要用的市政厅坐标一起带过去。</li>
 * </ol>
 *
 * <p>选国 / 建国 / 建领地三个动作做完后，服务端都用它回推一次，客户端据此刷新或关界面——
 * 所以这三个动作**不需要各自的专用回包**。</p>
 */
public record FactionStatePacket(
        boolean onboarded,
        String nationId,
        String territoryId,
        String territoryName,
        boolean openCityCreate,
        int hallX,
        int hallY,
        int hallZ,
        List<NationEntry> nations
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FactionStatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "faction_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionStatePacket> STREAM_CODEC =
            StreamCodec.of(FactionStatePacket::encode, FactionStatePacket::decode);

    public FactionStatePacket {
        nationId = nationId == null ? "" : nationId;
        territoryId = territoryId == null ? "" : territoryId;
        territoryName = territoryName == null ? "" : territoryName;
        nations = nations == null ? List.of() : List.copyOf(nations);
    }

    /** 一份"不改动任何弹窗触发"的状态快照，用于选国 / 建领地做完后的回推。 */
    public static FactionStatePacket state(boolean onboarded, String nationId,
                                           String territoryId, String territoryName,
                                           List<NationEntry> nations) {
        return new FactionStatePacket(onboarded, nationId, territoryId, territoryName,
                false, 0, 0, 0, nations);
    }

    /**
     * 请求客户端打开建城界面，并带上市政厅的<b>完整方块坐标</b>。
     *
     * <p>Y 不能省：服务端收到建领地请求后要拿它去取那一格方块、验"这里真是市政厅"。
     * 靠玩家位置猜 Y 会差一格，校验必失败。</p>
     */
    public static FactionStatePacket openCityCreate(boolean onboarded, String nationId,
                                                    int hallX, int hallY, int hallZ,
                                                    List<NationEntry> nations) {
        return new FactionStatePacket(onboarded, nationId, "", "",
                true, hallX, hallY, hallZ, nations);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 一个可选的玩家自建国。
     *
     * <p>预置的 12 个阵营不在这个列表里——它们由 {@code ui/start.html} 硬编码成 12 张卡，
     * 服务端不重复存一份。</p>
     */
    public record NationEntry(String id, String name, String ruler, String motto,
                              String colorLeft, String colorRight,
                              String emblem, String emblemColor) {
    }

    // ---------------------------------------------------------------- 编码

    private static void encode(RegistryFriendlyByteBuf buf, FactionStatePacket packet) {
        buf.writeNbt(packet.toTag());
    }

    private static FactionStatePacket decode(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        return tag == null ? new FactionStatePacket(false, "", "", "", false, 0, 0, 0, List.of())
                : fromTag(tag);
    }

    private CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        root.putBoolean("onboarded", onboarded);
        root.putString("nationId", nationId);
        root.putString("territoryId", territoryId);
        root.putString("territoryName", territoryName);
        root.putBoolean("openCityCreate", openCityCreate);
        root.putInt("hallX", hallX);
        root.putInt("hallY", hallY);
        root.putInt("hallZ", hallZ);

        ListTag list = new ListTag();
        for (NationEntry nation : nations) {
            CompoundTag item = new CompoundTag();
            item.putString("id", nation.id());
            item.putString("name", nation.name());
            item.putString("ruler", nation.ruler());
            item.putString("motto", nation.motto());
            item.putString("colorLeft", nation.colorLeft());
            item.putString("colorRight", nation.colorRight());
            item.putString("emblem", nation.emblem());
            item.putString("emblemColor", nation.emblemColor());
            list.add(item);
        }
        root.put("nations", list);
        return root;
    }

    private static FactionStatePacket fromTag(CompoundTag root) {
        List<NationEntry> nations = new ArrayList<>();
        ListTag list = root.getList("nations", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag item = list.getCompound(index);
            nations.add(new NationEntry(
                    item.getString("id"), item.getString("name"),
                    item.getString("ruler"), item.getString("motto"),
                    item.getString("colorLeft"), item.getString("colorRight"),
                    item.getString("emblem"), item.getString("emblemColor")));
        }
        return new FactionStatePacket(
                root.getBoolean("onboarded"),
                root.getString("nationId"),
                root.getString("territoryId"),
                root.getString("territoryName"),
                root.getBoolean("openCityCreate"),
                root.getInt("hallX"),
                root.getInt("hallY"),
                root.getInt("hallZ"),
                nations);
    }

    /** 收到后在客户端线程上落地。 */
    public static void handle(FactionStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> FactionHooks.stateSink.accept(packet));
    }
}
