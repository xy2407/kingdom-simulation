package com.xy2407.kingdom.client.rts;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.init.Node;
import com.sighs.apricityui.layout.Position;
import com.xy2407.kingdom.client.structure.StructureDebugChat;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.network.npc.NpcRosterPacket;
import com.xy2407.kingdom.common.network.npc.NpcRosterRequestPacket;
import com.xy2407.kingdom.common.npc.NpcSkins;
import com.xy2407.kingdom.common.network.rts.RtsOpsCommandPacket;
import com.xy2407.kingdom.common.network.rts.RtsTeamSyncPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RTS 指挥面板：七分组框的成员录入、头像跳转与坚守进攻指令的客户端状态
 */
@OnlyIn(Dist.CLIENT)
public final class RtsOpsPanel {

    public static final String PAGE = "ui/rts_ops.html";

    private static final String DOCK_ID = "rtsops-dock";
    private static final String BOXES_ID = "rtsops-boxes";
    private static final String FOOT_ID = "rtsops-foot";
    private static final String TITLE_ID = "rtsops-title";
    private static final String BTN_ALL = "rtsops-btn-all";
    private static final String BTN_HOLD = "rtsops-btn-hold";
    private static final String BTN_ATTACK = "rtsops-btn-attack";
    private static final String BTN_CLEAR = "rtsops-btn-clear";

    private static final int MAX_BOXES = 7;
    private static final int DOCK_CHROME = 4;
    private static final int BOX_CHROME = 4;
    private static final int CHIP_SIZE = 28;
    private static final int CHIP_GAP = 4;
    private static final int FACES_PADDING = 8;
    /** 单框最多渲染的头像数：超出部分走滚动条，超过这个上限才折叠成"还有 N 人"。 */
    private static final int MAX_CHIPS = 240;

    /** 分组姿态：未下达 / 坚守 / 进攻。纯内存，随会话结束清空。 */
    private static final int STATE_NONE = 0;
    private static final int STATE_HOLD = 1;
    private static final int STATE_ATTACK = 2;

    private static final List<LinkedHashSet<UUID>> BOXES = new ArrayList<>();
    private static final int[] BOX_STATE = new int[MAX_BOXES];

    private static int armedBox = -1;
    private static boolean suppressCapture;
    private static final Map<UUID, NpcRosterPacket.Entry> ROSTER = new HashMap<>();

    private static int lastWidth = -1;
    private static int lastHeight = -1;
    private static String lastTitle = "";
    /** 当前焦点分组，变化时重刷按钮高亮，避免每帧写 DOM。 */
    private static int lastFocus = Integer.MIN_VALUE;
    /** 上次鼠标位置：hover 标题只在鼠标移动时重算。 */
    private static double lastHoverX = Double.NaN;
    private static double lastHoverY = Double.NaN;

    private RtsOpsPanel() {
    }

    static {
        for (int i = 0; i < MAX_BOXES; i++) {
            BOXES.add(new LinkedHashSet<>());
        }
    }

    public static boolean isOpen() {
        return !ApricityUI.getDocument(PAGE).isEmpty();
    }

    public static void open() {
        if (isOpen()) {
            return;
        }
        Document document = ApricityUI.createDocument(PAGE);
        if (document == null) {
            StructureDebugChat.chat("创建 RTS 指挥面板失败：模板缺失：%s", PAGE);
            return;
        }
        document.setReloadPersistent(true);
        injectBoxes(document);
        bindButtons(document);
        lastWidth = -1;
        lastHeight = -1;
        lastFocus = Integer.MIN_VALUE;
        lastHoverX = Double.NaN;
        lastHoverY = Double.NaN;
        layout();
        rebuildFaces();
        refreshCommandState();
        updateTitle(document);
        requestRoster();
    }

    public static void close() {
        if (isOpen()) {
            ApricityUI.removeDocument(PAGE);
        }
    }

    public static void tick() {
        if (!isOpen()) {
            return;
        }
        layout();
        updateHoverTitle();
        syncFocus();
    }

