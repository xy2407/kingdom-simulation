package com.xy2407.kingdom.client.cityhall;

import java.util.List;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.common.network.cityhall.CityHallDataPacket;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.lwjgl.glfw.GLFW;

/**
 * 市政厅界面的宿主：右键市政厅操作台打开，页面数据由 Java 填充。
 *
 * <p>页面 {@code ui/city_hall.html} 里所有数据节点都留了 id、内容全是占位值，
 * 一律由 {@link #applyData} 覆盖。AUI 页面跑不了 JS，所以"点击统计框弹列表"
 * "点日历图标选日期""点地图放大"这些交互也全在这一侧用 DOM 事件接。</p>
 *
 * <p>数据由 {@link CityHallDataPacket} 送来。包可能比开屏包先到也可能后到，
 * 所以两边都接了：先到就存进 {@link #snapshot}，界面打开时读；后到就直接重填一遍。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CityHallScreen extends ApricityScreen {

    public static final String PAGE = "ui/city_hall.html";

    /** 打开界面时市政厅方块所在的位置。地图没记过中心时用它兜底。 */
    private static int hallX;
    private static int hallZ;

    /** 这名玩家上次看地图的位置（服务端存的）。两个都是 0 表示没记过，那就用市政厅位置。 */
    private int mapCenterX;
    private int mapCenterZ;

    /** 本次会话是否已经给地图定过初始视角；定过之后视角就完全归玩家，不再被覆盖。 */
    private boolean viewInitialized;

    /** 全屏地图要从缩略图继承的属性：中心与缩放。 */
    private static final String[] MAP_VIEW_ATTRIBUTES = {"center-x", "center-z", "zoom"};

    /** 最近一次收到的城镇数据。开屏包与数据包谁先到都靠它兜住。 */
    private static volatile CityHallDataPacket snapshot = CityHallDataPacket.EMPTY;

    /**
     * 事件已经挂到哪个 Document 上了。
     *
     * <p><b>必须是静态的</b>：AUI 按页面路径复用 Document 对象，而这个 Screen 每次都 new。
     * 用实例字段挡不住跨界面的重复绑定——同一个 Document 会被挂上两轮监听，点一次触发两次。</p>
     *
     * <p>但数据**不能**跟着只填一次：重进界面要看最新数值，所以 {@link #bind()} 里
     * 事件只绑一次、{@link #applyData} 每次都跑。</p>
     */
    private static Document boundDocument;

    public CityHallScreen() {
        super(PAGE);
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    /** 供开屏包钩子调用，已在客户端线程上。 */
    public static void open(int x, int z) {
        hallX = x;
        hallZ = z;
        com.xy2407.kingdom.ModMain.LOGGER.info("[市政厅] 客户端要开管理界面 pos={},{}", x, z);
        Minecraft.getInstance().setScreen(new CityHallScreen());
    }

    /** 供数据包钩子调用，已在客户端线程上。 */
    public static void accept(CityHallDataPacket packet) {
        snapshot = packet;
        /* 界面已经开着就立刻重填；没开就只存着，等 init() 时读 */
        if (Minecraft.getInstance().screen instanceof CityHallScreen screen) {
            /* 走 bind() 而不是直接 applyData：Document 是异步加载的，
               init() 那一刻可能还没就绪、事件也没挂上，这里补一次。 */
            screen.bind();
        }
    }

    @Override
    protected void init() {
        super.init();
        /* 报一次实际视口：面板是 1080×810 的定尺设计，而本页是纯 DOM 页、
           没有容器页那套"整稿缩放"机制，视口比它小就会被裁。
           尺寸问题只能靠这行日志定位（§4.3 的推荐做法）。 */
        Document document = getLinkedDocument();
        if (document != null) {
            var viewport = document.getViewportSize();
            com.xy2407.kingdom.ModMain.LOGGER.info("[市政厅] 文档视口 {}×{}",
                    viewport.width(), viewport.height());
        }
        bind();
    }

    /** 兜底：直到 Document 到手为止，每帧再试一次（bind() 自身幂等）。 */
    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                       float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (boundDocument == null) {
            bind();
        }
    }

    /**
     * 把当前 Document 接上：**事件只绑一次，数据每次都刷**。
     *
     * <p>Document 由 AUI 异步加载，{@code init()} 时 {@code getLinkedDocument()} 常常还是 null；
     * 所以数据包到达、以及每帧渲染时都会再调一次，Document 一到手立刻补绑，
     * 不必等下次开界面（那正是"重进后界面空白/没反应"的成因）。</p>
     */
    private void bind() {
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        if (document != boundDocument) {
            boundDocument = document;
            com.xy2407.kingdom.ModMain.LOGGER.info("[市政厅] 首次绑定 Document，挂载交互");
            /* 地图中心要等数据到了才知道（在 applyData 里从包里取），所以这里先不设，
               applyData 末尾会统一设一次。 */
            bindInteractions(document);
        }
        applyData(document, snapshot);
    }

    /**
     * 把数据填进页面。
     *
     * <p>某个字段是空的（null / 空串 / 0）时**不覆盖**页面里的占位文字，显示成 "—"——
     * 服务端暂时没有这些数据，硬塞 0 会让人以为是真实值。详见 {@code CityHallDataCollector}。</p>
     */
    private void applyData(CityHallDataPacket packet) {
        if (boundDocument == null) {
            /* Document 还没就绪，先补一次绑定；绑上后下面这段就能把数据填进去 */
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
        /* 地图中心的来源：优先这名玩家上次看的位置（服务端存在 player_profile 上），
           两个都是 0 表示没记过，那时才退回市政厅方块位置。
           这里只取下来存着，真正写进 DOM 在末尾的 pointMapIfChanged。 */
        mapCenterX = packet.mapCenterX();
        mapCenterZ = packet.mapCenterZ();

        // ---- 左栏：金库与三条民情 ----
        text(document, "chGoldNow", value(packet.gold()));
        text(document, "chGoldMax", limit(packet.goldMax()));
        bar(document, "chGoldBar", packet.gold(), packet.goldMax());
        bar(document, "chUnityBar", packet.unity(), 100);
        bar(document, "chLoyalBar", packet.loyalty(), 100);
        bar(document, "chHappyBar", packet.happiness(), 100);
        text(document, "chUnityNow", percent(packet.unity()));
        text(document, "chLoyalNow", percent(packet.loyalty()));
        text(document, "chHappyNow", percent(packet.happiness()));

        /* 地图框右下角那行小字：标出自绘的采样尺寸。
           页面里是 <world-map radius="192">，直径即 384；真要改范围两处得一起改
           （radius 属性 + 这行）。 */
        text(document, "chMapMeta", "384 × 384");

        // ---- 中栏：城名、三个概况数、领地信息 ----
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

        // ---- 右栏：三个条目列表 ----
        fillLines(document, "chEventList", packet.events(), "最近没有值得记一笔的事");
        fillLines(document, "chTodoList", packet.todos(), "暂无待办");
        fillLines(document, "chLedgerList", packet.ledger(), "暂无进出账");

        // 弹窗若正开着（比如数据后到），把里面的列表一起刷掉
        if (openModal != null) {
            showModal(openModal);
        }

        /* 地图视角最后定。这里**只在目标中心变化时**才写属性——applyData 每帧都会被
           bind() 调到，无条件写会把玩家拖出来的视角每帧拽回原位，拖动等于白拖。 */
        pointMapIfChanged(document);
    }

    // ------------------------------------------------------------ 交互

    /** 当前打开的列表弹窗类型；null 表示没开。数据后到时按它重刷内容。 */
    private ModalKind openModal;

    /* 另外两层的开关状态自己记着：AUI 的 classList 不保证有 contains()，
       靠读属性判断显隐不可靠，索性在切换时同步这两个标志。 */
    private boolean mapFullOpen;
    private boolean dayModalOpen;

    private enum ModalKind { CITIZEN, STRUCTURE, VASSAL }

    private void bindInteractions(Document document) {
        /* 三个概况框：点开各自的列表弹窗 */
        click(document, "chStatPop", () -> showModal(ModalKind.CITIZEN));
        click(document, "chStatBuild", () -> showModal(ModalKind.STRUCTURE));
        click(document, "chStatVassal", () -> showModal(ModalKind.VASSAL));

        /* 日历图标：开日期选择弹窗 */
        click(document, "chCalBtn", this::showDayModal);

        /* 地图框：开全屏地图。先把当前取景抄给全屏层——它是另一个元素实例。
           再把缩略图撤下：两个 <world-map> 同时绘制会各自向服务端请求"自己中心那块"，
           而客户端的地图数据只有一份，会互相把对方冲掉
           （见 .ch-map.is-hidden 的注释）。 */
        click(document, "chMapBox", () -> {
            mapFullOpen = true;
            copyMapView(document);
            toggleClass(document, "chMapBox", "is-hidden", true);
            toggleClass(document, "chMapFull", "is-open", true);
        });

        /* 两个弹窗的关闭钮 */
        click(document, "chModalClose", () -> closeModal(document));
        click(document, "chModalDayClose", () -> {
            dayModalOpen = false;
            toggleClass(document, "chModalDay", "is-open", false);
        });

        /* 搜索框：输入即时过滤当前列表。
           事件名用 input；若 AUI 只派发 change，这里不会生效但也不会报错，
           退化成"打开弹窗时全量显示"，不影响可用性。 */
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

    /** 列表弹窗的搜索词。切换弹窗时清空。 */
    private String searchKeyword = "";

    private void showModal(ModalKind kind) {
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        if (openModal != kind) {
            searchKeyword = "";
            /* 输入框要清空得走 Input.setValue，setTextContent 对它无效 */
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

    private void showDayModal() {
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        Element grid = document.getElementById("chDayGrid");
        if (grid != null) {
            int current = snapshot.day();
            StringBuilder html = new StringBuilder();
            /* 一格一天，从第 1 天排到当前天。上限 180 格，再多就靠滚动看。 */
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

    /**
     * ESC 逐层关闭：先全屏地图，再页内弹窗，最后才轮到界面本身。
     *
     * <p>原稿写的是"点击外侧关闭"，但 AUI 的事件对象能不能拿到 target 没实测过，
     * 按 target 判断有风险；ESC 一定可用，所以用它兜住这一层。</p>
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && boundDocument != null) {
            if (mapFullOpen) {
                mapFullOpen = false;
                toggleClass(boundDocument, "chMapFull", "is-open", false);
                /* 缩略图要放回来，否则关掉全屏后地图框是空的 */
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

    // ------------------------------------------------------------ 列表片段

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

    private boolean matches(String name, String sub) {
        if (searchKeyword.isBlank()) {
            return true;
        }
        String key = searchKeyword;
        return (name != null && name.contains(key)) || (sub != null && sub.contains(key));
    }

    /** 市民名录的第二行：职业与班次，谁有显示谁。 */
    private static String citizenSub(CityHallDataPacket.Citizen citizen) {
        String job = citizen.job() == null ? "" : citizen.job();
        String shift = citizen.shift() == null ? "" : citizen.shift();
        if (job.isBlank()) {
            return shift;
        }
        return shift.isBlank() ? job : job + " · " + shift;
    }

    /** "minecraft:overworld" → "主世界" 之类的简写留给以后；当前只把命名空间去掉。 */
    private static String shortDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "";
        }
        int colon = dimension.indexOf(':');
        return colon < 0 ? dimension : dimension.substring(colon + 1);
    }

    // ------------------------------------------------------------ DOM 小工具

    /**
     * 按服务端给的中心给两张地图定<b>初始</b>视角。
     *
     * <p><b>整个会话只定这一次，之后视角完全归玩家。</b>早先写的是"目标中心变了就重设"，
     * 本意是躲开"每帧覆盖玩家拖动"——但那不够：服务端记的"上次看的位置"就是玩家最后一次
     * 请求的中心，而数据包是定时下发的，包里那个值常常比玩家手上的视角<b>旧一档</b>。
     * 于是刚拖到某处，下一份包一到就把中心拽回去，接着因为区域变了又去请求旧区域，来回震荡
     * ——表现就是"每隔几秒地图中心自己变"。</p>
     *
     * <p>两张地图都设：全屏层是另一个 {@code <world-map>} 实例，有自己的
     * {@code center-x / center-z}，漏掉它点开放大时会跳回页面里的占位值。</p>
     */
    private void pointMapIfChanged(Document document) {
        if (viewInitialized) {
            return;
        }
        viewInitialized = true;
        pointMap(document, "chMap");
        pointMap(document, "chMapFullView");
    }

    /**
     * 给地图元素填中心点。
     *
     * <p>优先用这名玩家<b>自己</b>上次看的位置（服务端存在 player_profile 上），
     * 没记过才退回市政厅方块位置——不是每个人都想从领地中心看起。
     * 改了中心与半径属性会触发元素重新取一块新区域，见 {@code WorldMapElement.ensureData}。</p>
     */
    private void pointMap(Document document, String id) {
        Element map = document.getElementById(id);
        if (map == null) {
            return;
        }
        boolean hasSaved = mapCenterX != 0 || mapCenterZ != 0;
        map.setAttribute("center-x", String.valueOf(hasSaved ? mapCenterX : hallX));
        map.setAttribute("center-z", String.valueOf(hasSaved ? mapCenterZ : hallZ));
        /* 标记点始终是市政厅方块本身，不跟着视角跑——
           它是"我家在哪"的锚，玩家怎么拖地图它都钉在那片地面上。 */
        map.setAttribute("marker-x", String.valueOf(hallX));
        map.setAttribute("marker-z", String.valueOf(hallZ));
    }

    /**
     * 把缩略图当前的取景（中心与缩放）抄给全屏那张地图。
     *
     * <p>两张地图是两个独立的 {@code <world-map>} 元素实例，各持自己的属性。
     * 不抄的话，玩家在缩略图上拖到某处再点开放大，全屏那张会跳回初始中心——
     * 「放大」反而把视角丢了。</p>
     */
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

    /** 给 <npc-bar> 设值。上限为 0（无数据）时给 1，元素才不会除零；条会显示成空。 */
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

    /** 数字：0 视为"无数据"，显示成 —。 */
    private static String value(long number) {
        return number == 0L ? "—" : String.valueOf(number);
    }

    /** 百分比：0 视为"无数据"，显示成 —。 */
    private static String percent(int number) {
        return number == 0 ? "—" : String.valueOf(number);
    }

    /** 上限：0 视为"无上限/无数据"，显示成空串，避免出现 "/ 0"。 */
    private static String limit(long max) {
        return max <= 0L ? "" : "/" + max;
    }

    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }

    /** 拼 HTML 前必须转义，否则居民名里出现尖括号会把结构撑坏。 */
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
