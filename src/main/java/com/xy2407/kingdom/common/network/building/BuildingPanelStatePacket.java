package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：当前所处建筑的判定结果，以及该开哪个操作面板。
 *
 * <p>载荷为空串表示不在任何建筑内。</p>
 *
 * <p><b>页面路径由服务端决定</b>：建筑类型（农业 / 工业 / 养殖 / 商业）与商业子类
 * （出售铺 / 餐厅）只有服务端读得到蓝图元数据，客户端照着 {@link #page()} 开就是了，
 * 不必自己再查一遍蓝图——那也是磁盘 IO。</p>
 *
 * @param name      建筑名（蓝图名）；不在建筑内时为空
 * @param level     建筑等级
 * @param farmSlots 种植格上限；保留给旧判定用，新代码请用 {@link #page()}
 * @param page      该开的面板页面路径（相对 {@code assets/apricityui/apricity/}）；
 *                  空串表示这座建筑还没有对应面板，客户端只弹一条提示
 * @param pos       建筑原点坐标，已格式化成 {@code x, y, z}，直接填进信息框
 */
public record BuildingPanelStatePacket(String name, int level, int farmSlots, String page, String pos)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingPanelStatePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_panel_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPanelStatePacket> STREAM_CODEC =
            StreamCodec.of(BuildingPanelStatePacket::encode, BuildingPanelStatePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static BuildingPanelStatePacket outside() {
        return new BuildingPanelStatePacket("", 0, 0, "", "");
    }

    public boolean inside() {
        return !name.isEmpty();
    }

    /** 有没有农业额度。旧字段，新代码请用 {@link #hasPage()}。 */
    public boolean farmable() {
        return farmSlots > 0;
    }

    /** 这座建筑有没有对应的操作面板。 */
    public boolean hasPage() {
        return page != null && !page.isBlank();
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingPanelStatePacket packet) {
        buf.writeUtf(packet.name() == null ? "" : packet.name(), 256);
        buf.writeInt(packet.level());
        buf.writeInt(packet.farmSlots());
        buf.writeUtf(packet.page() == null ? "" : packet.page(), 128);
        buf.writeUtf(packet.pos() == null ? "" : packet.pos(), 64);
    }

    private static BuildingPanelStatePacket decode(RegistryFriendlyByteBuf buf) {
        return new BuildingPanelStatePacket(buf.readUtf(256), buf.readInt(), buf.readInt(),
                buf.readUtf(128), buf.readUtf(64));
    }

    public static void handle(BuildingPanelStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuildingPanelHooks.stateSink.accept(packet));
    }
}
