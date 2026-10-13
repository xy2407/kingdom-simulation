package com.xy2407.kingdom.client.structure;

import com.mojang.blaze3d.platform.InputConstants;
import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.client.aui.KingdomAuiStencil;
import com.xy2407.kingdom.client.aui.StructurePreviewElement;
import com.xy2407.kingdom.client.building.BuildingPanelClient;
import com.xy2407.kingdom.client.building.BuildingPlacementManager;
import com.xy2407.kingdom.client.map.WorldRoadScreen;
import com.xy2407.kingdom.client.rts.ClientBuildingStore;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.client.rts.RtsSubMode;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.network.npc.NpcRosterPacket;
import com.xy2407.kingdom.common.network.npc.NpcRosterRequestPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * RTS 建筑栏入口：Tab 开关贴边底栏与左栏，负责分类卡片、搜索过滤与模型释放。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class StructureDebugKeybind {

    public static final String VIEWER_PAGE = "structures/viewer.html";

    private static final String DOCK_ID = "viewer-dock";
    private static final String BAND_HOST_ID = "viewer-bands";
    private static final String CARD_HOST_ID = "viewer-cards";
    private static final String CARD_TITLE_ID = "viewer-title";
    private static final String SEARCH_ID = "viewer-search";
    private static final String BACK_ID = "viewer-back";
    private static final String SECTION_HOST_ID = "viewer-sections";
    private static final String SIDE_ID = "viewer-side";
    private static final String SIDE_LIST_ID = "side-list";
    private static final String SIDE_SEARCH_ID = "side-search";
    private static final String SIDE_TITLE_ID = "side-title";
    private static final String SIDE_BACK_ID = "side-back";
    private static final String TIP_ID = "building-tip";

    private static final String OTHER_KEY = "OTHER";

    private static final int FIELD_NONE = 0;
    private static final int FIELD_DOCK = 1;
    private static final int FIELD_SIDE = 2;

    private static final int SECTION_INTERVAL_TICKS = 20;

    private static final int DOCK_CHROME = 20;
    private static final int SIDE_CHROME = 236;

    private static final String[] BAND_FOLDERS = {
            BuildingType.RESIDENTIAL.folder(),
            BuildingType.AGRICULTURE.folder(),
            BuildingType.LIVESTOCK.folder(),
            BuildingType.COMMERCIAL.folder(),
            BuildingType.INDUSTRY.folder(),
            BuildingType.MILITARY.folder(),
            BuildingType.STORAGE.folder(),
            OTHER_KEY
    };

    private static final String[] SECTION_KEYS = {"POPULATION", "BUILDING", "TROOP", "ROAD"};

    private static final String[] SECTION_TITLES = {"人口", "建筑", "队伍", "道路"};

    public static final KeyMapping OPEN_VIEWER = new KeyMapping(
            "key.xy2407_kingdom.structure_viewer",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_TAB,
            "key.categories.xy2407_kingdom"
    );

    private static boolean rtsWasActive;

    private static final Map<String, KingdomSimulationStructures.Entry> cardIndex = new LinkedHashMap<>();

    private static final StringBuilder SEARCH_BUFFER = new StringBuilder();
    private static final StringBuilder SIDE_SEARCH_BUFFER = new StringBuilder();
    private static final List<NpcRosterPacket.Entry> roster = new ArrayList<>();
    private static final List<NpcRosterPacket.Entry> sideNpcs = new ArrayList<>();
    private static final List<ClientBuildingStore.Entry> sideBuildings = new ArrayList<>();

    private static int activeField = FIELD_NONE;

    private static List<KingdomSimulationStructures.Entry> allEntries = List.of();
    private static String currentFolder = "";
    private static String currentSection = "";
    private static String searchText = "";
    private static String sideFilter = "";
    private static UUID selectedId;
    private static boolean tipVisible;
    private static String tipShownId;

    private static int lastWidth = -1;
    private static int lastHeight = -1;
    private static int sectionCounter;
    private static int lastCitizens = -1;
    private static int lastBuildings = -1;

    private StructureDebugKeybind() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_VIEWER);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        KingdomSimulationStructures.deploy();
        boolean rtsActive = RtsModeManager.isActive();
        if (rtsWasActive && !rtsActive) {
            closeViewer();
        }
        rtsWasActive = rtsActive;

        while (OPEN_VIEWER.consumeClick()) {
            if (rtsActive) {
                if (isViewerOpen()) {
                    closeViewer();
                    StructureDebugChat.chat("已隐藏建筑预览 HUD 栏");
                    continue;
                }
                openViewer();
                continue;
            }
            BuildingPanelClient.requestAtPlayer();
        }
        KingdomAuiStencil.setSuppressed(isViewerOpen());
        layoutViewer();
        pollSearch();
        refreshSections();
        updateCardTooltip();
    }

    public static void closeViewer() {
        if (isViewerOpen()) {
            ApricityUI.removeDocument(VIEWER_PAGE);
        }
        StructurePreviewElement.releaseAll();
        StructurePreviewTarget.clear();
        StructureDebugChat.reset();
        StructureSearchInput.uninstall();
        cardIndex.clear();
        allEntries = List.of();
        roster.clear();
        sideNpcs.clear();
        sideBuildings.clear();
        currentFolder = "";
        currentSection = "";
        searchText = "";
        sideFilter = "";
        selectedId = null;
        activeField = FIELD_NONE;
        SEARCH_BUFFER.setLength(0);
        SIDE_SEARCH_BUFFER.setLength(0);
        tipVisible = false;
        tipShownId = null;
        lastWidth = -1;
        lastHeight = -1;
        sectionCounter = 0;
        lastCitizens = -1;
        lastBuildings = -1;
    }

    public static boolean isViewerOpen() {
        return !ApricityUI.getDocument(VIEWER_PAGE).isEmpty();
    }

    private static void openViewer() {
        StructureDebugChat.reset();
        StructureDebugChat.chat("按 Tab 打开建筑预览底部 HUD 栏");
        Document document = ApricityUI.createDocument(VIEWER_PAGE);
        if (document == null) {
            StructureDebugChat.chat("创建 HUD 文档失败：模板缺失：%s", VIEWER_PAGE);
            return;
        }
        allEntries = KingdomSimulationStructures.scan();
        currentFolder = "";
        currentSection = "";
        searchText = "";
        sideFilter = "";
        SEARCH_BUFFER.setLength(0);
        SIDE_SEARCH_BUFFER.setLength(0);
        selectedId = null;
        document.setReloadPersistent(true);
        injectBands(document);
        injectSections(document);
        setDockMode(document, false);
        setSideMode(document, false);
        bindBack(document);
        bindSideBack(document);
        bindSearch(document);
        StructureSearchInput.install();
        if (allEntries.isEmpty()) {
            StructureDebugChat.chat("structures 文件夹为空或无可用结构：%s",
                    KingdomSimulationStructures.structuresDir());
        }
        int unnamed = countUnnamed();
        if (unnamed > 0) {
            StructureDebugChat.chat("有 %d 个蓝图没在 json 里写 name，卡片暂用文件名显示", unnamed);
        }
        layoutViewer();
        refreshSections();
    }

    private static int countUnnamed() {
        int count = 0;
        for (KingdomSimulationStructures.Entry entry : allEntries) {
            if (entry.meta().name().equals(BuildingMeta.shortName(entry.id()))) {
                count++;
            }
        }
        return count;
    }

    private static void bindBack(Document document) {
        Element back = document.getElementById(BACK_ID);
        if (back != null) {
            back.addEventListener("click", event -> exitCategory(document));
        }
    }

    private static void bindSideBack(Document document) {
        Element back = document.getElementById(SIDE_BACK_ID);
        if (back != null) {
            back.addEventListener("click", event -> exitSection(document));
        }
    }

    private static void bindSearch(Document document) {
        Element dockSearch = document.getElementById(SEARCH_ID);
        if (dockSearch != null) {
            dockSearch.addEventListener("click", event -> setFieldActive(document, FIELD_DOCK));
        }
        Element sideSearch = document.getElementById(SIDE_SEARCH_ID);
        if (sideSearch != null) {
            sideSearch.addEventListener("click", event -> setFieldActive(document, FIELD_SIDE));
        }
    }

    private static void setFieldActive(Document document, int field) {
        activeField = field;
        StructureSearchInput.setActive(field != FIELD_NONE);
        applySearchClass(document, SEARCH_ID, "viewer-search", field == FIELD_DOCK);
        applySearchClass(document, SIDE_SEARCH_ID, "page-search", field == FIELD_SIDE);
    }

    private static void applySearchClass(Document document, String id, String base, boolean on) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setAttribute("class", on ? base + " search-on" : base);
        }
    }

    static boolean acceptSearchChar(int codePoint) {
        if (codePoint < 0x20) {
            return false;
        }
        if (activeField == FIELD_DOCK && !currentFolder.isEmpty()) {
            SEARCH_BUFFER.appendCodePoint(codePoint);
            pushBuffer(SEARCH_ID, SEARCH_BUFFER);
            return true;
        }
        if (activeField == FIELD_SIDE && !currentSection.isEmpty()) {
            SIDE_SEARCH_BUFFER.appendCodePoint(codePoint);
            pushBuffer(SIDE_SEARCH_ID, SIDE_SEARCH_BUFFER);
            return true;
        }
        return false;
    }

    static void backspaceSearch() {
        if (activeField == FIELD_DOCK && SEARCH_BUFFER.length() > 0) {
            SEARCH_BUFFER.setLength(SEARCH_BUFFER.length() - 1);
            pushBuffer(SEARCH_ID, SEARCH_BUFFER);
        } else if (activeField == FIELD_SIDE && SIDE_SEARCH_BUFFER.length() > 0) {
            SIDE_SEARCH_BUFFER.setLength(SIDE_SEARCH_BUFFER.length() - 1);
            pushBuffer(SIDE_SEARCH_ID, SIDE_SEARCH_BUFFER);
        }
    }

    static void cancelSearch() {
        List<Document> documents = ApricityUI.getDocument(VIEWER_PAGE);
        if (!documents.isEmpty()) {
            setFieldActive(documents.get(0), FIELD_NONE);
        }
    }

    private static void pushBuffer(String id, StringBuilder buffer) {
        List<Document> documents = ApricityUI.getDocument(VIEWER_PAGE);
        if (documents.isEmpty()) {
            return;
        }
        Element element = documents.get(0).getElementById(id);
        if (element instanceof Input input) {
            input.setValue(buffer.toString());
        }
    }

    private static void enterCategory(Document document, String folder) {
        currentFolder = folder;
        searchText = "";
        SEARCH_BUFFER.setLength(0);
        Element search = document.getElementById(SEARCH_ID);
        if (search instanceof Input input) {
            input.setValue("");
        }
        setFieldActive(document, FIELD_NONE);
        setDockMode(document, true);
        injectCards(document);
    }

    private static void exitCategory(Document document) {
        currentFolder = "";
        searchText = "";
        SEARCH_BUFFER.setLength(0);
        cardIndex.clear();
        setFieldActive(document, FIELD_NONE);
        hideTip(document);
        setDockMode(document, false);
    }

    private static void setDockMode(Document document, boolean cards) {
        Element dock = document.getElementById(DOCK_ID);
        if (dock != null) {
            dock.setAttribute("class", cards ? "dock is-cards" : "dock");
        }
    }

    private static void enterSection(Document document, String key) {
        currentSection = key;
        sideFilter = "";
        SIDE_SEARCH_BUFFER.setLength(0);
        selectedId = null;
        Element search = document.getElementById(SIDE_SEARCH_ID);
        if (search instanceof Input input) {
            input.setValue("");
        }
        setFieldActive(document, FIELD_NONE);
        setSideMode(document, true);
        if (isRoadPage()) {
            renderRoadEntries(document);
            return;
        }
        if (isPopulationPage()) {
            PacketDistributor.sendToServer(new NpcRosterRequestPacket());
        }
        renderSideList(document);
    }

    private static boolean isRoadPage() {
        return SECTION_KEYS[3].equals(currentSection);
    }

    private static void renderRoadEntries(Document document) {
        Element host = document.getElementById(SIDE_LIST_ID);
        if (host == null) {
            return;
        }
        host.setInnerHTML("<div class=\"side-item\" id=\"road-territory\">"
                + "<span class=\"side-name\">领地道路</span>"
                + "<span class=\"side-sub\">徒手刷</span></div>"
                + "<div class=\"side-item\" id=\"road-world\">"
                + "<span class=\"side-name\">世界道路</span>"
                + "<span class=\"side-sub\">地图画线</span></div>");
        Element territory = document.getElementById("road-territory");
        if (territory != null) {
            territory.addEventListener("click", event -> enterRoadTerritory(document));
        }
        Element world = document.getElementById("road-world");
        if (world != null) {
            world.addEventListener("click", event -> enterRoadWorld());
        }
        Element title = document.getElementById(SIDE_TITLE_ID);
        if (title != null) {
            title.setInnerHTML("道路");
        }
    }

    private static void enterRoadTerritory(Document document) {
        if (!RtsModeManager.isActive()) {
            StructureDebugChat.chat("先按 F6 进 RTS 模式，才能画领地道路");
            return;
        }
        closeViewer();
        RtsModeManager.enterSubMode(RtsSubMode.ROAD_TERRITORY);
        StructureDebugChat.chat("领地道路：按住右键拖拽画路，C 撤回，Enter 提交");
    }

    private static void enterRoadWorld() {
        closeViewer();
        WorldRoadScreen.open();
    }

    private static void exitSection(Document document) {
        currentSection = "";
        sideFilter = "";
        SIDE_SEARCH_BUFFER.setLength(0);
        sideNpcs.clear();
        sideBuildings.clear();
        selectedId = null;
        setFieldActive(document, FIELD_NONE);
        setSideMode(document, false);
    }

    private static void setSideMode(Document document, boolean page) {
        Element side = document.getElementById(SIDE_ID);
        if (side != null) {
            side.setAttribute("class", page ? "side is-page" : "side");
        }
    }

    private static boolean isPopulationPage() {
        return SECTION_KEYS[0].equals(currentSection);
    }

    private static void renderSideList(Document document) {
        Element host = document.getElementById(SIDE_LIST_ID);
        if (host == null) {
            return;
        }
        sideNpcs.clear();
        sideBuildings.clear();
        StringBuilder html = new StringBuilder();
        if (isPopulationPage()) {
            for (NpcRosterPacket.Entry entry : roster) {
                if (!matchesSide(entry.name())) {
                    continue;
                }
                int index = sideNpcs.size();
                sideNpcs.add(entry);
                html.append("<div class=\"side-item").append(entry.id().equals(selectedId) ? " on" : "")
                        .append("\" id=\"side-npc-").append(index).append("\">")
                        .append("<kingdom-entity-avatar class=\"side-face\" skin=\"")
                        .append(escapeAttr(entry.skin())).append("\"></kingdom-entity-avatar>")
                        .append("<span class=\"side-name\">").append(escapeAttr(entry.name())).append("</span>")
                        .append("<span class=\"side-sub\">").append(escapeAttr(entry.note())).append("</span>")
                        .append("</div>");
            }
        } else {
            for (ClientBuildingStore.Entry entry : ClientBuildingStore.entries()) {
                if (!matchesSide(entry.name())) {
                    continue;
                }
                int index = sideBuildings.size();
                sideBuildings.add(entry);
                var size = entry.size();
                html.append("<div class=\"side-item").append(entry.id().equals(selectedId) ? " on" : "")
                        .append("\" id=\"side-bld-").append(index).append("\">")
                        .append("<span class=\"side-name\">").append(escapeAttr(entry.name())).append("</span>")
                        .append("<span class=\"side-sub\">").append(size.getX()).append("×")
                        .append(size.getY()).append("×").append(size.getZ()).append("</span>")
                        .append("</div>");
            }
        }
        if (sideNpcs.isEmpty() && sideBuildings.isEmpty()) {
            host.setInnerHTML("<div class=\"side-empty\">没有符合条件的条目</div>");
        } else {
            host.setInnerHTML(html.toString());
        }
        bindSideRows(document);
        Element title = document.getElementById(SIDE_TITLE_ID);
        if (title != null) {
            int count = isPopulationPage() ? sideNpcs.size() : sideBuildings.size();
            title.setInnerHTML(isPopulationPage() ? "人口 " + count : "建筑 " + count);
        }
    }

    private static boolean matchesSide(String name) {
        if (sideFilter == null || sideFilter.isBlank()) {
            return true;
        }
        return name != null && name.toLowerCase(Locale.ROOT).contains(sideFilter.trim().toLowerCase(Locale.ROOT));
    }

    private static void bindSideRows(Document document) {
        for (int index = 0; index < sideNpcs.size(); index++) {
            NpcRosterPacket.Entry entry = sideNpcs.get(index);
            Element row = document.getElementById("side-npc-" + index);
            if (row != null) {
                row.addEventListener("click", event -> focusNpc(document, entry));
            }
        }
        for (int index = 0; index < sideBuildings.size(); index++) {
            ClientBuildingStore.Entry entry = sideBuildings.get(index);
            Element row = document.getElementById("side-bld-" + index);
            if (row != null) {
                row.addEventListener("click", event -> focusBuilding(document, entry));
            }
        }
    }

    private static void focusNpc(Document document, NpcRosterPacket.Entry entry) {
        selectedId = entry.id();
        RtsModeManager.teleportCameraTo(new Vec3(entry.x() + 0.5D, entry.y(), entry.z() + 0.5D));
        renderSideList(document);
    }

    private static void focusBuilding(Document document, ClientBuildingStore.Entry entry) {
        selectedId = entry.id();
        double centerX = (entry.min().getX() + entry.max().getX() + 1) / 2.0D;
        double centerZ = (entry.min().getZ() + entry.max().getZ() + 1) / 2.0D;
        RtsModeManager.teleportCameraTo(new Vec3(centerX, entry.min().getY(), centerZ));
        renderSideList(document);
    }

    public static void acceptRoster(NpcRosterPacket packet) {
        roster.clear();
        roster.addAll(packet.entries());
        List<Document> documents = ApricityUI.getDocument(VIEWER_PAGE);
        if (!documents.isEmpty() && isPopulationPage()) {
            renderSideList(documents.get(0));
        }
    }

    private static void pollSearch() {
        List<Document> documents = ApricityUI.getDocument(VIEWER_PAGE);
        if (documents.isEmpty()) {
            return;
        }
        Document document = documents.get(0);
        if (!currentFolder.isEmpty()) {
            String value = readSearch(document, SEARCH_ID);
            if (!value.equals(searchText)) {
                searchText = value;
                injectCards(document);
            }
        }
        if (!currentSection.isEmpty()) {
            String value = readSearch(document, SIDE_SEARCH_ID);
            if (!value.equals(sideFilter)) {
                sideFilter = value;
                renderSideList(document);
            }
        }
    }

    private static String readSearch(Document document, String id) {
        Element element = document.getElementById(id);
        if (element instanceof Input input) {
            String value = input.getValue();
            return value == null ? "" : value;
        }
        return "";
    }

    private static String bandsHtml() {
        StringBuilder html = new StringBuilder();
        for (String folder : BAND_FOLDERS) {
            List<KingdomSimulationStructures.Entry> list = entriesOf(folder);
            html.append("<div class=\"band\" id=\"band-").append(folder).append("\">")
                    .append("<div class=\"band-head\"><span class=\"band-name\">")
                    .append(bandLabel(folder))
                    .append("</span><span class=\"band-count\">").append(list.size())
                    .append("</span></div><div class=\"band-list\">");
            if (list.isEmpty()) {
                html.append("<div class=\"band-empty\">暂无</div>");
            } else {
                for (KingdomSimulationStructures.Entry entry : list) {
                    html.append("<div class=\"band-item\">")
                            .append(escapeAttr(entry.displayName())).append("</div>");
                }
            }
            html.append("</div></div>");
        }
        return html.toString();
    }

    private static void injectBands(Document document) {
        Element host = document.getElementById(BAND_HOST_ID);
        if (host == null) {
            StructureDebugChat.chat("找不到分类卡容器 #viewer-bands");
            return;
        }
        host.setInnerHTML(bandsHtml());
        for (String folder : BAND_FOLDERS) {
            Element band = document.getElementById("band-" + folder);
            if (band != null) {
                band.addEventListener("click", event -> enterCategory(document, folder));
            }
        }
        lastWidth = -1;
    }

    private static void injectCards(Document document) {
        Element container = document.getElementById(CARD_HOST_ID);
        if (container == null) {
            return;
        }
        List<KingdomSimulationStructures.Entry> visible = new ArrayList<>();
        for (KingdomSimulationStructures.Entry entry : entriesOf(currentFolder)) {
            if (matchesSearch(entry)) {
                visible.add(entry);
            }
        }
        cardIndex.clear();
        Element title = document.getElementById(CARD_TITLE_ID);
        if (title != null) {
            title.setInnerHTML(escapeAttr(bandLabel(currentFolder)) + " · " + visible.size() + " 座");
        }
        if (visible.isEmpty()) {
            container.setInnerHTML("<div class=\"card-empty\">没有符合条件的建筑</div>");
            return;
        }
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < visible.size(); index++) {
            html.append(cardHtml(visible.get(index), index));
        }
        container.setInnerHTML(html.toString());
        bindCards(document, visible);
    }

    private static boolean matchesSearch(KingdomSimulationStructures.Entry entry) {
        if (searchText == null || searchText.isBlank()) {
            return true;
        }
        String keyword = searchText.trim().toLowerCase(Locale.ROOT);
        String name = entry.displayName();
        if (name != null && name.toLowerCase(Locale.ROOT).contains(keyword)) {
            return true;
        }
        return entry.id().toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static String cardHtml(KingdomSimulationStructures.Entry entry, int index) {
        String id = escapeAttr(entry.id());
        String name = escapeAttr(entry.displayName());
        String open = "<div class=\"viewer-card\" id=\"card-" + index + "\">";
        String load = "<div class=\"card-load\" id=\"load-" + index + "\">[加载]</div>";
        String cap = "<div class=\"card-cap\">" + name + "</div>";
        if (!entry.previewable()) {
            return open + "<div class=\"card-preview card-toobig\" id=\"big-" + index + "\">"
                    + "<span class=\"toobig-title\">（建筑过大）</span>"
                    + "<span class=\"toobig-hint\">请点击查看</span>"
                    + "</div>" + cap + load + "</div>";
        }
        return open + "<div class=\"card-preview\">"
                + "<structure-preview structure=\"" + id + "\"></structure-preview>"
                + "</div>" + cap + load + "</div>";
    }

    private static void bindCards(Document document, List<KingdomSimulationStructures.Entry> visible) {
        for (int index = 0; index < visible.size(); index++) {
            KingdomSimulationStructures.Entry entry = visible.get(index);
            cardIndex.put("card-" + index, entry);
            Element tooBig = document.getElementById("big-" + index);
            if (tooBig != null) {
                tooBig.addEventListener("click", event -> expandPreview(tooBig, entry.id()));
            }
            Element load = document.getElementById("load-" + index);
            if (load != null) {
                load.addEventListener("click", event -> loadIntoWorld(entry.id()));
            }
        }
    }

    private static void expandPreview(Element preview, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        preview.setAttribute("class", "card-preview");
        preview.setInnerHTML("<structure-preview structure=\"" + escapeAttr(id) + "\"></structure-preview>");
        StructureDebugChat.chat("已在卡片内加载预览：%s", id);
    }

    private static void injectSections(Document document) {
        Element host = document.getElementById(SECTION_HOST_ID);
        if (host == null) {
            return;
        }
        lastCitizens = countCitizens();
        lastBuildings = countBuildings();
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < SECTION_KEYS.length; index++) {
            html.append("<div class=\"sect\" id=\"sect-").append(SECTION_KEYS[index]).append("\">")
                    .append("<div class=\"sect-head\"><span class=\"sect-title\">")
                    .append(SECTION_TITLES[index]).append("</span></div>")
                    .append("<div class=\"sect-rows\" id=\"sect-rows-").append(SECTION_KEYS[index]).append("\">")
                    .append(sectionRowsHtml(index)).append("</div></div>");
        }
        host.setInnerHTML(html.toString());
        for (int index = 0; index < SECTION_KEYS.length; index++) {
            String key = SECTION_KEYS[index];
            Element sect = document.getElementById("sect-" + key);
            if (sect != null) {
                sect.addEventListener("click", event -> enterSection(document, key));
            }
        }
        lastHeight = -1;
    }

    private static String sectionRowsHtml(int index) {
        if (index == 0) {
            return rowHtml("总人口", String.valueOf(Math.max(0, lastCitizens)));
        }
        if (index == 1) {
            return rowHtml("已登记", String.valueOf(Math.max(0, lastBuildings)));
        }
        return rowHtml("数据源", "待接入");
    }

    private static String rowHtml(String key, String value) {
        return "<div class=\"sect-row\"><span class=\"sect-key\">" + escapeAttr(key)
                + "</span><span class=\"sect-val\">" + escapeAttr(value) + "</span></div>";
    }

    private static void refreshSections() {
        if (++sectionCounter < SECTION_INTERVAL_TICKS) {
            return;
        }
        sectionCounter = 0;
        List<Document> documents = ApricityUI.getDocument(VIEWER_PAGE);
        if (documents.isEmpty()) {
            return;
        }
        int citizens = countCitizens();
        int buildings = countBuildings();
        if (citizens == lastCitizens && buildings == lastBuildings) {
            return;
        }
        lastCitizens = citizens;
        lastBuildings = buildings;
        Document document = documents.get(0);
        for (int index = 0; index < SECTION_KEYS.length; index++) {
            Element rows = document.getElementById("sect-rows-" + SECTION_KEYS[index]);
            if (rows != null) {
                rows.setInnerHTML(sectionRowsHtml(index));
            }
        }
    }

    private static int countCitizens() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0;
        }
        int count = 0;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof NpcEntity) {
                count++;
            }
        }
        return count;
    }

    private static int countBuildings() {
        return ClientBuildingStore.entries().size();
    }

    private static void layoutViewer() {
        List<Document> documents = ApricityUI.getDocument(VIEWER_PAGE);
        if (documents.isEmpty()) {
            return;
        }
        Document document = documents.get(0);
        var viewport = document.getViewportSize();
        int width = (int) Math.round(viewport.width());
        int height = (int) Math.round(viewport.height());
        if (width == lastWidth && height == lastHeight) {
            return;
        }
        lastWidth = width;
        lastHeight = height;

        int bandWidth = Math.max(48, (width - DOCK_CHROME) / BAND_FOLDERS.length);
        for (String folder : BAND_FOLDERS) {
            applyStyle(document, "band-" + folder, "width:" + bandWidth + "px");
        }
        int sectHeight = Math.max(48, (height - SIDE_CHROME) / SECTION_KEYS.length);
        for (String key : SECTION_KEYS) {
            applyStyle(document, "sect-" + key, "height:" + sectHeight + "px");
        }
    }

    private static void applyStyle(Document document, String id, String style) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setAttribute("style", style);
        }
    }

    private static List<KingdomSimulationStructures.Entry> entriesOf(String folder) {
        List<KingdomSimulationStructures.Entry> list = new ArrayList<>();
        for (KingdomSimulationStructures.Entry entry : allEntries) {
            if (folderOf(entry).equals(folder)) {
                list.add(entry);
            }
        }
        return list;
    }

    private static String folderOf(KingdomSimulationStructures.Entry entry) {
        String folder = entry.folder();
        return folder.isEmpty() ? OTHER_KEY : folder;
    }

    private static String bandLabel(String folder) {
        BuildingType type = BuildingType.parse(folder);
        return type == null ? "其它" : type.label();
    }

    private static void updateCardTooltip() {
        List<Document> documents = ApricityUI.getDocument(VIEWER_PAGE);
        if (documents.isEmpty() || cardIndex.isEmpty()) {
            return;
        }
        Document document = documents.get(0);
        KingdomSimulationStructures.Entry hovered = hoveredEntry(document);
        if (hovered == null) {
            hideTip(document);
            return;
        }
        Element tip = document.getElementById(TIP_ID);
        if (tip == null) {
            return;
        }
        if (!hovered.id().equals(tipShownId)) {
            tipShownId = hovered.id();
            tip.setInnerHTML(tooltipHtml(hovered));
        }
        if (!tipVisible) {
            tipVisible = true;
            tip.setAttribute("class", "building-tip tip-show");
        }
    }

    private static void hideTip(Document document) {
        if (!tipVisible) {
            return;
        }
        tipVisible = false;
        Element tip = document.getElementById(TIP_ID);
        if (tip != null) {
            tip.setAttribute("class", "building-tip");
        }
    }

    private static KingdomSimulationStructures.Entry hoveredEntry(Document document) {
        for (Map.Entry<String, KingdomSimulationStructures.Entry> pair : cardIndex.entrySet()) {
            Element element = document.getElementById(pair.getKey());
            if (element != null && element.isHover) {
                return pair.getValue();
            }
        }
        return null;
    }

    private static String tooltipHtml(KingdomSimulationStructures.Entry entry) {
        BuildingMeta meta = entry.meta();
        StringBuilder html = new StringBuilder();
        appendTipRow(html, "建筑", meta.name());
        appendTipRow(html, "分类", bandLabel(folderOf(entry)));
        appendTipRow(html, "等级", String.valueOf(meta.level()));
        if (entry.meta().type() == BuildingType.RESIDENTIAL) {
            appendTipRow(html, "床位", String.valueOf(entry.beds()));
        } else {
            appendTipRow(html, "岗位", String.valueOf(meta.jobs()));
        }
        if (!meta.tags().isEmpty()) {
            appendTipRow(html, "标签", String.join("、", meta.tags()));
        }
        if (!meta.nation().isBlank()) {
            appendTipRow(html, "势力", meta.nation());
        }
        appendTipRow(html, "尺寸", entry.size().getX() + "×" + entry.size().getY() + "×" + entry.size().getZ());
        appendTipRow(html, "蓝图", meta.id());
        return html.toString();
    }

    private static void appendTipRow(StringBuilder html, String key, String value) {
        html.append("<span class=\"tip-row\"><span class=\"tip-key\">")
                .append(escapeAttr(key))
                .append("</span>")
                .append(escapeAttr(value))
                .append("</span>");
    }

    private static void loadIntoWorld(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        BlockPos start = BlockPos.containing(minecraft.player.position()).offset(2, 0, 2);
        BuildingPlacementManager.beginRtsFollow(name, start);
        cancelSearch();
        StructureDebugChat.chat("已加载建筑投影：%s（移动鼠标选点，右键确认开工）", name);
    }

    private static String escapeAttr(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
