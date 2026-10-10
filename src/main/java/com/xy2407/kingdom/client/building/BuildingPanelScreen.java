package com.xy2407.kingdom.client.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.common.network.building.BuildingStorageRequestPacket;
import com.xy2407.kingdom.common.network.building.ResidentActionPacket;
import com.xy2407.kingdom.common.network.building.ResidentListPacket;
import com.xy2407.kingdom.common.network.building.ResidentListRequestPacket;
import com.xy2407.kingdom.common.network.building.RestaurantMenuPacket;
import com.xy2407.kingdom.common.network.building.RestaurantMenuRequestPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 各类建筑操作面板的共用宿主：工业/养殖/出售铺/餐厅/军事/住宅/仓库都开这一个类，底栏仓库按钮由它按页显隐。
 */
@OnlyIn(Dist.CLIENT)
public final class BuildingPanelScreen extends ApricityScreen {

    private static final String ID_WAREHOUSE = "bp-warehouse";
    private static final String ID_WAREHOUSE_ROW = "bp-warehouse-row";
    private static final String ID_CLOSE = "bp-close";
    private static final String ID_NAME = "bp-name";
    private static final String ID_LEVEL = "bp-level";
    private static final String CLASS_FOOT_OFF = "bp-foot-off";

    /* 住宅页的住户区（residential.html）：名册与农田的「打理人」同一套外观与交互 */
    private static final String RESIDENT_PREFIX = "rd";
    private static final String ID_BED_NOTE = "rd-bed-note";
    private static final String ID_BEDS = "rd-beds";
    private static final String ID_RESIDENT_COUNT = "rd-count";
    private static final String ID_RESIDENT_LIST = "rd-workers";
    private static final String ID_RESIDENT_CLEAR = "rd-clear";
    private static final String ID_RESIDENT_SEARCH = "rd-search";
    private static final String ID_RESIDENT_SEARCH_CLEAR = "rd-search-clear";

    /** 床位指示器最多画几格；再多就只靠标题栏那行数字交代。 */
    private static final int MAX_BED_CELLS = 32;

    /* 餐厅页的菜单区（restaurant.html）：菜品卡片由 Java 按 json 的 menu 注入 */
    private static final String RESTAURANT_PREFIX = "rs";
    private static final String ID_CHEF = "rs-worker";
    private static final String ID_DISH_COUNT = "rs-count";
    private static final String ID_MENU = "rs-menu";

    private static final Map<String, String> PREFIXES = Map.of(
            "industry", "im",
            "ranch", "rm",
            "restaurant", "rs",
            "shop", "sh",
            "military", "mt",
            "residential", "rd",
            "warehouse", "wh");

    private static final Map<String, String> MAIN_BUTTONS = Map.of(
            "industry", "im-start",
            "ranch", "rm-start",
            "restaurant", "rs-pick",
            "shop", "sh-shelve",
            "military", "mt-army",
            // 住宅没有底栏主按钮：入住已经变成名册里的点选
            "residential", "",
            "warehouse", "wh-link");

    private static final Set<String> WAREHOUSE_PAGES = Set.of(
            "im", "rm", "rs", "sh", "mt", "wh");

    private final String page;
    private final String buildingName;
    private final int buildingLevel;
    private final String buildingPos;
    private boolean applied;

    /** 最近一次收到的住户状态：搜索框变动时要用它就地重画名册，不必等下一次回包。 */
    private ResidentListPacket latestResidents = ResidentListPacket.outside();
    private String residentFilter = "";
    private String shownResidentFilter;

