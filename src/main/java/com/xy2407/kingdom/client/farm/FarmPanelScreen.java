package com.xy2407.kingdom.client.farm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.element.Item;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.common.network.farm.FarmPanelActionPacket;
import com.xy2407.kingdom.common.network.farm.FarmPanelRequestPacket;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.CropOption;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.Plot;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.WorkerOption;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 农业操作界面的驱动：两级页显隐、农田列表与作物槽位填充、操作发包
 */
@OnlyIn(Dist.CLIENT)
public final class FarmPanelScreen extends ApricityScreen {

    public static final String PAGE = "buildings/farm.html";

    private static final String OFF_HEAD = "bp-head-off";
    private static final String OFF_BODY = "bp-body-off";
    private static final String OFF_FOOT = "bp-foot-off";
    private static final String ON = "on";
    private static final String ROW = "bp-item";
    private static final String CROP_ROW = "bp-crop-row";
    private static final String CROP_CELL = "bp-crop-cell";
    private static final String SLOT = "bp-slot";
    private static final String TAG_ITEM = "item";

    private static final int CROPS_PER_ROW = 4;
    private static final int SLOT_BLOCKS = 12;

    private Document boundDocument;
    private Element mainHead;
    private Element mainBody;
    private Element mainFoot;
    private Element detailHead;
    private Element detailBody;
    private Element detailFoot;
    private Element title;
    private Element quotaText;
    private Element position;
    private Element blueprint;
    private Element totalText;
    private Element slotsHost;
    private Element listHost;
    private Element emptyHint;
    private Element countText;
    private Element searchInput;
    private Element detailName;
    private Element detailSlots;
    private Element detailSlotNote;
    private Element detailCap;
    private Element detailArea;
    private Element detailWorkerNote;
    private Element detailCropNote;
    private Element cropsHost;
    private Element workersHost;
    private Element workerSearchInput;

    private FarmPanelStatePacket latest = FarmPanelStatePacket.outside();
    private UUID selectedPlotId;
    private int draftSlots;
    private boolean cropsBuilt;
    private boolean detailOpen;

    private String filter = "";
    private String workerFilter = "";
    private String shownFilter;
    private String shownWorkerFilter;

