package com.xy2407.kingdom.client.cityhall;

import java.util.List;
import java.util.Set;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.client.aui.WorldMapElement;
import com.xy2407.kingdom.client.map.MapOverlayStore;
import com.xy2407.kingdom.common.network.cityhall.CityHallAffairActionPacket;
import com.xy2407.kingdom.common.network.cityhall.CityHallDataPacket;
import com.xy2407.kingdom.common.network.cityhall.CityHallRequestPacket;
import com.xy2407.kingdom.common.network.faction.TerritoryBuyPacket;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

/**
 * 市政厅界面的宿主：右键市政厅操作台打开，页面数据由 Java 填充。
 */
@OnlyIn(Dist.CLIENT)
public final class CityHallScreen extends ApricityScreen {

    public static final String PAGE = "ui/city_hall.html";

    private static final String TODO_HOST = "chTodoList";

    private static final String AFFAIR_ROW_PREFIX = "chAffair-";

    private static final int REFRESH_INTERVAL_TICKS = 100;

    private static int hallX;
    private static int hallZ;

    private boolean viewInitialized;

    private int refreshCounter;

    private Set<Long> buySelection;

    private static final String[] MAP_VIEW_ATTRIBUTES = {"center-x", "center-z", "zoom"};

    private static final String[] MAP_TIP_IDS = {"chMapTip", "chMapFullTip"};

    private static final String[] TIER_NAMES = {"据点", "村落", "集镇", "城池", "城邦"};

    private static final int[] TIER_NEEDS = {0, 24, 80, 200, 400};

    private static volatile CityHallDataPacket snapshot = CityHallDataPacket.EMPTY;

    private static Document boundDocument;

