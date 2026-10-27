package com.xy2407.kingdom.client.building;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.xy2407.kingdom.common.block.MapStandBlock;
import com.xy2407.kingdom.common.network.building.MapStandDataPacket;
import com.xy2407.kingdom.common.network.employment.EmploymentOpenRequestPacket;
import com.xy2407.kingdom.common.network.building.MapStandTogglePacket;
import com.xy2407.kingdom.common.network.clientbound.MapStandClientBridge;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 仓库管辖地图的页面驱动：把服务端下发的建筑清单画成范围与中心点，并处理点选与提交。 */
@OnlyIn(Dist.CLIENT)
public final class MapStandScreenDriver {

    private static final String TITLE_ID = "msTitle";
    private static final String HIRE_ID = "msHire";
    private static final String STAFF_ID = "msStaff";
    private static final String MAP_ID = "msMap";
    private static final String ACT_ID = "msAct";

    private static final int MAP_WIDTH = 600;
    private static final int MAP_HEIGHT = 380;

    private static final Map<UUID, Boolean> PENDING = new LinkedHashMap<>();
    private static MapStandDataPacket current;
    private static boolean hireBound;
    private static boolean actBound;

    private MapStandScreenDriver() {
    }

    public static void install() {
        MapStandClientBridge.setSink(MapStandScreenDriver::accept);
    }

    private static void accept(MapStandDataPacket packet) {
        current = packet;
        PENDING.clear();
        Document document = ensureDocument();
        if (document == null) {
            return;
        }
        render(document, packet);
    }