    private static void injectBoxes(Document document) {
        Element host = document.getElementById(BOXES_ID);
        if (host == null) {
            return;
        }
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < MAX_BOXES; i++) {
            html.append("<div class=\"ops-box\" id=\"rtsops-box-").append(i).append("\">")
                    .append("<div class=\"ops-box-head\">")
                    .append("<span class=\"ops-box-name\">组 ").append(i + 1).append("</span>")
                    .append("<span class=\"ops-box-state\" id=\"rtsops-state-").append(i).append("\"></span>")
                    .append("<span class=\"ops-box-count\" id=\"rtsops-count-").append(i).append("\">0</span>")
                    .append("</div>")
                    .append("<div class=\"ops-faces\" id=\"rtsops-faces-").append(i).append("\"></div>")
                    .append("</div>");
        }
        host.setInnerHTML(html.toString());
        for (int i = 0; i < MAX_BOXES; i++) {
            Element box = document.getElementById("rtsops-box-" + i);
            if (box != null) {
                final int index = i;
                box.addEventListener("click", event -> armBox(index));
            }
        }
    }

    private static void bindButtons(Document document) {
        bindClick(document, BTN_ALL, () -> selectAll());
        bindClick(document, BTN_HOLD, () -> sendAction(RtsOpsCommandPacket.ACTION_HOLD));
        bindClick(document, BTN_ATTACK, () -> sendAction(RtsOpsCommandPacket.ACTION_ATTACK));
        bindClick(document, BTN_CLEAR, () -> clearRecords());
    }

    private static void bindClick(Document document, String id, Runnable action) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.addEventListener("click", event -> action.run());
        }
    }

    private static void armBox(int index) {
        armedBox = armedBox == index ? -1 : index;
        lastFocus = focusBox();
        refreshCommandState();
        updateTitle(documentOrNull());
    }

    /**
     * 重刷分组姿态徽标，并让底部「坚守 / 进攻」按钮跟着当前焦点分组常驻高亮。
     * 状态与分组只存在内存里：面板是「临时指挥所」，关面板再开还在，断开/换世界就清空。
     */
    private static void refreshCommandState() {
        Document document = documentOrNull();
        if (document == null) {
            return;
        }
        int focus = focusBox();
        for (int i = 0; i < MAX_BOXES; i++) {
            StringBuilder classes = new StringBuilder("ops-box");
            if (i == armedBox) {
                classes.append(" on");
            }
            if (BOX_STATE[i] == STATE_HOLD) {
                classes.append(" state-hold");
            } else if (BOX_STATE[i] == STATE_ATTACK) {
                classes.append(" state-attack");
            }
            Element box = document.getElementById("rtsops-box-" + i);
            if (box != null) {
                box.setAttribute("class", classes.toString());
            }
            Element badge = document.getElementById("rtsops-state-" + i);
            if (badge == null) {
                continue;
            }
            if (BOX_STATE[i] == STATE_HOLD) {
                badge.setAttribute("class", "ops-box-state on-hold");
                badge.setInnerHTML("坚守");
            } else if (BOX_STATE[i] == STATE_ATTACK) {
                badge.setAttribute("class", "ops-box-state on-attack");
                badge.setInnerHTML("进攻");
            } else {
                badge.setAttribute("class", "ops-box-state");
                badge.setInnerHTML("");
            }
        }
        setButtonClass(document, BTN_HOLD, focus >= 0 && BOX_STATE[focus] == STATE_HOLD
                ? "ops-btn on-hold" : "ops-btn");
        setButtonClass(document, BTN_ATTACK, focus >= 0 && BOX_STATE[focus] == STATE_ATTACK
                ? "ops-btn on-attack" : "ops-btn");
    }

    private static void setButtonClass(Document document, String id, String classes) {
        Element button = document.getElementById(id);
        if (button != null) {
            button.setAttribute("class", classes);
        }
    }

    /** 焦点分组：优先玩家刚点开的待录入框，否则取与当前 RTS 选中重合最多的那一组。 */
    private static int focusBox() {
        if (armedBox >= 0) {
            return armedBox;
        }
        Set<UUID> selected = RtsModeManager.getSelectedEntities();
        if (selected.isEmpty()) {
            return -1;
        }
        int best = -1;
        int bestHits = 0;
        for (int i = 0; i < MAX_BOXES; i++) {
            int hits = 0;
            for (UUID id : BOXES.get(i)) {
                if (selected.contains(id)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = i;
            }
        }
        return best;
    }

    private static void syncFocus() {
        int focus = focusBox();
        if (focus != lastFocus) {
            lastFocus = focus;
            refreshCommandState();
        }
    }

    private static void updateTitle(Document document) {
        if (document == null) {
            return;
        }
        lastTitle = baseTitle();
        Element title = document.getElementById(TITLE_ID);
        if (title != null) {
            title.setInnerHTML(lastTitle);
        }
    }

    private static String baseTitle() {
        if (armedBox < 0) {
            return "RTS 指挥 · 点击下方分组框后，世界内框选的市民将录入该组";
        }
        String posture = switch (BOX_STATE[armedBox]) {
            case STATE_HOLD -> " · 坚守中";
            case STATE_ATTACK -> " · 进攻中";
            default -> "";
        };
        return "RTS 指挥 · 组 " + (armedBox + 1) + posture + "，框选即入队（同队整队录入）";
    }

    public static void onWorldSelection() {
        if (suppressCapture || !isOpen() || armedBox < 0) {
            return;
        }
        Set<UUID> selected = RtsModeManager.getSelectedEntities();
        if (selected.isEmpty()) {
            return;
        }
        Set<UUID> citizens = onlyCitizens(selected);
        if (citizens.isEmpty()) {
            StructureDebugChat.chat("框选里没有市民：玩家假人与其它实体不进编组");
            return;
        }
        assignToBox(armedBox, citizens);
    }

    /**
     * 只认市民实体：RTS 框选也能框到玩家假人（{@code RtsFakePlayerEntity}）与其它实体，
     * 那些都不是市民，不能进编组框，否则框里会出现没有皮肤、也下不了命令的幽灵格子。
     */
    private static Set<UUID> onlyCitizens(Collection<UUID> ids) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        Minecraft minecraft = Minecraft.getInstance();
        for (UUID id : ids) {
            if (id == null) {
                continue;
            }
            Entity entity = ClientEntities.byUuid(minecraft.level, id);
            if (entity instanceof NpcEntity npc && !npc.isTouristEntity()) {
                result.add(id);
            }
        }
        return result;
    }

    private static void assignToBox(int target, Collection<UUID> ids) {
        Set<UUID> expanded = expandTeams(ids);
        for (int i = 0; i < MAX_BOXES; i++) {
            if (i != target) {
                BOXES.get(i).removeAll(expanded);
            }
        }
        BOXES.get(target).addAll(expanded);
        dropEmptyBoxStates();
        rebuildFaces();
        refreshCommandState();
    }

    /** 分组被清空后姿态没有意义，跟着归零，避免空框还挂着「坚守」徽标。 */
    private static void dropEmptyBoxStates() {
        for (int i = 0; i < MAX_BOXES; i++) {
            if (BOXES.get(i).isEmpty()) {
                BOX_STATE[i] = STATE_NONE;
            }
        }
    }

    private static Set<UUID> expandTeams(Collection<UUID> ids) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id == null) {
                continue;
            }
            result.add(id);
            RtsTeamSyncPacket.Entry team = RtsTeamClientCache.byMember(id);
            if (team != null) {
                if (team.leader() != null) {
                    result.add(team.leader());
                }
                result.addAll(team.members());
            }
        }
        return result;
    }

    private static void selectAll() {
        if (armedBox < 0) {
            StructureDebugChat.chat("先点击一个分组框，再「选中全部」");
            return;
        }
        Set<UUID> members = BOXES.get(armedBox);
        if (members.isEmpty()) {
            StructureDebugChat.chat("组 " + (armedBox + 1) + " 还没有记录任何市民");
            return;
        }
        runWithoutCapture(() -> RtsModeManager.setSelectedEntities(new HashSet<>(members)));
        StructureDebugChat.chat("已选中组 " + (armedBox + 1) + " 全部 " + members.size() + " 名市民");
    }

    private static void sendAction(int action) {
        Set<UUID> selected = RtsModeManager.getSelectedEntities();
        if (selected.isEmpty()) {
            StructureDebugChat.chat("先选中要下达命令的市民");
            return;
        }
        PacketDistributor.sendToServer(new RtsOpsCommandPacket(action));
        boolean recorded = recordBoxState(action, selected);
        String tail = recorded ? "，该组按钮常驻高亮" : "（这批市民不在任何分组框内，姿态未记入队列）";
        if (action == RtsOpsCommandPacket.ACTION_HOLD) {
            StructureDebugChat.chat("已下达坚守：选中市民原地防守" + tail);
        } else {
            StructureDebugChat.chat("已进入进攻：选中市民主动索敌" + tail);
        }
    }

    /**
     * 把这次命令记到「与该批市民有交集」的分组上；一个都没命中时退回当前待录入框。
     * 返回是否真的记进了某个分组，用于给玩家明确反馈。
     */
    private static boolean recordBoxState(int action, Set<UUID> selected) {
        int state = action == RtsOpsCommandPacket.ACTION_HOLD ? STATE_HOLD : STATE_ATTACK;
        boolean touched = false;
        for (int i = 0; i < MAX_BOXES; i++) {
            for (UUID id : BOXES.get(i)) {
                if (selected.contains(id)) {
                    BOX_STATE[i] = state;
                    touched = true;
                    break;
                }
            }
        }
        if (!touched && armedBox >= 0) {
            BOX_STATE[armedBox] = state;
            touched = true;
        }
        if (touched) {
            lastFocus = focusBox();
            refreshCommandState();
            updateTitle(documentOrNull());
        }
        return touched;
    }

    private static void clearRecords() {
        if (armedBox >= 0) {
            BOXES.get(armedBox).clear();
            BOX_STATE[armedBox] = STATE_NONE;
        } else {
            for (int i = 0; i < MAX_BOXES; i++) {
                BOXES.get(i).clear();
                BOX_STATE[i] = STATE_NONE;
            }
        }
        rebuildFaces();
        refreshCommandState();
    }

    private static void runWithoutCapture(Runnable action) {
        suppressCapture = true;
        try {
            action.run();
        } finally {
            suppressCapture = false;
        }
    }

    private static void requestRoster() {
        PacketDistributor.sendToServer(new NpcRosterRequestPacket());
    }

    public static void acceptRoster(NpcRosterPacket packet) {
        for (NpcRosterPacket.Entry entry : packet.entries()) {
            // 编组/选中用的是实体 UUID，名册里同时给了数据层 id 与实体 UUID，这里按实体 UUID 建索引
            ROSTER.put(entry.entityId() != null ? entry.entityId() : entry.id(), entry);
        }
        if (packet.last() && isOpen()) {
            rebuildFaces();
        }
    }

    private static void rebuildFaces() {
        Document document = documentOrNull();
        if (document == null) {
            return;
        }
        for (int i = 0; i < MAX_BOXES; i++) {
            Element count = document.getElementById("rtsops-count-" + i);
            if (count != null) {
                count.setInnerHTML(String.valueOf(BOXES.get(i).size()));
            }
            rebuildBoxFaces(document, i);
        }
    }

    private static void rebuildBoxFaces(Document document, int boxIndex) {
        Element host = document.getElementById("rtsops-faces-" + boxIndex);
        if (host == null) {
            return;
        }
        LinkedHashSet<UUID> members = BOXES.get(boxIndex);
        if (members.isEmpty()) {
            host.setInnerHTML("<div class=\"ops-empty\">空</div>");
            return;
        }
        List<UUID> visible = new ArrayList<>();
        for (UUID id : members) {
            if (visible.size() >= MAX_CHIPS) {
                break;
            }
            visible.add(id);
        }
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < visible.size(); index++) {
            UUID id = visible.get(index);
            // 名册里 skin 可能是空串（市民还没分配皮肤）。此时必须显式给默认皮肤：
            // <kingdom-entity-avatar> 的 skin 为空会回落到"身份卡目标"，而 RTS 面板没有身份卡，
            // 结果就是整块头像不渲染（不是画错，是直接 return）。
            NpcRosterPacket.Entry entry = ROSTER.get(id);
            String skin = entry == null || entry.skin() == null || entry.skin().isBlank()
                    ? NpcSkins.DEFAULT_SKIN : entry.skin();
            html.append("<div class=\"ops-chip\" id=\"rtsops-chip-").append(boxIndex)
                    .append("-").append(index).append("\">")
                    .append("<kingdom-entity-avatar class=\"ops-avatar\" width=\"24\" height=\"24\" skin=\"")
                    .append(escapeAttr(skin))
                    .append("\"></kingdom-entity-avatar>")
                    .append("</div>");
        }
        int rest = members.size() - visible.size();
        if (rest > 0) {
            html.append("<div class=\"ops-faces-more\">…还有 ").append(rest).append(" 人（超出绘制上限）</div>");
        }
        host.setInnerHTML(html.toString());
        // 滚动位置复位：重建后旧偏移没有意义
        host.setScrollTop(0.0D);
        for (int index = 0; index < visible.size(); index++) {
            UUID id = visible.get(index);
            Element chip = document.getElementById("rtsops-chip-" + boxIndex + "-" + index);
            if (chip != null) {
                chip.addEventListener("click", event -> focusMember(id));
            }
        }
    }

    private static void focusMember(UUID id) {
        NpcRosterPacket.Entry entry = ROSTER.get(id);
        double x;
        double y;
        double z;
        Entity entity = ClientEntities.byUuid(
                Minecraft.getInstance().level, id);
        if (entity != null) {
            Vec3 pos = entity.position();
            x = pos.x;
            y = pos.y;
            z = pos.z;
        } else if (entry != null) {
            x = entry.x() + 0.5D;
            y = entry.y();
            z = entry.z() + 0.5D;
        } else {
            StructureDebugChat.chat("该市民暂无位置数据");
            return;
        }
        runWithoutCapture(() -> RtsModeManager.setSelectedEntities(Set.of(id)));
        // 89° = 近乎垂直向下俯视：相机落在该市民正上方，画面中心就是他的脚下，
        // 原来的 30° 斜视会把脚下的人甩到画面外（看着像没跳过去）。
        RtsModeManager.teleportCameraTo(new Vec3(x, y, z), 89.0F);
    }

    private static void updateHoverTitle() {
        Document document = documentOrNull();
        if (document == null) {
            return;
        }
        Element title = document.getElementById(TITLE_ID);
        if (title == null) {
            return;
        }
        // 头像可能上百个，逐格 isHover 每帧要查上千次 DOM；改成一次命中测试 + 鼠标静止短路。
        double[] mouse = RtsModeManager.getGuiScaledMouse();
        if (mouse[0] == lastHoverX && mouse[1] == lastHoverY) {
            return;
        }
        lastHoverX = mouse[0];
        lastHoverY = mouse[1];
        String hoveredName = hoveredChipName(document, mouse);
        String current = hoveredName == null ? baseTitle() : hoveredName;
        if (!current.equals(lastTitle)) {
            lastTitle = current;
            title.setInnerHTML(current);
        }
    }

    /** 鼠标下的头像格子 → "组 N · 名字"，不走全量遍历。 */
    private static String hoveredChipName(Document document, double[] mouse) {
        Position screen = new Position(mouse[0], mouse[1]);
        Element chip = null;
        for (Node node = document.hitTest(document.screenToDocumentPosition(screen));
                node instanceof Element current; node = current.getParentNode()) {
            String id = current.getAttribute("id");
            if (id != null && id.startsWith("rtsops-chip-")) {
                chip = current;
                break;
            }
        }
        if (chip == null) {
            return null;
        }
        String[] parts = chip.getAttribute("id").split("-");
        if (parts.length < 4) {
            return null;
        }
        int boxIndex;
        int chipIndex;
        try {
            boxIndex = Integer.parseInt(parts[2]);
            chipIndex = Integer.parseInt(parts[3]);
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (boxIndex < 0 || boxIndex >= MAX_BOXES) {
            return null;
        }
        List<UUID> list = new ArrayList<>(BOXES.get(boxIndex));
        if (chipIndex < 0 || chipIndex >= list.size()) {
            return null;
        }
        NpcRosterPacket.Entry entry = ROSTER.get(list.get(chipIndex));
        String name = entry == null ? "市民" : entry.name();
        return "组 " + (boxIndex + 1) + " · " + name + "（点击头像跳转并选中）";
    }

    private static void layout() {
        Document document = documentOrNull();
        if (document == null) {
            return;
        }
        var viewport = document.getViewportSize();
        int width = (int) Math.round(viewport.width());
        int height = (int) Math.round(viewport.height());
        if (width == lastWidth && height == lastHeight) {
            return;
        }
        lastWidth = width;
        lastHeight = height;

        int dockHeight = Math.max(80, height / 4);
        applyStyle(document, DOCK_ID, "height:" + dockHeight + "px");
        // 按钮行在底框外面、贴着它的上沿，留 8px 间距（水平居中由 CSS flex 负责）
        applyStyle(document, FOOT_ID, "bottom:" + (dockHeight + 8) + "px");

        int innerWidth = width - DOCK_CHROME - 16 - (MAX_BOXES - 1) * 6;
        int boxWidth = Math.max(64, innerWidth / MAX_BOXES);
        for (int i = 0; i < MAX_BOXES; i++) {
            applyStyle(document, "rtsops-box-" + i, "width:" + boxWidth + "px");
        }

        // 预留 8px 给竖向滚动条（.ops-faces 现在 overflow-y:auto），否则最后一列头像会被压在滚动条下面
        int facesWidth = boxWidth - BOX_CHROME - FACES_PADDING - 8;
        int columns = Math.max(1, (facesWidth + CHIP_GAP) / (CHIP_SIZE + CHIP_GAP));
        for (int i = 0; i < MAX_BOXES; i++) {
            applyStyle(document, "rtsops-faces-" + i,
                    "grid-template-columns:repeat(" + columns + "," + CHIP_SIZE + "px)");
        }
        rebuildFaces();
    }

    private static void applyStyle(Document document, String id, String style) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setAttribute("style", style);
        }
    }

    // ---- 会话内生命周期：这是一次性的临时指挥所，不落盘、不跨局保留 ----

    /**
     * 市民死亡或被移除：从名册缓存与所有分组里摘掉。
     * 不处理的话，头像会一直留在框里，点它还会继续跳相机/选中一个已经不存在的实体。
     */
    public static void onEntityRemoved(UUID entityId) {
        if (entityId == null) {
            return;
        }
        ROSTER.remove(entityId);
        boolean touched = false;
        for (int i = 0; i < MAX_BOXES; i++) {
            if (BOXES.get(i).remove(entityId)) {
                touched = true;
            }
        }
        if (!touched) {
            return;
        }
        dropEmptyBoxStates();
        rebuildFaces();
        refreshCommandState();
    }

    /** 断开连接 / 换世界：整块清空（分组、姿态、名册、待录入框）。 */
    public static void reset() {
        close();
        for (LinkedHashSet<UUID> box : BOXES) {
            box.clear();
        }
        Arrays.fill(BOX_STATE, STATE_NONE);
        ROSTER.clear();
        armedBox = -1;
        lastFocus = Integer.MIN_VALUE;
        lastTitle = "";
        lastWidth = -1;
        lastHeight = -1;
        lastHoverX = Double.NaN;
        lastHoverY = Double.NaN;
    }

    private static Document documentOrNull() {
        List<Document> documents = ApricityUI.getDocument(PAGE);
        return documents.isEmpty() ? null : documents.get(0);
    }

    private static String escapeAttr(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
