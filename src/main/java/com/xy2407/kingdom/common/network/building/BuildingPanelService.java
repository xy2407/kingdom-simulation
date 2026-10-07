package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.server.building.BuildingRegistry;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 建筑面板的服务端侧处理：按玩家当前位置判定所处建筑并回包。
 *
 * <p>位置由服务端从 {@code context.player()} 直接取，不信任客户端传来的坐标。</p>
 *
 * <p><b>该开哪个面板也在这里决定</b>：建筑类型与商业子类只有服务端读得到蓝图元数据，
 * 客户端照着 {@code page} 开就行。</p>
 */
public final class BuildingPanelService {

    private BuildingPanelService() {
    }

    static void respond(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        BuildingRecord hit = BuildingRegistry.INSTANCE.locate(player);
        if (hit == null) {
            PacketDistributor.sendToPlayer(player, BuildingPanelStatePacket.outside());
            return;
        }
        PacketDistributor.sendToPlayer(player, new BuildingPanelStatePacket(
                hit.name(), hit.level(), hit.farmSlots(), pageOf(BuildingRegistry.INSTANCE.metaOf(hit)),
                hit.min().getX() + ", " + hit.min().getY() + ", " + hit.min().getZ()));
    }

    /**
     * 建筑类型 → 操作面板页面路径。
     *
     * <p>商业分两支：出售铺与餐厅的面板完全不同，靠 {@code subtype} 分流
     * （{@code BuildingMeta#SUBTYPE_SHOP} / {@code SUBTYPE_RESTAURANT}）。</p>
     *
     * <p>没有对应面板的类型返回空串，客户端会退回成一条提示。</p>
     */
    private static String pageOf(BuildingMeta meta) {
        if (meta == null || meta.type() == null) {
            return "";
        }
        return switch (meta.type()) {
            case AGRICULTURE -> "buildings/farm.html";
            case INDUSTRY -> "buildings/industry.html";
            case LIVESTOCK -> "buildings/ranch.html";
            case COMMERCIAL -> meta.isShop()
                    ? "buildings/shop.html"
                    : meta.isRestaurant() ? "buildings/restaurant.html" : "";
            case MILITARY -> "buildings/military.html";
            case RESIDENTIAL -> "buildings/residential.html";
            case STORAGE -> "buildings/warehouse.html";
            default -> "";
        };
    }
}