    public BuildingPanelScreen(String page, String buildingName, int buildingLevel, String buildingPos) {
        super(page);
        this.page = page == null ? "" : page;
        this.buildingName = buildingName == null ? "" : buildingName;
        this.buildingLevel = buildingLevel;
        this.buildingPos = buildingPos == null ? "" : buildingPos;
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    @Override
    protected void init() {
        super.init();
        applied = false;
        sync();
    }

    @Override
    public void tick() {
        super.tick();
        if (!applied) {
            sync();
            return;
        }
        // 搜索框逐字生效：只有内容真的变了才重画名册，避免每 tick 刷 DOM
        if (RESIDENT_PREFIX.equals(prefixOf(page))) {
            Document document = getLinkedDocument();
            Element search = document == null ? null : document.getElementById(ID_RESIDENT_SEARCH);
            if (search instanceof Input input && !input.getValue().equals(shownResidentFilter)) {
                shownResidentFilter = input.getValue();
                residentFilter = shownResidentFilter;
                applyResidents(latestResidents);
            }
        }
    }

    private void sync() {
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        applied = true;
        String prefix = prefixOf(page);
        Element warehouse = document.getElementById(ID_WAREHOUSE);
        if (warehouse != null) {
            warehouse.addEventListener("click", event -> openWarehouse());
        }
        setOff(document.getElementById(ID_WAREHOUSE_ROW), CLASS_FOOT_OFF, !WAREHOUSE_PAGES.contains(prefix));
        Element close = document.getElementById(ID_CLOSE);
        if (close != null) {
            close.addEventListener("click", event -> onClose());
        }
        Element main = document.getElementById(MAIN_BUTTONS.getOrDefault(prefix, ""));
        if (main != null) {
            if (RESTAURANT_PREFIX.equals(prefix)) {
                // 餐厅的「选择菜品」= 重新拉一次菜单（"食材够不够"要现查仓库）
                main.addEventListener("click", event -> requestMenu());
            } else {
                main.addEventListener("click", event -> hint("该功能尚未接入"));
            }
        }
        if (RESIDENT_PREFIX.equals(prefix)) {
            // 名册里的两个按钮在这里绑；点行本身由 applyResidents 在生成行时逐个绑
            Element clear = document.getElementById(ID_RESIDENT_CLEAR);
            if (clear != null) {
                clear.addEventListener("click", event ->
                        PacketDistributor.sendToServer(ResidentActionPacket.clearResidents()));
            }
            Element searchClear = document.getElementById(ID_RESIDENT_SEARCH_CLEAR);
            if (searchClear != null) {
                searchClear.addEventListener("click", event -> {
                    shownResidentFilter = "";
                    residentFilter = "";
                    applyResidents(latestResidents);
                });
            }
            // 打开面板即拉一次，玩家不必先点按钮
            requestResidents();
        } else if (RESTAURANT_PREFIX.equals(prefix)) {
            requestMenu();
        }
        text(document, ID_NAME, buildingName.isBlank() ? "建筑" : buildingName);
        text(document, ID_LEVEL, "Lv." + buildingLevel);
        text(document, prefix + "-pos", buildingPos.isBlank() ? "—" : buildingPos);
        text(document, prefix + "-blueprint", buildingName.isBlank() ? "—" : buildingName);
    }

    private static void requestResidents() {
        PacketDistributor.sendToServer(new ResidentListRequestPacket());
    }

    private static void requestMenu() {
        PacketDistributor.sendToServer(new RestaurantMenuRequestPacket());
    }

    /**
     * 收到菜单：把菜品卡片注入 {@code rs-menu}。
     * 卡片是只读展示（菜名 / 食材 / 售价），食材不齐的那张画灰 —— 后厨能不能做一眼可见。
     */
    void applyRestaurantMenu(RestaurantMenuPacket packet) {
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        text(document, ID_CHEF, packet.chefCount() > 0 ? packet.chefCount() + " 人" : "未指派");
        text(document, ID_DISH_COUNT, packet.dishes().size() + " 道");
        Element menu = document.getElementById(ID_MENU);
        if (menu == null) {
            return;
        }
        StringBuilder html = new StringBuilder();
        if (packet.dishes().isEmpty()) {
            html.append("<div class=\"bp-empty\">这家餐厅还没有上菜单</div>");
        }
        for (RestaurantMenuPacket.Dish dish : packet.dishes()) {
            html.append("<div class=\"bp-dish").append(dish.ready() ? "" : " off").append("\">")
                    .append("<span class=\"bp-dish-name\">").append(escape(dish.name())).append("</span>")
                    .append("<span class=\"bp-dish-in\">").append(escape(dish.inputText())).append("</span>")
                    .append("<span class=\"bp-tag\">").append(dish.price()).append("</span>")
                    .append("</div>");
        }
        menu.setInnerHTML(html.toString());
    }

    /**
     * 收到服务端下发的住户状态：画床位指示器，再填名册并给每行绑点击。
     * 名册是"一份市民名单 + 谁已入住"（与农田的「打理人」同一套），点一行即切换入住状态。
     * 行用固定 id（{@code rd-worker-i}）而不是事件委托 —— AUI 是自研事件系统，
     * "按 id 取回元素再逐个绑"最稳，也避开了 event.target 的口径问题。
     */
    void applyResidents(ResidentListPacket packet) {
        latestResidents = packet;
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        List<UUID> residents = packet.residents();
        text(document, ID_BED_NOTE, residents.size() + " / " + packet.bedTotal());
        text(document, ID_RESIDENT_COUNT, residents.size() + " 人");
        refreshBeds(document, residents.size(), packet.bedTotal());
        refreshResidentList(document, packet, residents);
    }

    /** 床位指示器：一格一床，有人的点亮。格子太多时只画前 {@link #MAX_BED_CELLS} 格。 */
    private void refreshBeds(Document document, int occupied, int total) {
        Element host = document.getElementById(ID_BEDS);
        if (host == null) {
            return;
        }
        int cells = Math.min(Math.max(total, 0), MAX_BED_CELLS);
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < cells; index++) {
            html.append("<span class=\"bp-bed").append(index < occupied ? " on" : "").append("\"></span>");
        }
        host.setInnerHTML(html.toString());
    }