    public FarmPanelScreen() {
        super(PAGE);
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    @Override
    protected void init() {
        super.init();
        bind();
        PacketDistributor.sendToServer(new FarmPanelRequestPacket());
    }

    private void bind() {
        Document document = getLinkedDocument();
        if (document == null || document == boundDocument) {
            return;
        }
        boundDocument = document;
        cropsBuilt = false;

        mainHead = document.getElementById("fm-head");
        mainBody = document.getElementById("fm-body");
        mainFoot = document.getElementById("fm-foot");
        detailHead = document.getElementById("fd-head");
        detailBody = document.getElementById("fd-body");
        detailFoot = document.getElementById("fd-foot");
        title = document.getElementById("fm-title");
        quotaText = document.getElementById("fm-quota");
        position = document.getElementById("fm-pos");
        blueprint = document.getElementById("fm-blueprint");
        totalText = document.getElementById("fm-total");
        slotsHost = document.getElementById("fm-slots");
        listHost = document.getElementById("fm-list");
        emptyHint = document.getElementById("fm-empty");
        countText = document.getElementById("fm-count");
        searchInput = document.getElementById("fm-search");
        detailName = document.getElementById("fd-name");
        detailSlots = document.getElementById("fd-slots");
        detailSlotNote = document.getElementById("fd-slots-note");
        detailCap = document.getElementById("fd-cap");
        detailArea = document.getElementById("fd-area");
        detailWorkerNote = document.getElementById("fd-workers-note");
        detailCropNote = document.getElementById("fd-crop-note");
        cropsHost = document.getElementById("fd-crops");
        workersHost = document.getElementById("fd-workers");
        workerSearchInput = document.getElementById("fd-worker-search");

        on("fm-new", this::createPlot);
        on("fm-close", this::onClose);
        on("fm-search-clear", () -> setFilter(""));
        on("fd-back", this::closeDetail);
        on("fd-save", this::closeDetail);
        on("fd-minus", () -> stepSlots(-1));
        on("fd-plus", () -> stepSlots(1));
        on("fd-delete", this::deletePlot);
        on("fd-clear-workers", () -> send(FarmPanelActionPacket.toggleWorker(selectedPlotId, null)));
        on("fd-worker-search-clear", () -> setWorkerFilter(""));
        on("fd-draw", () -> {
            if (selectedPlotId == null) {
                return;
            }
            UUID plotId = selectedPlotId;
            onClose();
            FarmAreaSelector.begin(plotId);
        });

        showDetail(false);
        apply(latest);
    }

    private void on(String elementId, Runnable action) {
        if (boundDocument == null) {
            return;
        }
        Element element = boundDocument.getElementById(elementId);
        if (element != null) {
            element.addEventListener("click", event -> action.run());
        }
    }

    public static void accept(FarmPanelStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof FarmPanelScreen screen) {
            screen.apply(packet);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (searchInput instanceof Input input && !input.getValue().equals(shownFilter)) {
            shownFilter = input.getValue();
            setFilter(shownFilter);
        }
        if (workerSearchInput instanceof Input input && !input.getValue().equals(shownWorkerFilter)) {
            shownWorkerFilter = input.getValue();
            setWorkerFilter(shownWorkerFilter);
        }
    }

    private void apply(FarmPanelStatePacket packet) {
        if (packet == null) {
            return;
        }
        latest = packet;
        setText(title, packet.buildingName().isBlank() ? "农业" : packet.buildingName());
        setText(quotaText, packet.usedSlots() + " / " + packet.totalSlots());
        setText(totalText, String.valueOf(packet.totalSlots()));
        setText(blueprint, packet.buildingName());
        setText(countText, packet.plots().size() + " 块");
        refreshQuota(packet);
        refreshList(packet);
        refreshCrops(packet);

        Plot selected = plotOf(packet, selectedPlotId);
        if (selected == null) {
            if (detailOpen) {
                closeDetail();
            }
        } else {
            draftSlots = selected.slots();
            refreshDetail(packet, selected);
        }
        refreshWorkers(packet, selected);
    }

    private void refreshQuota(FarmPanelStatePacket packet) {
        if (slotsHost == null) {
            return;
        }
        StringBuilder html = new StringBuilder();
        int used = packet.usedSlots();
        int total = packet.totalSlots();
        if (total > 0 && total <= SLOT_BLOCKS) {
            for (int index = 0; index < total; index++) {
                html.append("<span class=\"").append(SLOT).append(index < used ? " " + ON : "").append("\"></span>");
            }
        }
        html.append("<span class=\"bp-quota-note\">已分配 ").append(used)
                .append(" 格，剩余 ").append(packet.remainingSlots()).append(" 格</span>");
        slotsHost.setInnerHTML(html.toString());
    }

    private void refreshList(FarmPanelStatePacket packet) {
        if (listHost == null) {
            return;
        }
        List<Plot> visible = new ArrayList<>();
        for (Plot plot : packet.plots()) {
            if (filter.isBlank() || plot.name().contains(filter)) {
                visible.add(plot);
            }
        }
        if (visible.isEmpty()) {
            listHost.setInnerHTML("");
            setVisible(emptyHint, true);
            setText(emptyHint, filter.isBlank() ? "这座建筑还没有划出农田" : "没有匹配的农田");
            return;
        }
        setVisible(emptyHint, false);

        StringBuilder html = new StringBuilder();
        for (int index = 0; index < visible.size(); index++) {
            Plot plot = visible.get(index);
            html.append("<div class=\"").append(ROW).append("\" id=\"fm-row-").append(index).append("\">")
                    .append("<span class=\"bp-crop\">")
                    .append("<").append(TAG_ITEM).append(" class=\"bp-crop-icon\" id=\"fm-row-icon-")
                    .append(index).append("\"></").append(TAG_ITEM).append("></span>")
                    .append("<span class=\"bp-name\">").append(escape(plot.name())).append("</span>")
                    .append("<span class=\"bp-sub\">").append(plot.slots()).append(" 格</span>")
                    .append("<span class=\"bp-tag\">")
                    .append(plot.hasArea() ? areaText(plot) : "未划地")
                    .append("</span>")
                    .append("<span class=\"bp-sub\">").append(plot.workers().size()).append(" 人</span>")
                    .append("</div>");
        }
        listHost.setInnerHTML(html.toString());

        for (int index = 0; index < visible.size(); index++) {
            Plot plot = visible.get(index);
            Element row = boundDocument.getElementById("fm-row-" + index);
            if (row != null) {
                row.addEventListener("click", event -> openDetail(plot.id()));
            }
            applyIcon(boundDocument.getElementById("fm-row-icon-" + index), plot.cropId());
        }
    }

    private void refreshCrops(FarmPanelStatePacket packet) {
        if (cropsHost == null || cropsBuilt) {
            return;
        }
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < packet.crops().size(); index++) {
            if (index % CROPS_PER_ROW == 0) {
                if (index > 0) {
                    html.append("</div>");
                }
                html.append("<div class=\"").append(CROP_ROW).append("\">");
            }
            CropOption crop = packet.crops().get(index);
            html.append("<span class=\"").append(CROP_CELL).append("\" id=\"fd-crop-").append(index).append("\">")
                    .append("<").append(TAG_ITEM).append(" class=\"bp-crop-cell-icon\" id=\"fd-crop-icon-")
                    .append(index).append("\"></").append(TAG_ITEM).append(">")
                    .append("<span class=\"bp-crop-cell-name\">").append(escape(crop.label())).append("</span>")
                    .append("</span>");
        }
        if (!packet.crops().isEmpty()) {
            html.append("</div>");
        }
        cropsHost.setInnerHTML(html.toString());
        cropsBuilt = true;

        for (int index = 0; index < packet.crops().size(); index++) {
            CropOption crop = packet.crops().get(index);
            Element cell = boundDocument.getElementById("fd-crop-" + index);
            if (cell != null) {
                cell.addEventListener("click", event -> selectCrop(crop.id()));
            }
            applyIcon(boundDocument.getElementById("fd-crop-icon-" + index), crop.id());
        }
    }