    public CityHallScreen() {
        super(PAGE);
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    public static void open(int x, int z) {
        hallX = x;
        hallZ = z;
        com.xy2407.kingdom.ModMain.LOGGER.info("[市政厅] 客户端要开管理界面 pos={},{}", x, z);
        Minecraft.getInstance().setScreen(new CityHallScreen());
    }

    public static void accept(CityHallDataPacket packet) {
        snapshot = packet;
        if (Minecraft.getInstance().screen instanceof CityHallScreen screen) {
            screen.bind();
        }
    }

    @Override
    protected void init() {
        super.init();
        Document document = getLinkedDocument();
        if (document != null) {
            var viewport = document.getViewportSize();
            com.xy2407.kingdom.ModMain.LOGGER.info("[市政厅] 文档视口 {}×{}",
                    viewport.width(), viewport.height());
        }
        bind();
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                       float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (boundDocument == null) {
            bind();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshCounter < REFRESH_INTERVAL_TICKS) {
            return;
        }
        refreshCounter = 0;
        PacketDistributor.sendToServer(new CityHallRequestPacket(hallX, hallZ));
    }

    @Override
    public void onClose() {
        WorldMapElement.clearSelection();
        WorldMapElement.setSelectionListener(null);
        buySelection = null;
        super.onClose();
    }

    private void bind() {
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        if (document != boundDocument) {
            boundDocument = document;
            com.xy2407.kingdom.ModMain.LOGGER.info("[市政厅] 首次绑定 Document，挂载交互");
            bindInteractions(document);
        }
        applyData(document, snapshot);
    }

    private void applyData(CityHallDataPacket packet) {
        if (boundDocument == null) {
            bind();
        }
        if (boundDocument != null) {
            applyData(boundDocument, packet);
        }
    }

    private void applyData(Document document, CityHallDataPacket packet) {
        if (document == null || packet == null) {
            return;
        }
        text(document, "chGoldNow", value(packet.gold()));
        text(document, "chGoldMax", limit(packet.goldMax()));
        bar(document, "chGoldBar", packet.gold(), packet.goldMax());
        bar(document, "chUnityBar", packet.unity(), 100);
        bar(document, "chLoyalBar", packet.loyalty(), 100);
        bar(document, "chHappyBar", packet.happiness(), 100);
        text(document, "chUnityNow", percent(packet.unity()));
        text(document, "chLoyalNow", percent(packet.loyalty()));
        text(document, "chHappyNow", percent(packet.happiness()));

        text(document, "chMapMeta", "384 × 384");

        text(document, "chTownName", packet.townName().isBlank() ? "未 命 名 城 镇" : packet.townName());

        text(document, "chPop", String.valueOf(packet.population()));
        text(document, "chPopMax", limit(packet.populationMax()));
        text(document, "chBuild", String.valueOf(packet.buildings()));
        text(document, "chBuildMax", limit(packet.buildingsMax()));
        text(document, "chVassal", String.valueOf(packet.vassals()));
        text(document, "chVassalMax", limit(packet.vassalsMax()));

        text(document, "chRealmNation", orDash(packet.nation()));
        text(document, "chRealmAlly", orDash(packet.ally()));
        text(document, "chRealmFoe", orDash(packet.foe()));
        text(document, "chRealmPolicy", orDash(packet.policy()));

        fillLines(document, "chEventList", packet.events(), "最近没有值得记一笔的事");
        fillAffairs(document, packet.affairs());
        fillLines(document, "chLedgerList", packet.ledger(), "暂无进出账");

        applyTier(document, packet.population());

        if (openModal != null) {
            showModal(openModal);
        }

        pointMapIfChanged(document);
    }

    private ModalKind openModal;

    private boolean mapFullOpen;
    private boolean dayModalOpen;

    private enum ModalKind { CITIZEN, STRUCTURE, VASSAL }

    private void bindInteractions(Document document) {
        click(document, "chStatPop", () -> showModal(ModalKind.CITIZEN));
        click(document, "chStatBuild", () -> showModal(ModalKind.STRUCTURE));
        click(document, "chStatVassal", () -> showModal(ModalKind.VASSAL));

        click(document, "chCalBtn", this::showDayModal);

        WorldMapElement.setSelectionListener(this::openBuyModal);
        click(document, "chBuyClose", this::closeBuyModal);
        click(document, "chBuyCancel", this::closeBuyModal);
        click(document, "chBuyConfirm", this::confirmBuy);

        click(document, "chMapBox", () -> {
            if (WorldMapElement.draggedRecently()) {
                return;
            }
            mapFullOpen = true;
            copyMapView(document);
            toggleClass(document, "chMapBox", "is-hidden", true);
            toggleClass(document, "chMapFull", "is-open", true);
        });

        Element fullMap = document.getElementById("chMapFull");
        if (fullMap != null) {
            fullMap.addEventListener("wheel", event -> event.preventDefault());
        }

        MapOverlayStore.setChangeListener(() -> {
            refreshMapTip(document);
            WorldMapElement.requestRepaint();
        });

        click(document, "chModalClose", () -> closeModal(document));
        click(document, "chModalDayClose", () -> {
            dayModalOpen = false;
            toggleClass(document, "chModalDay", "is-open", false);
        });

        Element search = document.getElementById("chModalSearch");
        if (search instanceof Input input) {
            input.addEventListener("input", event -> {
                searchKeyword = input.getValue() == null ? "" : input.getValue();
                if (openModal != null) {
                    showModal(openModal);
                }
            });
        }
    }

    private String searchKeyword = "";

    private void showModal(ModalKind kind) {
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        if (openModal != kind) {
            searchKeyword = "";
            if (document.getElementById("chModalSearch") instanceof Input input) {
                input.setValue("");
            }
        }
        openModal = kind;

        String title;
        String html;
        boolean searchable;
        switch (kind) {
            case CITIZEN -> {
                title = "人 口 名 录";
                html = citizensHtml(snapshot.citizens());
                searchable = true;
            }
            case STRUCTURE -> {
                title = "建 筑 名 录";
                html = structuresHtml(snapshot.structures());
                searchable = true;
            }
            default -> {
                title = "附 属 领 地";
                html = "<div class=\"empty-note\">尚无附属领地</div>";
                searchable = false;
            }
        }

        text(document, "chModalTitle", title);
        toggleClass(document, "chModalSearchWrap", "is-hidden", !searchable);
        Element body = document.getElementById("chModalBody");
        if (body != null) {
            body.setInnerHTML(html);
        }
        toggleClass(document, "chModal", "is-open", true);
    }

    private void closeModal(Document document) {
        openModal = null;
        toggleClass(document, "chModal", "is-open", false);
    }

    private void openBuyModal(Set<Long> chunks) {
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        buySelection = Set.copyOf(chunks);
        Element body = document.getElementById("chBuyBody");
        if (body != null) {
            body.setInnerHTML("<div class=\"rline\"><span class=\"rline-k\">已选区块</span>"
                    + "<span class=\"rline-v\">" + chunks.size() + " 个</span></div>"
                    + "<div class=\"rline\"><span class=\"rline-k\">操作</span>"
                    + "<span class=\"rline-v\">确认后并入你的领地</span></div>");
        }
        toggleClass(document, "chBuyModal", "is-open", true);
    }

    private void closeBuyModal() {
        buySelection = null;
        WorldMapElement.clearSelection();
        if (boundDocument != null) {
            toggleClass(boundDocument, "chBuyModal", "is-open", false);
        }
    }

    private void confirmBuy() {
        Set<Long> selection = buySelection;
        closeBuyModal();
        if (selection == null || selection.isEmpty()) {
            return;
        }
        long[] chunks = new long[selection.size()];
        int index = 0;
        for (long key : selection) {
            chunks[index++] = key;
        }
        PacketDistributor.sendToServer(new TerritoryBuyPacket(chunks));
    }

    private void showDayModal() {
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        Element grid = document.getElementById("chDayGrid");
        if (grid != null) {
            int current = snapshot.day();
            StringBuilder html = new StringBuilder();
            int from = Math.max(1, current - 179);
            for (int day = from; day <= current; day++) {
                html.append("<div class=\"day-cell")
                        .append(day == current ? " on" : "")
                        .append("\">").append(day).append("</div>");
            }
            if (current <= 0) {
                html.append("<div class=\"empty-note\">尚未记到任何一天</div>");
            }
            grid.setInnerHTML(html.toString());
        }
        dayModalOpen = true;
        toggleClass(document, "chModalDay", "is-open", true);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && boundDocument != null) {
            if (buySelection != null) {
                closeBuyModal();
                return true;
            }
            if (mapFullOpen) {
                mapFullOpen = false;
                toggleClass(boundDocument, "chMapFull", "is-open", false);
                toggleClass(boundDocument, "chMapBox", "is-hidden", false);
                return true;
            }
            if (dayModalOpen) {
                dayModalOpen = false;
                toggleClass(boundDocument, "chModalDay", "is-open", false);
                return true;
            }
            if (openModal != null) {
                closeModal(boundDocument);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private String citizensHtml(List<CityHallDataPacket.Citizen> citizens) {
        StringBuilder html = new StringBuilder();
        int shown = 0;
        for (CityHallDataPacket.Citizen citizen : citizens) {
            if (!matches(citizen.name(), citizen.job())) {
                continue;
            }
            shown++;
            html.append("<div class=\"pcard\">")
                    .append("<kingdom-entity-avatar class=\"pcard-avatar\" skin=\"")
                    .append(escape(citizen.skin())).append("\"></kingdom-entity-avatar>")
                    .append("<div class=\"pcard-main\">")
                    .append("<div class=\"pcard-name\">").append(escape(citizen.name())).append("</div>")
                    .append("<div class=\"pcard-sub\">").append(escape(citizenSub(citizen))).append("</div>")
                    .append("</div>")
                    .append("</div>");
        }
        if (shown == 0) {
            return "<div class=\"empty-note\">"
                    + (searchKeyword.isBlank() ? "这座城还没有居民" : "没有匹配的居民")
                    + "</div>";
        }
        return html.toString();
    }

    private String structuresHtml(List<CityHallDataPacket.Structure> structures) {
        StringBuilder html = new StringBuilder();
        int shown = 0;
        for (CityHallDataPacket.Structure structure : structures) {
            if (!matches(structure.name(), structure.category())) {
                continue;
            }
            shown++;
            html.append("<div class=\"bcard\">")
                    .append("<div class=\"bcard-main\">")
                    .append("<div class=\"bcard-name\">").append(escape(structure.name())).append("</div>")
                    .append("<div class=\"pcard-sub\">").append(escape(shortDimension(structure.category())))
                    .append("</div></div>")
                    .append("<span class=\"bcard-pos\">").append(structure.x()).append(", ")
                    .append(structure.z()).append("</span>")
                    .append("</div>");
        }
        if (shown == 0) {
            return "<div class=\"empty-note\">"
                    + (searchKeyword.isBlank() ? "还没有建成任何建筑" : "没有匹配的建筑")
                    + "</div>";
        }
        return html.toString();
    }

    private void fillLines(Document document, String hostId, List<CityHallDataPacket.Line> lines, String emptyNote) {
        Element host = document.getElementById(hostId);
        if (host == null) {
            return;
        }
        if (lines.isEmpty()) {
            host.setInnerHTML("<div class=\"empty-note\">" + escape(emptyNote) + "</div>");
            return;
        }
        StringBuilder html = new StringBuilder();
        for (CityHallDataPacket.Line line : lines) {
            html.append("<div class=\"rline\">")
                    .append("<span class=\"rline-k\">").append(escape(line.key())).append("</span>")
                    .append("<span class=\"rline-v")
                    .append(line.tone() > 0 ? " kv-val--up" : line.tone() < 0 ? " kv-val--down" : "")
                    .append("\">").append(escape(line.value())).append("</span>")
                    .append("</div>");
        }
        host.setInnerHTML(html.toString());
    }

    private void fillAffairs(Document document, List<CityHallDataPacket.Affair> affairs) {
        Element host = document.getElementById(TODO_HOST);
        if (host == null) {
            return;
        }
        if (affairs.isEmpty()) {
            host.setInnerHTML("<div class=\"empty-note\">暂无待办政务</div>");
            return;
        }
        StringBuilder html = new StringBuilder();
        for (CityHallDataPacket.Affair affair : affairs) {
            html.append("<div class=\"rline rline--affair\" id=\"")
                    .append(AFFAIR_ROW_PREFIX).append(affair.id()).append("\">")
                    .append("<span class=\"rline-k\">").append(escape(affair.scope()))
                    .append(" · 剩 ").append(affair.daysLeft()).append(" 天</span>")
                    .append("<span class=\"rline-v\">").append(escape(affair.title())).append("</span>")
                    .append("</div>");
        }
        host.setInnerHTML(html.toString());
        for (CityHallDataPacket.Affair affair : affairs) {
            Element row = document.getElementById(AFFAIR_ROW_PREFIX + affair.id());
            if (row != null) {
                String affairId = affair.id();
                row.addEventListener("click", event -> resolveAffair(affairId));
            }
        }
    }

    private static void resolveAffair(String affairId) {
        PacketDistributor.sendToServer(new CityHallAffairActionPacket(hallX, hallZ, affairId));
    }

    private static void applyTier(Document document, int population) {
        int tier = 0;
        for (int index = TIER_NEEDS.length - 1; index >= 0; index--) {
            if (population >= TIER_NEEDS[index]) {
                tier = index;
                break;
            }
        }
        for (int index = 0; index < TIER_NAMES.length; index++) {
            toggleClass(document, "chTierNode" + index, "is-passed", index < tier);
            toggleClass(document, "chTierNode" + index, "is-current", index == tier);
        }
        for (int index = 0; index < TIER_NAMES.length - 1; index++) {
            toggleClass(document, "chTierLink" + index, "is-passed", index < tier);
        }

        text(document, "chTierNow", TIER_NAMES[tier]);
        boolean maxed = tier >= TIER_NAMES.length - 1;
        toggleClass(document, "chTierCond", "is-max", maxed);
        text(document, "chTierPopNow", String.valueOf(population));
        if (maxed) {
            text(document, "chTierNext", "已达最高等级");
            text(document, "chTierPopNeed", "");
            bar(document, "chTierPopBar", population, Math.max(1, population));
            return;
        }
        int need = TIER_NEEDS[tier + 1];
        text(document, "chTierNext", TIER_NAMES[tier + 1] + " · 需 " + need + " 人口");
        text(document, "chTierPopNeed", "/ " + need);
        bar(document, "chTierPopBar", population, need);
    }

    private boolean matches(String name, String sub) {
        if (searchKeyword.isBlank()) {
            return true;
        }
        String key = searchKeyword;
        return (name != null && name.contains(key)) || (sub != null && sub.contains(key));
    }

    private static String citizenSub(CityHallDataPacket.Citizen citizen) {
        String job = citizen.job() == null ? "" : citizen.job();
        String shift = citizen.shift() == null ? "" : citizen.shift();
        if (job.isBlank()) {
            return shift;
        }
        return shift.isBlank() ? job : job + " · " + shift;
    }

    private static String shortDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "";
        }
        int colon = dimension.indexOf(':');
        return colon < 0 ? dimension : dimension.substring(colon + 1);
    }

    private void pointMapIfChanged(Document document) {
        if (viewInitialized) {
            return;
        }
        viewInitialized = true;
        pointMap(document, "chMap");
        pointMap(document, "chMapFullView");
    }

    private void pointMap(Document document, String id) {
        Element map = document.getElementById(id);
        if (map == null) {
            return;
        }
        map.setAttribute("center-x", String.valueOf(hallX));
        map.setAttribute("center-z", String.valueOf(hallZ));
        map.setAttribute("marker-x", String.valueOf(hallX));
        map.setAttribute("marker-z", String.valueOf(hallZ));
        if (map instanceof WorldMapElement worldMap) {
            worldMap.resetView();
        }
    }

    private static void copyMapView(Document document) {
        Element from = document.getElementById("chMap");
        Element to = document.getElementById("chMapFullView");
        if (from == null || to == null) {
            return;
        }
        for (String attribute : MAP_VIEW_ATTRIBUTES) {
            String value = from.getAttribute(attribute);
            if (value != null && !value.isBlank()) {
                to.setAttribute(attribute, value);
            }
        }
    }

    private static void text(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setTextContent(value);
        }
    }