    /** 名册：按搜索词过滤；行尾说明由服务端给（饥饿 / 另有住处）。 */
    private void refreshResidentList(Document document, ResidentListPacket packet, List<UUID> residents) {
        Element host = document.getElementById(ID_RESIDENT_LIST);
        if (host == null) {
            return;
        }
        if (!packet.inside()) {
            host.setInnerHTML("<div class=\"bp-empty\">载入中…</div>");
            return;
        }
        List<ResidentListPacket.Worker> visible = new ArrayList<>();
        for (ResidentListPacket.Worker worker : packet.workers()) {
            if (residentFilter.isBlank() || worker.name().contains(residentFilter)) {
                visible.add(worker);
            }
        }
        if (visible.isEmpty()) {
            host.setInnerHTML("<div class=\"bp-empty\">"
                    + (packet.workers().isEmpty() ? "城里还没有市民" : "没有匹配的市民") + "</div>");
            return;
        }
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < visible.size(); index++) {
            ResidentListPacket.Worker worker = visible.get(index);
            boolean hired = residents.contains(worker.id());
            String state = worker.note().isBlank() ? (hired ? "住户" : "空闲") : worker.note();
            html.append("<div class=\"bp-item").append(hired ? " on" : "")
                    .append("\" id=\"rd-worker-").append(index).append("\">")
                    .append("<kingdom-entity-avatar class=\"bp-face\" skin=\"")
                    .append(escape(worker.skin())).append("\"></kingdom-entity-avatar>")
                    .append("<span class=\"bp-name\">").append(escape(worker.name())).append("</span>")
                    .append("<span class=\"bp-sub\">").append(escape(state)).append("</span>")
                    .append("</div>");
        }
        host.setInnerHTML(html.toString());
        for (int index = 0; index < visible.size(); index++) {
            UUID workerId = visible.get(index).id();
            Element row = document.getElementById("rd-worker-" + index);
            if (row != null) {
                row.addEventListener("click", event ->
                        PacketDistributor.sendToServer(ResidentActionPacket.toggleResident(workerId)));
            }
        }
    }

    /** 市民名字进 innerHTML 前转义，免得起名带尖括号把结构打破。 */
    private static String escape(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String prefixOf(String page) {
        if (page == null) {
            return "";
        }
        int slash = page.lastIndexOf('/');
        String file = slash < 0 ? page : page.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        return PREFIXES.getOrDefault(dot < 0 ? file : file.substring(0, dot), "");
    }

    private void openWarehouse() {
        PacketDistributor.sendToServer(new BuildingStorageRequestPacket());
    }

    private static void setOff(Element element, String token, boolean off) {
        if (element != null) {
            element.getClassList().toggle(token, off);
        }
    }

    private static void hint(String message) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static void text(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setTextContent(value);
        }
    }
}
