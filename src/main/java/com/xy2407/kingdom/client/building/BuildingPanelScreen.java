package com.xy2407.kingdom.client.building;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.client.aui.AuiLazyList;
import com.xy2407.kingdom.common.network.building.BuildingStorageRequestPacket;
import com.xy2407.kingdom.common.network.building.ResidentActionPacket;
import com.xy2407.kingdom.common.network.building.ResidentListPacket;
import com.xy2407.kingdom.common.network.building.ResidentListRequestPacket;
import com.xy2407.kingdom.common.network.building.RestaurantLayoutRequestPacket;
import com.xy2407.kingdom.common.network.building.RestaurantMenuPacket;
import com.xy2407.kingdom.common.network.building.RestaurantMenuRequestPacket;
import com.xy2407.kingdom.common.network.shop.ShopPanelActionPacket;
import com.xy2407.kingdom.common.network.shop.ShopPanelRequestPacket;
import com.xy2407.kingdom.common.network.shop.ShopPanelStatePacket;

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

    private static final String RESIDENT_PREFIX = "rd";
    private static final String ID_BED_NOTE = "rd-bed-note";
    private static final String ID_BEDS = "rd-beds";
    private static final String ID_RESIDENT_COUNT = "rd-count";
    private static final String ID_RESIDENT_LIST = "rd-workers";
    private static final String ID_RESIDENT_CLEAR = "rd-clear";
    private static final String ID_RESIDENT_SEARCH = "rd-search";
    private static final String ID_RESIDENT_SEARCH_CLEAR = "rd-search-clear";

    private static final int MAX_BED_CELLS = 32;

    private static final String RESTAURANT_PREFIX = "rs";
    private static final String ID_CHEF = "rs-worker";
    private static final String ID_DISH_COUNT = "rs-count";
    private static final String ID_MENU = "rs-menu";

    private static final String SHOP_PREFIX = "sh";
    private static final String ID_SHOP_WORKER = "sh-worker";
    private static final String ID_SHOP_STATE = "sh-state";
    private static final String ID_SHOP_TOGGLE = "sh-shelve";

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
            "residential", "",
            "warehouse", "wh-link");

    private static final Set<String> WAREHOUSE_PAGES = Set.of(
            "im", "rm", "rs", "sh", "mt", "wh");

    private final String page;
    private final String buildingName;
    private final int buildingLevel;
    private final String buildingPos;
    private boolean applied;

    private ResidentListPacket latestResidents = ResidentListPacket.outside();
    private String residentFilter = "";
    private String shownResidentFilter;
    private boolean shopOpen;

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
        Element hire = document.getElementById("empl-hire");
        if (hire != null) {
            hire.addEventListener("click", event -> net.neoforged.neoforge.network.PacketDistributor
                    .sendToServer(
                            new com.xy2407.kingdom.common.network.employment
                                    .EmploymentOpenRequestPacket("", "", 0)));
        }
        }
        Element main = document.getElementById(MAIN_BUTTONS.getOrDefault(prefix, ""));
        if (main != null) {
            if (RESTAURANT_PREFIX.equals(prefix)) {
                main.addEventListener("click", event -> requestLayout());
            } else if (SHOP_PREFIX.equals(prefix)) {
                main.addEventListener("click", event -> toggleShop());
            } else {
                main.addEventListener("click", event -> hint("该功能尚未接入"));
            }
        }
        if (RESIDENT_PREFIX.equals(prefix)) {
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
            requestResidents();
        } else if (RESTAURANT_PREFIX.equals(prefix)) {
            requestMenu();
        } else if (SHOP_PREFIX.equals(prefix)) {
            requestShop();
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

    private static void requestLayout() {
        PacketDistributor.sendToServer(new RestaurantLayoutRequestPacket());
    }

    private static void requestShop() {
        PacketDistributor.sendToServer(new ShopPanelRequestPacket());
    }

    private void toggleShop() {
        PacketDistributor.sendToServer(new ShopPanelActionPacket(!shopOpen));
    }

    void applyShop(ShopPanelStatePacket packet) {
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        shopOpen = packet.open();
        text(document, ID_SHOP_WORKER, packet.worker().isBlank() ? "未指派" : packet.worker());
        text(document, ID_SHOP_STATE, packet.open()
                ? "营业中 · " + packet.shelves() + " 个货架" : "已打烊");
        Element toggle = document.getElementById(ID_SHOP_TOGGLE);
        if (toggle != null) {
            toggle.setTextContent(packet.open() ? "打烊" : "开业");
        }
    }

    void applyRestaurantMenu(RestaurantMenuPacket packet) {
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        String staff;
        if (packet.chefCount() <= 0) {
            staff = "缺少厨师";
        } else if (packet.waiterCount() <= 0) {
            staff = "缺服务员·停业";
        } else {
            staff = "厨师 " + packet.chefCount() + " · 服务员 " + packet.waiterCount();
        }
        text(document, ID_CHEF, staff);
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

    private void refreshResidentList(Document document, ResidentListPacket packet, List<UUID> residents) {
        Element host = document.getElementById(ID_RESIDENT_LIST);
        if (host == null) {
            return;
        }
        if (!packet.inside()) {
            host.setInnerHTML("<div class=\"bp-empty\">载入中…</div>");
            return;
        }
        Map<UUID, ResidentListPacket.Worker> unique = new LinkedHashMap<>();
        for (ResidentListPacket.Worker worker : packet.workers()) {
            if (residentFilter.isBlank() || worker.name().contains(residentFilter)) {
                unique.putIfAbsent(worker.id(), worker);
            }
        }
        List<ResidentListPacket.Worker> visible = new ArrayList<>(unique.values());
        if (visible.isEmpty()) {
            AuiLazyList.clear(host);
            host.setInnerHTML("<div class=\"bp-empty\">"
                    + (packet.workers().isEmpty() ? "城里还没有市民" : "没有匹配的市民") + "</div>");
            return;
        }
        AuiLazyList.render(host, visible.size(),
                index -> {
                    ResidentListPacket.Worker worker = visible.get(index);
                    boolean hired = residents.contains(worker.id());
                    String state = worker.note().isBlank() ? (hired ? "住户" : "空闲") : worker.note();
                    return "<div class=\"bp-item" + (hired ? " on" : "")
                            + "\" id=\"rd-worker-" + index + "\">"
                            + "<kingdom-entity-avatar class=\"bp-face\" skin=\""
                            + escape(worker.skin()) + "\"></kingdom-entity-avatar>"
                            + "<span class=\"bp-name\">" + escape(worker.name()) + "</span>"
                            + "<span class=\"bp-sub\">" + escape(state) + "</span>"
                            + "</div>";
                },
                index -> {
                    UUID workerId = visible.get(index).id();
                    Element row = document.getElementById("rd-worker-" + index);
                    if (row != null) {
                        row.addEventListener("click", event ->
                                PacketDistributor.sendToServer(ResidentActionPacket.toggleResident(workerId)));
                    }
                });
    }

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