    private void refreshDetail(FarmPanelStatePacket packet, Plot plot) {
        setText(detailName, plot.name());
        setText(detailSlots, String.valueOf(draftSlots));
        setText(detailSlotNote, draftSlots + " 格");
        setText(detailCap, "上限 " + (packet.remainingSlots() + plot.slots()));
        setText(detailArea, plot.hasArea()
                ? String.format("(%d,%d)-(%d,%d)", plot.minX(), plot.minZ(), plot.maxX(), plot.maxZ())
                : "尚未划定");
        setText(detailWorkerNote, plot.workers().size() + " 人");
        setText(detailCropNote, labelOf(packet, plot.cropId()));
        if (cropsHost != null) {
            for (int index = 0; index < packet.crops().size(); index++) {
                Element cell = boundDocument.getElementById("fd-crop-" + index);
                if (cell != null) {
                    cell.getClassList().toggle(ON, packet.crops().get(index).id().equals(plot.cropId()));
                }
            }
        }
    }

    private void refreshWorkers(FarmPanelStatePacket packet, Plot selected) {
        if (workersHost == null) {
            return;
        }
        if (selected == null) {
            workersHost.setInnerHTML("");
            return;
        }
        List<UUID> ids = new ArrayList<>();
        StringBuilder html = new StringBuilder();
        for (WorkerOption worker : packet.workers()) {
            if (!workerFilter.isBlank() && !worker.name().contains(workerFilter)) {
                continue;
            }
            boolean assigned = selected.workers().contains(worker.id());
            int index = ids.size();
            ids.add(worker.id());
            html.append("<div class=\"").append(ROW).append(assigned ? " " + ON : "")
                    .append("\" id=\"fd-worker-").append(index).append("\">")
                    .append("<kingdom-entity-avatar class=\"bp-worker-face\" skin=\"")
                    .append(escape(worker.skin())).append("\"></kingdom-entity-avatar>")
                    .append("<span class=\"bp-name\">").append(escape(worker.name())).append("</span>")
                    .append("<span class=\"bp-sub\">").append(assigned ? "打理中" : "空闲").append("</span>")
                    .append("</div>");
        }
        workersHost.setInnerHTML(html.toString());

        for (int index = 0; index < ids.size(); index++) {
            UUID workerId = ids.get(index);
            Element row = boundDocument.getElementById("fd-worker-" + index);
            if (row != null) {
                row.addEventListener("click", event ->
                        send(FarmPanelActionPacket.toggleWorker(selectedPlotId, workerId)));
            }
        }
    }