    private static void bar(Document document, String id, long value, long max) {
        Element element = document.getElementById(id);
        if (element == null) {
            return;
        }
        element.setAttribute("value", String.valueOf(Math.max(0L, value)));
        element.setAttribute("max", String.valueOf(max > 0L ? max : 1L));
    }

    private static void click(Document document, String id, Runnable action) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.addEventListener("click", event -> action.run());
        }
    }

    private static void toggleClass(Document document, String id, String token, boolean on) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.getClassList().toggle(token, on);
        }
    }

    private static String value(long number) {
        return number == 0L ? "—" : String.valueOf(number);
    }

    private static String percent(int number) {
        return number == 0 ? "—" : String.valueOf(number);
    }

    private static String limit(long max) {
        return max <= 0L ? "" : "/" + max;
    }

    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }

    private static void refreshMapTip(Document document) {
        String html = mapTipHtml();
        for (int index = 0; index < MAP_TIP_IDS.length; index++) {
            Element tip = document.getElementById(MAP_TIP_IDS[index]);
            if (tip == null) {
                continue;
            }
            if (html.isEmpty()) {
                tip.getClassList().toggle("is-open", false);
            } else {
                tip.setInnerHTML(html);
                tip.getClassList().toggle("is-open", true);
            }
        }
    }

    private static String mapTipHtml() {
        MapOverlayStore.BuildingView building = MapOverlayStore.selectedBuilding();
        if (building != null) {
            int width = building.maxX() - building.minX() + 1;
            int depth = building.maxZ() - building.minZ() + 1;
            return "<span class=\"k\">" + escape(building.name()) + "</span>"
                    + "<span class=\"v\">占地 " + width + " × " + depth + "</span>";
        }
        MapOverlayStore.TerritoryView territory = MapOverlayStore.selectedTerritory();
        if (territory != null) {
            return "<span class=\"k\">" + escape(territory.name()) + "</span>"
                    + "<span class=\"v\">领主 " + escape(territory.ownerName()) + "</span>"
                    + "<span class=\"v\">" + escape(territory.tier()) + "</span>"
                    + "<span class=\"v\">" + escape(territory.nationLabel()) + "</span>";
        }
        return "";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
