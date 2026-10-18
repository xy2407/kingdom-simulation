package com.xy2407.kingdom.client.builder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.common.network.builder.BuilderAssignPacket;
import com.xy2407.kingdom.common.network.builder.BuilderBoardDataPacket;
import com.xy2407.kingdom.common.network.builder.BuilderBoardRequestPacket;
import com.xy2407.kingdom.common.network.builder.BuilderEmployeePacket;
import com.xy2407.kingdom.common.network.builder.BuilderOpenPacket;
import com.xy2407.kingdom.common.network.builder.BuilderTaskActionPacket;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建筑工坊界面：左员工右任务双栏，头像可拖拽指派，卡片支持开始暂停终止
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderBoardScreen extends ApricityScreen {

    public static final String PAGE = "ui/builder.html";

    private static final UUID NULL_ID = new UUID(0L, 0L);
    private static final int REFRESH_INTERVAL_TICKS = 40;
    private static final int MAX_MATERIAL_ROWS = 16;
    private static final int BAR_WIDTH = 416;

    private record Drag(UUID npcId, String skin, UUID fromTask, Element source) {
    }

    private static volatile BuilderBoardDataPacket snapshot =
            new BuilderBoardDataPacket(List.of(), List.of());

    private static Document boundDocument;

    private final Set<Integer> expanded = new HashSet<>();
    private final List<BuilderBoardDataPacket.TaskEntry> cards = new ArrayList<>();
    private final List<UUID> cardIds = new ArrayList<>();
    private Drag drag;
    private double dragX;
    private double dragY;
    private int refreshCounter;

    public BuilderBoardScreen() {
        super(PAGE);
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new BuilderBoardScreen());
        PacketDistributor.sendToServer(new BuilderBoardRequestPacket());
    }

    public static void acceptOpen(BuilderOpenPacket packet) {
        open();
    }

    public static void accept(BuilderBoardDataPacket packet) {
        snapshot = packet == null ? new BuilderBoardDataPacket(List.of(), List.of()) : packet;
        if (Minecraft.getInstance().screen instanceof BuilderBoardScreen screen) {
            screen.bind();
        }
    }

    @Override
    protected void init() {
        super.init();
        bind();
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                       float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Document document = getLinkedDocument();
        if (document != null && document != boundDocument) {
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
        PacketDistributor.sendToServer(new BuilderBoardRequestPacket());
    }

    @Override
    public void onClose() {
        drag = null;
        super.onClose();
    }

    private void bind() {
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        if (document != boundDocument) {
            boundDocument = document;
            bindStatic(document);
        }
        applyData(document, snapshot);
    }

    private void bindStatic(Document document) {
        click(document, "bb-close", () -> Minecraft.getInstance().setScreen(null));
        click(document, "bb-hire", () ->
                PacketDistributor.sendToServer(new BuilderEmployeePacket(NULL_ID, true)));
        document.addEventListener("mousemove", this::onDocumentMouseMove);
        document.addEventListener("mouseup", this::onDocumentMouseUp);
    }

    private void onDocumentMouseMove(Event event) {
        if (drag == null || !(event instanceof MouseEvent mouse)) {
            return;
        }
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        Position position = document.screenToDocumentPosition(new Position(mouse.clientX, mouse.clientY));
        dragX = position.x;
        dragY = position.y;
        Element ghost = document.getElementById("bb-ghost");
        if (ghost != null) {
            ghost.setAttribute("style", "display:block;position:absolute;left:"
                    + (int) position.x + "px;top:" + (int) position.y + "px");
        }
    }

    private void onDocumentMouseUp(Event event) {
        Drag source = drag;
        if (source == null) {
            return;
        }
        drag = null;
        Document document = boundDocument;
        if (document != null) {
            Element ghost = document.getElementById("bb-ghost");
            if (ghost != null) {
                ghost.setAttribute("style", "display:none");
            }
        }
        if (source.source() != null) {
            source.source().getClassList().toggle("bb-dragging", false);
        }
        UUID target = taskAt(document, dragX, dragY);
        if (source.fromTask() == null) {
            if (target != null) {
                PacketDistributor.sendToServer(new BuilderAssignPacket(target, source.npcId(), true));
            }
            return;
        }
        if (target == null || !target.equals(source.fromTask())) {
            if (target != null) {
                PacketDistributor.sendToServer(new BuilderAssignPacket(target, source.npcId(), true));
            }
            PacketDistributor.sendToServer(new BuilderAssignPacket(source.fromTask(), source.npcId(), false));
        }
        refreshCounter = REFRESH_INTERVAL_TICKS;
    }

    private UUID taskAt(Document document, double docX, double docY) {
        if (document == null) {
            return null;
        }
        for (int index = 0; index < cardIds.size(); index++) {
            Element card = document.getElementById("bb-task-" + index);
            if (card == null) {
                continue;
            }
            Element.DOMRect rect = card.getBoundingClientRect();
            if (docX >= rect.left && docX <= rect.right && docY >= rect.top && docY <= rect.bottom) {
                return cardIds.get(index);
            }
        }
        return null;
    }

    private void applyData(Document document, BuilderBoardDataPacket packet) {
        cards.clear();
        cardIds.clear();
        if (packet.tasks() != null) {
            cards.addAll(packet.tasks());
            for (BuilderBoardDataPacket.TaskEntry task : cards) {
                cardIds.add(task.id());
            }
        }
        text(document, "bb-emp-count",
                packet.employees() == null ? "" : " 共 " + packet.employees().size() + " 人");
        applyEmployees(document, packet.employees());
        applyTasks(document, cards);
    }

    private void applyEmployees(Document document, List<BuilderBoardDataPacket.EmployeeEntry> employees) {
        Element host = document.getElementById("bb-emp-list");
        if (host == null) {
            return;
        }
        if (employees == null || employees.isEmpty()) {
            host.setInnerHTML("<div class=\"bb-empty\">还没有建筑工，点上面「雇佣市民」收一个人</div>");
            return;
        }
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < employees.size(); index++) {
            BuilderBoardDataPacket.EmployeeEntry employee = employees.get(index);
            html.append("<div class=\"bb-emp-row\">")
                    .append("<div class=\"bb-face-frame\" id=\"bb-emp-").append(index).append("\">")
                    .append("<kingdom-entity-avatar class=\"bb-face\" skin=\"")
                    .append(escape(employee.skin())).append("\"></kingdom-entity-avatar></div>")
                    .append("<div class=\"bb-emp-info\">")
                    .append("<div class=\"bb-emp-name\">").append(escape(employee.name())).append("</div>")
                    .append("<div class=\"bb-emp-status\">").append(escape(employee.status())).append("</div>")
                    .append("</div>")
                    .append("<div class=\"bb-btn bb-btn-danger bb-btn-mini\" id=\"bb-fire-").append(index)
                    .append("\">解雇</div>")
                    .append("</div>");
        }
        host.setInnerHTML(html.toString());
        for (int index = 0; index < employees.size(); index++) {
            BuilderBoardDataPacket.EmployeeEntry employee = employees.get(index);
            Element frame = document.getElementById("bb-emp-" + index);
            if (frame != null) {
                frame.addEventListener("mousedown", event ->
                        startDrag(event, employee.npcId(), employee.skin(), null, frame));
            }
            Element fire = document.getElementById("bb-fire-" + index);
            if (fire != null) {
                fire.addEventListener("click", event -> PacketDistributor.sendToServer(
                        new BuilderEmployeePacket(employee.npcId(), false)));
            }
        }
    }

    private void applyTasks(Document document, List<BuilderBoardDataPacket.TaskEntry> tasks) {
        Element host = document.getElementById("bb-task-list");
        if (host == null) {
            return;
        }
        if (tasks.isEmpty()) {
            host.setInnerHTML("<div class=\"bb-empty\">暂无任务：RTS 里打开左侧「填充/拆除」框选范围即可创建</div>");
            return;
        }
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < tasks.size(); index++) {
            html.append(taskHtml(tasks.get(index), index, expanded.contains(index)));
        }
        host.setInnerHTML(html.toString());
        for (int index = 0; index < tasks.size(); index++) {
            bindTask(document, tasks.get(index), index);
        }
    }

    private static String taskHtml(BuilderBoardDataPacket.TaskEntry task, int index, boolean expanded) {
        String kindLabel;
        String kindClass;
        if (task.kind() == BuilderBoardDataPacket.KIND_BUILDING) {
            kindLabel = "建筑";
            kindClass = "bb-kind";
        } else if (task.kind() == BuilderBoardDataPacket.KIND_DEMOLISH) {
            kindLabel = "拆除";
            kindClass = "bb-kind bb-kind-demolish";
        } else {
            kindLabel = "填充";
            kindClass = "bb-kind bb-kind-fill";
        }
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"bb-task-card\" id=\"bb-task-").append(index).append("\">")
                .append("<div class=\"bb-task-head\" id=\"bb-toggle-").append(index).append("\">")
                .append("<span class=\"").append(kindClass).append("\">").append(kindLabel).append("</span>")
                .append("<span class=\"bb-task-title\">").append(escape(task.title())).append("</span>")
                .append("<span class=\"bb-task-status\">").append(escape(statusLabel(task.status()))).append("</span>")
                .append("</div>")
                .append("<div class=\"bb-task-pos\">范围 (").append(task.minX()).append(',').append(task.minY())
                .append(',').append(task.minZ()).append(") ~ (").append(task.maxX()).append(',')
                .append(task.maxY()).append(',').append(task.maxZ()).append(")</div>")
                .append("<div class=\"bb-bar-row\"><div class=\"bb-bar\"><div class=\"bb-bar-fill\" style=\"left:0;top:0;height:10px;width:")
                .append(barWidth(task)).append("px\"></div></div>")
                .append("<span class=\"bb-bar-num\">").append(task.cursor()).append('/').append(task.total())
                .append("</span></div>")
                .append("<div class=\"bb-detail").append(expanded ? " bb-detail-open" : "")
                .append("\" id=\"bb-detail-").append(index).append("\">")
                .append(materialsHtml(task))
                .append(crewHtml(task, index))
                .append("</div>")
                .append("<div class=\"bb-task-actions\">")
                .append("<div class=\"bb-btn bb-act\" id=\"bb-start-").append(index).append("\">开始</div>")
                .append("<div class=\"bb-btn bb-act\" id=\"bb-pause-").append(index).append("\">暂停</div>")
                .append("<div class=\"bb-btn bb-btn-danger bb-act\" id=\"bb-stop-").append(index).append("\">终止</div>")
                .append("</div></div>");
        return html.toString();
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "RUNNING" -> "进行中";
            case "PAUSED" -> "已暂停";
            case "READY" -> "待开始";
            default -> status == null ? "" : status;
        };
    }

    private static int barWidth(BuilderBoardDataPacket.TaskEntry task) {
        if (task.total() <= 0) {
            return 0;
        }
        return (int) Math.min(BAR_WIDTH, (long) BAR_WIDTH * Math.max(0, task.cursor()) / task.total());
    }

    private static String materialsHtml(BuilderBoardDataPacket.TaskEntry task) {
        StringBuilder html = new StringBuilder();
        if (task.kind() == BuilderBoardDataPacket.KIND_BUILDING
                || task.materials() == null || task.materials().isEmpty()) {
            html.append("<div class=\"bb-mat-row\"><span class=\"bb-mat-text\">")
                    .append(task.kind() == BuilderBoardDataPacket.KIND_BUILDING
                            ? "蓝图施工，无需材料" : "暂无材料数据")
                    .append("</span></div>");
            return html.toString();
        }
        int rows = Math.min(task.materials().size(), MAX_MATERIAL_ROWS);
        for (int index = 0; index < rows; index++) {
            BuilderBoardDataPacket.MatEntry material = task.materials().get(index);
            html.append("<div class=\"bb-mat-row\"><div class=\"bb-mat-box\">")
                    .append("<item class=\"bb-mat-icon\">").append(escape(material.itemId()))
                    .append("</item></div>")
                    .append("<span class=\"bb-mat-text\">").append(escape(material.itemId()))
                    .append(" × ").append(material.count()).append("</span></div>");
        }
        return html.toString();
    }

    private static String crewHtml(BuilderBoardDataPacket.TaskEntry task, int taskIndex) {
        StringBuilder html = new StringBuilder("<div class=\"bb-crew\">");
        if (task.npcs() == null || task.npcs().isEmpty()) {
            html.append("<span class=\"bb-crew-empty\">把左侧员工头像拖到这里指派</span></div>");
            return html.toString();
        }
        for (int index = 0; index < task.npcs().size(); index++) {
            BuilderBoardDataPacket.NpcRef npc = task.npcs().get(index);
            html.append("<div class=\"bb-crew-chip\" id=\"bb-crew-").append(taskIndex)
                    .append('-').append(index).append("\">")
                    .append("<div class=\"bb-face-frame\">")
                    .append("<kingdom-entity-avatar class=\"bb-face\" skin=\"")
                    .append(escape(npc.skin())).append("\"></kingdom-entity-avatar></div>")
                    .append("<span class=\"bb-crew-name\">").append(escape(npc.name())).append("</span>")
                    .append("</div>");
        }
        return html.append("</div>").toString();
    }

    private void bindTask(Document document, BuilderBoardDataPacket.TaskEntry task, int index) {
        Element toggle = document.getElementById("bb-toggle-" + index);
        if (toggle != null) {
            toggle.addEventListener("click", event -> {
                if (expanded.contains(index)) {
                    expanded.remove(index);
                } else {
                    expanded.add(index);
                }
                Element detail = document.getElementById("bb-detail-" + index);
                if (detail != null) {
                    detail.getClassList().toggle("bb-detail-open", expanded.contains(index));
                }
            });
        }
        bindAction(document, "bb-start-" + index, task.id(), BuilderTaskActionPacket.ACTION_START);
        bindAction(document, "bb-pause-" + index, task.id(), BuilderTaskActionPacket.ACTION_PAUSE);
        bindAction(document, "bb-stop-" + index, task.id(), BuilderTaskActionPacket.ACTION_CANCEL);
        if (task.kind() == BuilderBoardDataPacket.KIND_BUILDING || task.npcs() == null) {
            return;
        }
        for (int crew = 0; crew < task.npcs().size(); crew++) {
            BuilderBoardDataPacket.NpcRef npc = task.npcs().get(crew);
            Element chip = document.getElementById("bb-crew-" + index + "-" + crew);
            if (chip != null) {
                chip.addEventListener("mousedown", event ->
                        startDrag(event, npc.npcId(), npc.skin(), task.id(), chip));
            }
        }
    }

    private void bindAction(Document document, String id, UUID taskId, int action) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.addEventListener("click", event -> PacketDistributor.sendToServer(
                    new BuilderTaskActionPacket(taskId, action)));
        }
    }

    private void startDrag(Event event, UUID npcId, String skin, UUID fromTask, Element source) {
        if (!(event instanceof MouseEvent mouse)) {
            return;
        }
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        drag = new Drag(npcId, skin, fromTask, source);
        if (source != null) {
            source.getClassList().toggle("bb-dragging", true);
        }
        Position position = document.screenToDocumentPosition(new Position(mouse.clientX, mouse.clientY));
        dragX = position.x;
        dragY = position.y;
        Element ghost = document.getElementById("bb-ghost");
        if (ghost != null) {
            ghost.setInnerHTML("<kingdom-entity-avatar class=\"bb-face\" skin=\""
                    + escape(skin) + "\"></kingdom-entity-avatar>");
            ghost.setAttribute("style", "display:block;position:absolute;left:"
                    + (int) position.x + "px;top:" + (int) position.y + "px");
        }
    }

    private static void click(Document document, String id, Runnable action) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.addEventListener("click", event -> action.run());
        }
    }

    private static void text(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setTextContent(value);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
