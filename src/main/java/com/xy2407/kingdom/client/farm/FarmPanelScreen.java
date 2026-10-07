package com.xy2407.kingdom.client.farm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.element.Item;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.common.farm.FarmRegion;
import com.xy2407.kingdom.common.network.farm.FarmPanelActionPacket;
import com.xy2407.kingdom.common.network.farm.FarmPanelRequestPacket;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.CropOption;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.Plot;
import com.xy2407.kingdom.common.network.farm.FarmPanelStatePacket.WorkerOption;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
    private Element detailSave;
    private Element detailCancel;
    /** 点了卡片上的 ✕、正等确认弹窗答复的那块农田；null 表示没有待确认的删除。 */
    private UUID pendingDeleteId;

    private FarmPanelStatePacket latest = FarmPanelStatePacket.outside();
    private UUID selectedPlotId;
    private int draftSlots;
    private String builtCrops;
    private boolean detailOpen;
    private boolean draftOpen;

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
    }

    private void bind() {
        Document document = getLinkedDocument();
        if (document == null || document == boundDocument) {
            return;
        }
        boundDocument = document;
        builtCrops = null;

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
        detailSave = document.getElementById("fd-save");
        detailCancel = document.getElementById("fd-cancel");

        on("fm-new", this::startNewPlot);
        on("fm-close", this::onClose);
        on("fm-search-clear", () -> setFilter(""));
        on("fd-back", this::closeDetail);
        on("fd-save", this::saveDetail);
        on("fd-cancel", this::cancelDraft);
        on("fd-minus", () -> stepSlots(-1));
        on("fd-plus", () -> stepSlots(1));
        on("fd-warehouse", this::openWarehouse);
        on("fm-confirm-no", this::closeConfirm);
        on("fm-confirm-yes", this::confirmDelete);
        on("fd-clear-workers", this::clearWorkers);
        on("fd-worker-search-clear", () -> setWorkerFilter(""));
        on("fd-draw", () -> {
            if (draftOpen) {
                onClose();
                FarmAreaSelector.beginNew();
                return;
            }
            if (selectedPlotId == null) {
                return;
            }
            UUID plotId = selectedPlotId;
            onClose();
            FarmAreaSelector.begin(plotId);
        });

        showDetail(false);
        apply(latest);
        PacketDistributor.sendToServer(new FarmPanelRequestPacket());
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
        FarmPlotOverlay.cache(packet);
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

        if (draftOpen) {
            FarmPlotDraft draft = FarmPlotDraft.current();
            if (draft == null) {
                closeDetail();
            } else {
                refreshDraft(draft);
                refreshWorkers(packet, draft.workerIds());
            }
            return;
        }
        Plot selected = plotOf(packet, selectedPlotId);
        if (selected == null) {
            if (detailOpen) {
                closeDetail();
            }
        } else {
            draftSlots = selected.slots();
            refreshDetail(packet, selected);
        }
        refreshWorkers(packet, selected == null ? null : selected.workers());
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
        FarmPlotDraft draft = FarmPlotDraft.current();
        boolean showDraft = draft != null && filter.isBlank();
        if (visible.isEmpty() && !showDraft) {
            listHost.setInnerHTML("");
            setVisible(emptyHint, true);
            setText(emptyHint, filter.isBlank() ? "这座建筑还没有划出农田" : "没有匹配的农田");
            return;
        }
        setVisible(emptyHint, false);

        StringBuilder html = new StringBuilder();
        int offset = 0;
        if (showDraft) {
            html.append("<div class=\"").append(ROW).append(" bp-item-draft\" id=\"fm-row-draft\">")
                    .append("<span class=\"bp-name\">新农田（待保存）</span>")
                    .append("<span class=\"bp-sub\">").append(draft.slots()).append(" 格</span>")
                    .append("<span class=\"bp-tag\">").append(draft.cells()).append(" 格地</span>")
                    .append("<span class=\"bp-sub\">").append(draft.workerIds().size()).append(" 人</span>")
                    .append("</div>");
            offset = 1;
        }
        for (int index = 0; index < visible.size(); index++) {
            Plot plot = visible.get(index);
            int row = index + offset;
            html.append("<div class=\"").append(ROW).append("\" id=\"fm-row-").append(row).append("\">")
                    .append("<span class=\"bp-crop\">")
                    .append("<").append(TAG_ITEM).append(" class=\"bp-crop-icon\" id=\"fm-row-icon-")
                    .append(row).append("\"></").append(TAG_ITEM).append("></span>")
                    .append("<span class=\"bp-name\">").append(escape(plot.name())).append("</span>")
                    .append("<span class=\"bp-sub\">").append(plot.slots()).append(" 格</span>")
                    .append("<span class=\"bp-tag\">")
                    .append(plot.hasArea() ? areaText(plot) : "未划地")
                    .append("</span>")
                    .append("<span class=\"bp-sub\">").append(plot.workers().size()).append(" 人</span>")
                    /* 删除按钮贴在卡片最右侧。点它只弹确认、不直接删——
                       整块农田连同格数与打理人安排一起没，手一抖代价太大。 */
                    .append("<span class=\"bp-row-del\" id=\"fm-row-del-").append(row).append("\">✕</span>")
                    .append("</div>");
        }
        listHost.setInnerHTML(html.toString());

        if (showDraft) {
            Element draftRow = boundDocument.getElementById("fm-row-draft");
            if (draftRow != null) {
                draftRow.addEventListener("click", event -> openDraft());
            }
        }
        for (int index = 0; index < visible.size(); index++) {
            Plot plot = visible.get(index);
            int row = index + offset;
            Element element = boundDocument.getElementById("fm-row-" + row);
            if (element != null) {
                element.addEventListener("click", event -> openDetail(plot.id()));
            }
            Element remove = boundDocument.getElementById("fm-row-del-" + row);
            if (remove != null) {
                remove.addEventListener("click", event -> askDelete(plot.id(), plot.name()));
            }
            applyIcon(boundDocument.getElementById("fm-row-icon-" + row), plot.cropId());
        }
    }

    private void refreshCrops(FarmPanelStatePacket packet) {
        if (cropsHost == null) {
            return;
        }
        String key = cropKey(packet);
        if (key.equals(builtCrops)) {
            return;
        }
        builtCrops = key;
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

        for (int index = 0; index < packet.crops().size(); index++) {
            CropOption crop = packet.crops().get(index);
            Element cell = boundDocument.getElementById("fd-crop-" + index);
            if (cell != null) {
                cell.addEventListener("click", event -> selectCrop(crop.id()));
            }
            applyIcon(boundDocument.getElementById("fd-crop-icon-" + index), crop.id());
        }
    }

    /** 作物槽位的重建依据：清单变了（含"先空后到"）就重建，图标与名字一起刷新。 */
    private static String cropKey(FarmPanelStatePacket packet) {
        StringBuilder key = new StringBuilder();
        for (CropOption crop : packet.crops()) {
            key.append(crop.id()).append('|').append(crop.label()).append(';');
        }
        return key.toString();
    }

    private void refreshDetail(FarmPanelStatePacket packet, Plot plot) {
        setText(detailName, plot.name());
        setText(detailSlots, String.valueOf(draftSlots));
        setText(detailSlotNote, draftSlots + " 格");
        setText(detailCap, "上限 " + (packet.remainingSlots() + plot.slots()));
        setText(detailArea, plot.hasArea()
                ? String.format("(%d,%d)-(%d,%d)", plot.minX(), plot.minZ(), plot.maxX(), plot.maxZ())
                        + (plot.extra().isEmpty() ? "" : " ＋" + plot.extra().size() + " 块")
                : "尚未划定");
        setText(detailWorkerNote, plot.workers().size() + " 人");
        setText(detailCropNote, labelOf(packet, plot.cropId()));
        markSelectedCrop(plot.cropId());
        showDetailButtons(false);
    }

    /** 待定农田的详情：数据全在客户端草稿里，点作物与打理人只改草稿，不发包。 */
    private void refreshDraft(FarmPlotDraft draft) {
        setText(detailName, "新农田（待保存）");
        setText(detailSlots, String.valueOf(draft.slots()));
        setText(detailSlotNote, draft.slots() + " 格 / 共 " + draft.cells() + " 格地");
        setText(detailCap, "上限 " + latest.remainingSlots());
        setText(detailArea, String.format("(%d,%d)-(%d,%d)", draft.minX(), draft.minZ(), draft.maxX(), draft.maxZ())
                + (draft.extra().isEmpty() ? "" : " ＋" + draft.extra().size() + " 块"));
        setText(detailWorkerNote, draft.workerIds().size() + " 人");
        setText(detailCropNote, labelOf(latest, draft.cropId()));
        markSelectedCrop(draft.cropId());
        showDetailButtons(true);
    }

    private void markSelectedCrop(String cropId) {
        if (cropsHost == null) {
            return;
        }
        for (int index = 0; index < latest.crops().size(); index++) {
            Element cell = boundDocument.getElementById("fd-crop-" + index);
            if (cell != null) {
                cell.getClassList().toggle(ON, latest.crops().get(index).id().equals(cropId));
            }
        }
    }

    /**
     * 底栏常驻「打开仓库」；只有**待定农田**（还没落库）才额外多出「保存 / 取消」。
     *
     * <p>已建农田不再有「完成」：格数、作物、打理人本来就是改动即发到服务端，
     * 那个按钮当年其实只是"关闭详情页"，留着反而让人以为不点就不保存。</p>
     */
    private void showDetailButtons(boolean draft) {
        setText(detailSave, "保存");
        setVisible(detailSave, draft);
        setVisible(detailCancel, draft);
    }

    private void refreshWorkers(FarmPanelStatePacket packet, List<UUID> assigned) {
        if (workersHost == null) {
            return;
        }
        List<UUID> hired = assigned == null ? List.of() : assigned;
        List<UUID> ids = new ArrayList<>();
        StringBuilder html = new StringBuilder();
        for (WorkerOption worker : packet.workers()) {
            if (!workerFilter.isBlank() && !worker.name().contains(workerFilter)) {
                continue;
            }
            boolean isHired = hired.contains(worker.id());
            int index = ids.size();
            ids.add(worker.id());
            String state = worker.note().isBlank() ? (isHired ? "打理中" : "空闲") : worker.note();
            html.append("<div class=\"").append(ROW).append(isHired ? " " + ON : "")
                    .append("\" id=\"fd-worker-").append(index).append("\">")
                    .append("<kingdom-entity-avatar class=\"bp-worker-face\" skin=\"")
                    .append(escape(worker.skin())).append("\"></kingdom-entity-avatar>")
                    .append("<span class=\"bp-name\">").append(escape(worker.name())).append("</span>")
                    .append("<span class=\"bp-sub\">").append(escape(state)).append("</span>")
                    .append("</div>");
        }
        if (ids.isEmpty()) {
            html.append("<div class=\"bp-empty\">").append(packet.workers().isEmpty()
                    ? "这份名册里还没有市民"
                    : "没有匹配「" + escape(workerFilter) + "」的市民").append("</div>");
        }
        workersHost.setInnerHTML(html.toString());

        for (int index = 0; index < ids.size(); index++) {
            UUID workerId = ids.get(index);
            Element row = boundDocument.getElementById("fd-worker-" + index);
            if (row != null) {
                row.addEventListener("click", event -> toggleWorker(workerId));
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
        int cells = 0;
        if (plot.maxX() > plot.minX() || plot.maxZ() > plot.minZ()) {
            cells = (plot.maxX() - plot.minX() + 1) * (plot.maxZ() - plot.minZ() + 1);
        }
        for (FarmRegion region : plot.extra()) {
            cells += region.cells();
        }
        return plot.extra().isEmpty() ? cells + " 格地" : cells + " 格地 · " + plot.extra().size() + " 块附加";
    }

    /** 新建农田：先关面板去框地，框完的地变成待定卡片，作物与打理人在卡片里选。 */
    private void startNewPlot() {
        onClose();
        FarmAreaSelector.beginNew();
    }

    private void openDetail(UUID plotId) {
        selectedPlotId = plotId;
        draftOpen = false;
        Plot plot = plotOf(latest, plotId);
        draftSlots = plot == null ? 0 : plot.slots();
        showDetail(true);
        apply(latest);
    }

    private void openDraft() {
        FarmPlotDraft draft = FarmPlotDraft.current();
        if (draft == null) {
            return;
        }
        selectedPlotId = null;
        draftOpen = true;
        draftSlots = draft.slots();
        showDetail(true);
        refreshDraft(draft);
        refreshWorkers(latest, draft.workerIds());
    }

    private void closeDetail() {
        selectedPlotId = null;
        draftOpen = false;
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
        if (!open) {
            showDetailButtons(false);
        }
    }

    private static void setOff(Element element, String token, boolean off) {
        if (element != null) {
            element.getClassList().toggle(token, off);
        }
    }

    private void stepSlots(int delta) {
        if (draftOpen) {
            FarmPlotDraft draft = FarmPlotDraft.current();
            if (draft == null) {
                return;
            }
            FarmPlotDraft next = draft.withSlots(
                    Math.max(0, Math.min(latest.remainingSlots(), draft.slots() + delta)));
            FarmPlotDraft.set(next);
            refreshDraft(next);
            refreshList(latest);
            return;
        }
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
        if (draftOpen) {
            FarmPlotDraft draft = FarmPlotDraft.current();
            if (draft == null) {
                return;
            }
            FarmPlotDraft next = draft.withCrop(cropId.equals(draft.cropId()) ? "" : cropId);
            FarmPlotDraft.set(next);
            refreshDraft(next);
            refreshList(latest);
            return;
        }
        if (selectedPlotId == null) {
            return;
        }
        Plot plot = plotOf(latest, selectedPlotId);
        if (plot == null) {
            return;
        }
        send(FarmPanelActionPacket.setCrop(selectedPlotId, cropId.equals(plot.cropId()) ? "" : cropId));
    }

    private void toggleWorker(UUID workerId) {
        if (draftOpen) {
            FarmPlotDraft draft = FarmPlotDraft.current();
            if (draft == null) {
                return;
            }
            FarmPlotDraft next = draft.toggleWorker(workerId);
            FarmPlotDraft.set(next);
            refreshDraft(next);
            refreshWorkers(latest, next.workerIds());
            refreshList(latest);
            return;
        }
        if (selectedPlotId != null) {
            send(FarmPanelActionPacket.toggleWorker(selectedPlotId, workerId));
        }
    }

    private void clearWorkers() {
        if (draftOpen) {
            FarmPlotDraft draft = FarmPlotDraft.current();
            if (draft == null) {
                return;
            }
            FarmPlotDraft next = draft.withWorkers(List.of());
            FarmPlotDraft.set(next);
            refreshDraft(next);
            refreshWorkers(latest, next.workerIds());
            refreshList(latest);
            return;
        }
        if (selectedPlotId != null) {
            send(FarmPanelActionPacket.toggleWorker(selectedPlotId, null));
        }
    }

    /** 待定卡片的保存：区域、格数、作物、打理人一次成型发给服务端。 */
    private void saveDetail() {
        if (!draftOpen) {
            closeDetail();
            return;
        }
        FarmPlotDraft draft = FarmPlotDraft.current();
        if (draft != null) {
            send(FarmPanelActionPacket.create(draft.slots(), draft.cropId(), draft.workerCsv(),
                    draft.minX(), draft.minZ(), draft.maxX(), draft.maxZ(), draft.extra()));
        }
        FarmPlotDraft.clear();
        closeDetail();
        refreshList(latest);
        hint(draft == null ? "农田已保存" : saveHint(draft));
    }

    /** 保存后的提示直说闸门：市民不会开工的原因就这三个，别让人对着不动的市民猜。 */
    private static String saveHint(FarmPlotDraft draft) {
        if (draft.cropId().isBlank()) {
            return "农田已保存，但还没选作物：市民不会开工";
        }
        if (draft.slots() <= 0) {
            return "农田已保存，但分配格数是 0：市民不会种任何一格";
        }
        if (draft.workerIds().isEmpty()) {
            return "农田已保存，但还没安排打理人：没人会来种";
        }
        return "农田已保存";
    }

    private void cancelDraft() {
        if (!draftOpen) {
            closeDetail();
            return;
        }
        FarmPlotDraft.clear();
        closeDetail();
        refreshList(latest);
        hint("已放弃这块待定农田");
    }

    /** 点卡片最右侧的 ✕：先把要删的那块记下来并弹确认，不立刻删。 */
    private void askDelete(UUID plotId, String name) {
        if (boundDocument == null || plotId == null) {
            return;
        }
        pendingDeleteId = plotId;
        setText(boundDocument.getElementById("fm-confirm-text"),
                "确定删除「" + (name == null || name.isBlank() ? "这块农田" : name)
                        + "」？它的格数与打理人安排会一并清掉。");
        Element modal = boundDocument.getElementById("fm-confirm");
        if (modal != null) {
            modal.getClassList().toggle(ON, true);
        }
    }

    private void closeConfirm() {
        pendingDeleteId = null;
        if (boundDocument == null) {
            return;
        }
        Element modal = boundDocument.getElementById("fm-confirm");
        if (modal != null) {
            modal.getClassList().toggle(ON, false);
        }
    }

    /** 确认删除。删的是**卡片上那一块**，不一定是详情页里选中的那块。 */
    private void confirmDelete() {
        UUID plotId = pendingDeleteId;
        closeConfirm();
        if (plotId == null) {
            return;
        }
        send(FarmPanelActionPacket.delete(plotId));
        if (plotId.equals(selectedPlotId)) {
            closeDetail();
        }
    }

    /** 底栏的「打开仓库」：界面由服务端换（建筑按玩家位置判定，不采信客户端）。 */
    private void openWarehouse() {
        send(FarmPanelActionPacket.openStorage());
    }

    private void setFilter(String value) {
        filter = value == null ? "" : value;
        shownFilter = filter;
        refreshList(latest);
    }

    private void setWorkerFilter(String value) {
        workerFilter = value == null ? "" : value;
        shownWorkerFilter = workerFilter;
        refreshWorkers(latest, assignedWorkers());
    }

    /** 详情页正在编辑谁的打理人：待定草稿，或已建的某块农田。 */
    private List<UUID> assignedWorkers() {
        if (draftOpen) {
            FarmPlotDraft draft = FarmPlotDraft.current();
            return draft == null ? null : draft.workerIds();
        }
        Plot plot = plotOf(latest, selectedPlotId);
        return plot == null ? null : plot.workers();
    }

    /** 操作反馈走动作栏，不弹聊天框。 */
    private static void hint(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
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