    private static Document ensureDocument() {
        List<Document> documents = ApricityUI.getDocument(MapStandBlock.PAGE);
        if (!documents.isEmpty()) {
            return documents.get(0);
        }
        try {
            return ApricityUI.createDocument(MapStandBlock.PAGE);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static void render(Document document, MapStandDataPacket packet) {
        text(document, TITLE_ID, packet.warehouseName());
        renderStaff(document, packet);
        renderMap(document, packet);
        renderAction(document);
        if (!hireBound) {
            Element hire = document.getElementById(HIRE_ID);
            if (hire != null) {
                hireBound = true;
                hire.addEventListener("click", event -> openEmployment());
            }
        }
    }

    private static void renderStaff(Document document, MapStandDataPacket packet) {
        Element host = document.getElementById(STAFF_ID);
        if (host == null) {
            return;
        }
        StringBuilder html = new StringBuilder();
        for (MapStandDataPacket.Staff staff : packet.staff()) {
            html.append("<div class=\"ms-card\"><div class=\"n\">").append(escape(staff.name()))
                    .append("</div><div class=\"j\">").append(escape(staff.job())).append("</div></div>");
        }
        if (packet.staff().isEmpty()) {
            html.append("<div class=\"ms-card\"><div class=\"j\">暂无员工</div></div>");
        }
        host.setInnerHTML(html.toString());
    }

    private static void renderMap(Document document, MapStandDataPacket packet) {
        Element map = document.getElementById(MAP_ID);
        if (map == null) {
            return;
        }
        map.setAttribute("style", "width:" + MAP_WIDTH + "px;height:" + MAP_HEIGHT + "px;");
        int spanX = Math.max(1, packet.maxX() - packet.minX() + 1);
        int spanZ = Math.max(1, packet.maxZ() - packet.minZ() + 1);
        double scale = Math.min((double) MAP_WIDTH / spanX, (double) MAP_HEIGHT / spanZ);
        MapStandDataPacket.Building hub = null;
        for (MapStandDataPacket.Building building : packet.buildings()) {
            if (building.warehouse()) {
                hub = building;
                break;
            }
        }
        int hubX = hub == null ? 0 : (int) Math.round((hub.coreX() - packet.minX()) * scale);
        int hubY = hub == null ? 0 : (int) Math.round((hub.coreZ() - packet.minZ()) * scale);
        StringBuilder links = new StringBuilder();
        StringBuilder ranges = new StringBuilder();
        StringBuilder cores = new StringBuilder();
        for (int index = 0; index < packet.buildings().size(); index++) {
            MapStandDataPacket.Building building = packet.buildings().get(index);
            int left = (int) Math.round((building.minX() - packet.minX()) * scale);
            int top = (int) Math.round((building.minZ() - packet.minZ()) * scale);
            int width = Math.max(6, (int) Math.round((building.maxX() - building.minX() + 1) * scale));
            int height = Math.max(6, (int) Math.round((building.maxZ() - building.minZ() + 1) * scale));
            boolean picked = PENDING.containsKey(building.id());
            String rangeClass = "ms-range" + (building.warehouse() ? " warehouse" : "")
                    + (picked ? " picked" : "");
            ranges.append("<div class=\"").append(rangeClass).append("\" style=\"left:").append(left)
                    .append("px;top:").append(top).append("px;width:").append(width)
                    .append("px;height:").append(height).append("px\"></div>");
            int coreX = (int) Math.round((building.coreX() - packet.minX()) * scale) - 4;
            int coreY = (int) Math.round((building.coreZ() - packet.minZ()) * scale) - 4;
            String coreClass = building.warehouse()
                    ? "ms-core locked"
                    : (picked ? "ms-core picked" : "ms-core");
            cores.append("<div class=\"").append(coreClass)
                    .append("\" id=\"mscore").append(index)
                    .append("\" style=\"left:").append(coreX).append("px;top:").append(coreY)
                    .append("px\"></div>");
            boolean linked = hub != null && !building.warehouse()
                    && (building.governed() || Boolean.TRUE.equals(PENDING.get(building.id())));
            if (linked) {
                appendLink(links, hubX, hubY, coreX + 4, coreY + 4);
            }
        }
        map.setInnerHTML(links.append(ranges).append(cores).toString());
        for (int index = 0; index < packet.buildings().size(); index++) {
            MapStandDataPacket.Building building = packet.buildings().get(index);
            if (building.warehouse()) {
                continue;
            }
            Element core = document.getElementById("mscore" + index);
            if (core != null) {
                core.addEventListener("click", event -> togglePick(building));
            }
        }
    }

    private static void appendLink(StringBuilder html, int x1, int y1, int x2, int y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 4.0D) {
            return;
        }
        int steps = (int) Math.max(1, length / 12.0D);
        for (int step = 0; step <= steps; step++) {
            int px = (int) Math.round(x1 + dx * step / steps) - 1;
            int py = (int) Math.round(y1 + dy * step / steps) - 1;
            boolean tip = step == 0 || step == steps;
            html.append("<div class=\"ms-link").append(tip ? " tip" : "")
                    .append("\" style=\"left:").append(px).append("px;top:").append(py)
                    .append("px\"></div>");
        }
    }

    private static void togglePick(MapStandDataPacket.Building building) {
        if (building.warehouse()) {
            return;
        }
        boolean desired = !building.governed();
        Boolean existing = PENDING.get(building.id());
        if (existing != null && existing == desired) {
            PENDING.remove(building.id());
        } else {
            PENDING.put(building.id(), desired);
        }
        Document document = ensureDocument();
        if (document != null && current != null) {
            renderMap(document, current);
            renderAction(document);
        }
    }

    private static void renderAction(Document document) {
        Element act = document.getElementById(ACT_ID);
        if (act == null) {
            return;
        }
        boolean enabled = !PENDING.isEmpty();
        boolean govern = !PENDING.isEmpty() && PENDING.values().iterator().next();
        act.setTextContent(govern ? "加入管辖范围" : "删除管辖范围");
        act.setAttribute("class", enabled ? "ms-act" : "ms-act disabled");
        if (!actBound) {
            actBound = true;
            act.addEventListener("click", event -> submit());
        }
    }

    private static void submit() {
        if (PENDING.isEmpty() || current == null) {
            return;
        }
        List<UUID> govern = new ArrayList<>();
        List<UUID> revoke = new ArrayList<>();
        for (Map.Entry<UUID, Boolean> entry : PENDING.entrySet()) {
            (entry.getValue() ? govern : revoke).add(entry.getKey());
        }
        if (!govern.isEmpty()) {
            PacketDistributor.sendToServer(new MapStandTogglePacket(current.standPos(), govern, true));
        }
        if (!revoke.isEmpty()) {
            PacketDistributor.sendToServer(new MapStandTogglePacket(current.standPos(), revoke, false));
        }
        PENDING.clear();
    }

    private static void openEmployment() {
        if (current == null) {
            return;
        }
        PacketDistributor.sendToServer(new EmploymentOpenRequestPacket(
                current.warehouseId().toString(), "", 0));
    }

    private static void text(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setTextContent(value == null ? "" : value);
        }
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
