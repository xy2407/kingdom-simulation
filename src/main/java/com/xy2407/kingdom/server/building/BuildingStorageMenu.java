package com.xy2407.kingdom.server.building;

import com.sighs.apricityui.network.handler.PendingMenu;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.network.building.BuildingStorageOpenPacket;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 打开建筑仓库界面（服务端入口）。
 *
 * <p>与市民行装（{@code NpcBackpackMenu}）同一个套路，只有两点不同：</p>
 * <ol>
 *   <li>数据源是 {@code saveddata} 而不是 {@code entity} —— 建筑没有实体，
 *       容器由 AUI 的 {@code ApricitySavedData} 承载，一座建筑一份
 *       （名字里带建筑 id，见 {@link BuildingStorageStore#dataName}）；</li>
 *   <li>打开前必须先把数据库里的内容灌进容器，顺序反了的话界面会先显示空仓库
 *       （{@link BuildingStorageStore#handlerOf} 干的就是这件事）。</li>
 * </ol>
 *
 * <p><b>容器容量恒为 {@value #MAX_SLOTS} 格</b>，与页面里静态写死的槽位数一一对应。</p>
 * <p>蓝图给的 {@code storage_slots} 只决定"能用几格"：它随包发给客户端，
 * 由客户端把多余的槽位隐藏掉。这样两端不需要为不同建筑准备不同的页面，
 * 也不会出现"声明容量 27、页面画了 54 格"导致槽位绑不上数据源的情况。</p>
 */
public final class BuildingStorageMenu {

    /** 页面路径，相对 {@code assets/apricityui/apricity/}。 */
    public static final String PAGE = "buildings/storage.html";

    /** 页面里画了多少格，也是容器的固定容量。与 {@code storage.html} 的槽位数必须一致。 */
    public static final int MAX_SLOTS = 54;

    private BuildingStorageMenu() {
    }

    /**
     * 按**玩家实际位置**找建筑并开仓库。所有建筑面板底栏的「打开仓库」都走这里。
     *
     * <p>三种失败都给一句明话，不然点下去毫无反应、只能干瞪眼：不在这座建筑里、
     * 这座建筑不是仓库（蓝图 json 没写 {@code storage_slots}）、以及坐标判定失败。</p>
     */
    public static void openFor(ServerPlayer player) {
        if (player == null) {
            return;
        }
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            player.displayClientMessage(Component.literal("这里没有已完工的建筑"), true);
            return;
        }
        int capacity = BuildingRegistry.INSTANCE.storageSlotsOf(building);
        if (capacity <= 0) {
            player.displayClientMessage(Component.literal(
                    building.name() + " 没有仓库：蓝图 json 里没写 storage_slots"), true);
            return;
        }
        open(player, building, capacity);
    }

    /**
     * @param capacity 蓝图声明的可用格数；≤0 表示这座建筑不是仓库，直接不开
     * @return 是否打开了界面
     */
    public static boolean open(ServerPlayer player, BuildingRecord building, int capacity) {
        if (player == null || building == null) {
            return false;
        }
        int usable = Math.min(capacity, MAX_SLOTS);
        if (usable <= 0) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        /* 先把数据库那份灌进容器，再开界面——顺序反了玩家会看到空仓库 */
        BuildingStorageStore.INSTANCE.handlerOf(server, building.id(), MAX_SLOTS);
        PacketDistributor.sendToPlayer(player,
                new BuildingStorageOpenPacket(building.name(), usable, MAX_SLOTS));
        new PendingMenu(player, PAGE).bind(binding -> binding
                .saveddata(BuildingStorageStore.dataName(building.id()), MAX_SLOTS)
                .player());
        return true;
    }
}