    private void applyIcon(Element element, String itemId) {
        if (!(element instanceof Item item)) {
            return;
        }
        ItemStack stack = stackOf(itemId);
        item.setIngredientStack(stack == null ? ItemStack.EMPTY : stack);
    }

    private static ItemStack stackOf(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) {
            return null;
        }
        var holder = BuiltInRegistries.ITEM.get(key);
        if (holder == null || holder == net.minecraft.world.item.Items.AIR) {
            return null;
        }
        return new ItemStack(holder);
    }

    private static String labelOf(FarmPanelStatePacket packet, String cropId) {
        if (cropId == null || cropId.isBlank()) {
            return "未选择";
        }
        for (CropOption crop : packet.crops()) {
            if (crop.id().equals(cropId)) {
                return crop.label();
            }
        }
        return cropId;
    }

    private static String areaText(Plot plot) {
        return (plot.maxX() - plot.minX() + 1) * (plot.maxZ() - plot.minZ() + 1) + " 格地";
    }

    private void createPlot() {
        send(FarmPanelActionPacket.create("", 1));
    }

    private void openDetail(UUID plotId) {
        selectedPlotId = plotId;
        Plot plot = plotOf(latest, plotId);
        draftSlots = plot == null ? 0 : plot.slots();
        showDetail(true);
        apply(latest);
    }

    private void closeDetail() {
        selectedPlotId = null;
        showDetail(false);
    }

    private void showDetail(boolean open) {
        detailOpen = open;
        setOff(mainHead, OFF_HEAD, open);
        setOff(mainBody, OFF_BODY, open);
        setOff(mainFoot, OFF_FOOT, open);
        setOff(detailHead, OFF_HEAD, !open);
        setOff(detailBody, OFF_BODY, !open);
        setOff(detailFoot, OFF_FOOT, !open);
    }

    private static void setOff(Element element, String token, boolean off) {
        if (element != null) {
            element.getClassList().toggle(token, off);
        }
    }

    private void stepSlots(int delta) {
        if (selectedPlotId == null) {
            return;
        }
        Plot plot = plotOf(latest, selectedPlotId);
        if (plot == null) {
            return;
        }
        int cap = latest.remainingSlots() + plot.slots();
        int next = Math.max(0, Math.min(cap, draftSlots + delta));
        draftSlots = next;
        refreshDetail(latest, plot);
        if (next != plot.slots()) {
            send(FarmPanelActionPacket.setSlots(selectedPlotId, next));
        }
    }

    private void selectCrop(String cropId) {
        if (selectedPlotId == null) {
            return;
        }
        Plot plot = plotOf(latest, selectedPlotId);
        if (plot == null) {
            return;
        }
        send(FarmPanelActionPacket.setCrop(selectedPlotId, cropId.equals(plot.cropId()) ? "" : cropId));
    }

    private void deletePlot() {
        if (selectedPlotId == null) {
            return;
        }
        send(FarmPanelActionPacket.delete(selectedPlotId));
        closeDetail();
    }

    private void setFilter(String value) {
        filter = value == null ? "" : value;
        shownFilter = filter;
        refreshList(latest);
    }

    private void setWorkerFilter(String value) {
        workerFilter = value == null ? "" : value;
        shownWorkerFilter = workerFilter;
        refreshWorkers(latest, plotOf(latest, selectedPlotId));
    }

    private static void send(FarmPanelActionPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    private static Plot plotOf(FarmPanelStatePacket packet, UUID plotId) {
        if (packet == null || plotId == null) {
            return null;
        }
        for (Plot plot : packet.plots()) {
            if (plot.id().equals(plotId)) {
                return plot;
            }
        }
        return null;
    }

    private static void setText(Element element, String text) {
        if (element != null) {
            element.setTextContent(text == null ? "" : text);
        }
    }

    private static void setVisible(Element element, boolean visible) {
        if (element != null) {
            element.setAttribute("style", visible ? "display:flex" : "display:none");
        }
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
