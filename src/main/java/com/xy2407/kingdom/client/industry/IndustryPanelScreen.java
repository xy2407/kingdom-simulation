package com.xy2407.kingdom.client.industry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.element.Item;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.client.aui.AuiLazyList;
import com.xy2407.kingdom.client.mining.MiningBrushSelector;
import com.xy2407.kingdom.client.mining.MiningPlotOverlay;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.common.network.building.BuildingStorageRequestPacket;
import com.xy2407.kingdom.common.network.industry.IndustryPanelActionPacket;
import com.xy2407.kingdom.common.network.industry.IndustryPanelRequestPacket;
import com.xy2407.kingdom.common.network.industry.IndustryPanelStatePacket;
import com.xy2407.kingdom.common.network.industry.IndustryPanelStatePacket.Recipe;
import com.xy2407.kingdom.common.network.industry.IndustryPanelStatePacket.Worker;
import com.xy2407.kingdom.common.network.mining.MiningPanelActionPacket;
import com.xy2407.kingdom.common.network.mining.MiningPanelRequestPacket;
import com.xy2407.kingdom.common.network.mining.MiningPanelStatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工业操作界面的驱动：一级页铺配方卡片，二级页看详情并派工
 */
@OnlyIn(Dist.CLIENT)
public final class IndustryPanelScreen extends ApricityScreen {

    public static final String PAGE = "buildings/industry.html";

    private static final String ON = "on";
    private static final String ROW = "bp-item";
    private static final String TAG_ITEM = "item";
    private static final int REFRESH_INTERVAL = 40;

    private Document boundDocument;
    private Element titleText;
    private Element levelText;
    private Element position;
    private Element blueprint;
    private Element workerText;
    private Element countText;
    private Element listHost;
    private Element emptyHint;
    private Element searchInput;
    private Element mainHead;
    private Element mainBody;
    private Element mainFoot;
    private Element detailHead;
    private Element detailBody;
    private Element detailFoot;
    private Element detailName;
    private Element detailRecipe;
    private Element detailTicks;
    private Element detailInputs;
    private Element detailOutputs;
    private Element detailStatus;
    private Element workersHost;
    private Element workersNote;
    private Element workersEmpty;
    private Element workerSearchInput;

    private Element mineListHost;
    private Element mineCountText;
    private Element mineEmptyHint;
    private Element mineHead;
    private Element mineBody;
    private Element mineFoot;
    private Element mineNameText;
    private Element mineAreaText;
    private Element mineLayerText;
    private Element mineProgressText;
    private Element mineStatusText;
    private Element mineWorkersHost;
    private Element mineWorkersNote;
    private Element mineWorkersEmpty;
    private Element mineWorkerSearchInput;
    private Element minePauseButton;

    private MiningPanelStatePacket mineState = MiningPanelStatePacket.outside();
    private UUID selectedMineId;
    private String mineWorkerFilter = "";
    private String shownMineWorkerFilter;

    private IndustryPanelStatePacket latest = IndustryPanelStatePacket.outside();
    private String selectedRecipeId;
    private String filter = "";
    private String workerFilter = "";
    private String shownFilter;
    private String shownWorkerFilter;
    private int refreshIn;

    public IndustryPanelScreen() {
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
        mainHead = document.getElementById("im-head");
        mainBody = document.getElementById("im-body");
        mainFoot = document.getElementById("im-foot");
        detailHead = document.getElementById("ic-head");
        detailBody = document.getElementById("ic-body");
        detailFoot = document.getElementById("ic-foot");
        titleText = document.getElementById("bp-name");
        levelText = document.getElementById("bp-level");
        position = document.getElementById("im-pos");
        blueprint = document.getElementById("im-blueprint");
        workerText = document.getElementById("im-worker");
        countText = document.getElementById("im-count");
        listHost = document.getElementById("im-recipes");
        emptyHint = document.getElementById("im-empty");
        searchInput = document.getElementById("im-search");
        detailName = document.getElementById("ic-name");
        detailRecipe = document.getElementById("ic-recipe");
        detailTicks = document.getElementById("ic-ticks");
        detailInputs = document.getElementById("ic-inputs");
        detailOutputs = document.getElementById("ic-outputs");
        detailStatus = document.getElementById("ic-status");
        workersHost = document.getElementById("ic-workers");
        workersNote = document.getElementById("ic-workers-note");
        workersEmpty = document.getElementById("ic-workers-empty");
        workerSearchInput = document.getElementById("ic-worker-search");
        mineListHost = document.getElementById("im-mines");
        mineCountText = document.getElementById("im-mine-count");
        mineEmptyHint = document.getElementById("im-mine-empty");
        mineHead = document.getElementById("in-head");
        mineBody = document.getElementById("in-body");
        mineFoot = document.getElementById("in-foot");
        mineNameText = document.getElementById("in-name");
        mineAreaText = document.getElementById("in-area");
        mineLayerText = document.getElementById("in-layer");
        mineProgressText = document.getElementById("in-progress");
        mineStatusText = document.getElementById("in-status");
        mineWorkersHost = document.getElementById("in-workers");
        mineWorkersNote = document.getElementById("in-workers-note");
        mineWorkersEmpty = document.getElementById("in-workers-empty");
        mineWorkerSearchInput = document.getElementById("in-worker-search");
        minePauseButton = document.getElementById("in-pause");

        on("im-search-clear", () -> setFilter(""));
        on("ic-back", this::closeDetail);
        on("ic-close", this::closeDetail);
        on("ic-clear-workers", this::clearWorkers);
        on("ic-worker-search-clear", () -> setWorkerFilter(""));
        on("bp-warehouse", this::openWarehouse);
        on("bp-close", this::onClose);
        on("im-start", this::startMineBrush);
        on("in-back", this::closeMineDetail);
        on("in-close", this::closeMineDetail);
        on("in-pause", this::toggleMineStatus);
        on("in-redraw", this::redrawMine);
        on("in-delete", this::deleteMine);
        on("in-worker-search-clear", () -> setMineWorkerFilter(""));

        setLevel(false);
        apply(latest);
        PacketDistributor.sendToServer(new IndustryPanelRequestPacket());
        PacketDistributor.sendToServer(new MiningPanelRequestPacket());
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

    public static void accept(IndustryPanelStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof IndustryPanelScreen screen) {
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
        if (mineWorkerSearchInput instanceof Input input && !input.getValue().equals(shownMineWorkerFilter)) {
            shownMineWorkerFilter = input.getValue();
            setMineWorkerFilter(shownMineWorkerFilter);
        }
        if (--refreshIn <= 0) {
            refreshIn = REFRESH_INTERVAL;
            PacketDistributor.sendToServer(new IndustryPanelRequestPacket());
            if (selectedMineId != null) {
                PacketDistributor.sendToServer(new MiningPanelRequestPacket());
            }
        }
    }

    private void apply(IndustryPanelStatePacket packet) {
        latest = packet;
        if (boundDocument == null) {
            return;
        }
        setText(titleText, packet.buildingName().isBlank() ? "工业" : packet.buildingName());
        setText(levelText, "Lv." + packet.buildingLevel());
        setText(blueprint, packet.buildingName().isBlank() ? "—" : packet.buildingName());
        setText(position, packet.position().isBlank() ? "—" : packet.position());
        setText(workerText, workerSummary(packet));
        setText(countText, packet.recipes().size() + " 项");

        Recipe selected = selected();
        if (selected == null && selectedRecipeId != null) {
            selectedRecipeId = null;
            setLevel(false);
        }
        refreshList(packet);
        refreshDetail(selected);
        refreshWorkers(selected);
        refreshMines();
    }

    public static void acceptMines(MiningPanelStatePacket packet) {
        MiningPlotOverlay.cache(packet);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof IndustryPanelScreen screen) {
            screen.applyMines(packet);
        }
    }

    private void applyMines(MiningPanelStatePacket packet) {
        mineState = packet;
        if (selectedMineId != null && mine(selectedMineId) == null) {
            selectedMineId = null;
            setLevel(selectedRecipeId != null);
        }
        refreshMines();
        refreshMineDetail();
        refreshMineWorkers();
    }

    private void refreshList(IndustryPanelStatePacket packet) {
        if (listHost == null) {
            return;
        }
        if (!packet.inside()) {
            listHost.setInnerHTML("");
            setShown(emptyHint, true);
            setText(emptyHint, "载入中…");
            return;
        }
        List<Recipe> visible = new ArrayList<>();
        for (Recipe recipe : packet.recipes()) {
            if (filter.isBlank() || recipe.name().contains(filter) || recipe.id().contains(filter)) {
                visible.add(recipe);
            }
        }
        if (visible.isEmpty()) {
            listHost.setInnerHTML("");
            setShown(emptyHint, true);
            setText(emptyHint, packet.recipes().isEmpty() ? "这座建筑没有配置生产配方" : "没有匹配的配方");
            return;
        }
        setShown(emptyHint, false);
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < visible.size(); index++) {
            Recipe recipe = visible.get(index);
            boolean active = recipe.id().equals(selectedRecipeId);
            html.append("<div class=\"").append(ROW).append(active ? " " + ON : "")
                    .append("\" id=\"im-row-").append(index).append("\">")
                    .append("<span class=\"bp-recipe-box\"><").append(TAG_ITEM)
                    .append(" class=\"bp-recipe-icon\" id=\"im-row-icon-").append(index)
                    .append("\"></").append(TAG_ITEM).append("></span>")
                    .append("<span class=\"bp-name\">").append(escape(recipe.name())).append("</span>")
                    .append("<span class=\"bp-sub\">").append(escape(recipe.outputs())).append("</span>")
                    .append("<span class=\"bp-tag\">").append(escape(recipe.status())).append("</span>")
                    .append("</div>");
        }
        listHost.setInnerHTML(html.toString());

        for (int index = 0; index < visible.size(); index++) {
            Recipe recipe = visible.get(index);
            Element row = boundDocument.getElementById("im-row-" + index);
            if (row != null) {
                row.addEventListener("click", event -> openDetail(recipe.id()));
            }
            applyIcon(boundDocument.getElementById("im-row-icon-" + index), recipe.icon());
        }
    }

    private void refreshDetail(Recipe recipe) {
        setText(detailName, recipe == null ? "生产配方" : recipe.name());
        setText(detailRecipe, recipe == null ? "—" : recipe.name());
        setText(detailTicks, recipe == null ? "—" : (recipe.ticks() / 20.0) + " 秒");
        setText(detailInputs, recipe == null ? "—" : recipe.inputs());
        setText(detailOutputs, recipe == null ? "—" : recipe.outputs());
        setText(detailStatus, recipe == null ? "未选择" : recipe.status());
    }

    private void refreshWorkers(Recipe recipe) {
        if (workersHost == null) {
            return;
        }
        List<UUID> crew = recipe == null ? List.of() : recipe.workers();
        setText(workersNote, crew.size() + " 人");
        if (recipe == null) {
            workersHost.setInnerHTML("");
            setShown(workersEmpty, true);
            setText(workersEmpty, "先点一张配方卡片");
            return;
        }
        Map<UUID, Worker> unique = new LinkedHashMap<>();
        for (Worker worker : latest.workers()) {
            if (workerFilter.isBlank() || worker.name().contains(workerFilter)) {
                unique.putIfAbsent(worker.id(), worker);
            }
        }
        List<Worker> visible = new ArrayList<>(unique.values());
        if (visible.isEmpty()) {
            AuiLazyList.clear(workersHost);
            workersHost.setInnerHTML("");
            setShown(workersEmpty, true);
            setText(workersEmpty, latest.workers().isEmpty() ? "城里还没有可雇的市民" : "没有匹配的市民");
            return;
        }
        setShown(workersEmpty, false);
        AuiLazyList.render(workersHost, visible.size(),
                index -> {
                    Worker worker = visible.get(index);
                    boolean hired = crew.contains(worker.id());
                    String state = worker.note().isBlank() ? (hired ? "在岗" : "空闲") : worker.note();
                    return "<div class=\"" + ROW + (hired ? " " + ON : "")
                            + "\" id=\"ic-worker-" + index + "\">"
                            + "<kingdom-entity-avatar class=\"bp-worker-face\" skin=\""
                            + escape(worker.skin()) + "\"></kingdom-entity-avatar>"
                            + "<span class=\"bp-name\">" + escape(worker.name()) + "</span>"
                            + "<span class=\"bp-sub\">" + escape(state) + "</span>"
                            + "</div>";
                },
                index -> {
                    UUID workerId = visible.get(index).id();
                    Element row = boundDocument.getElementById("ic-worker-" + index);
                    if (row != null) {
                        row.addEventListener("click", event -> toggleWorker(workerId));
                    }
                });
    }

    private static String workerSummary(IndustryPanelStatePacket packet) {
        List<String> names = new ArrayList<>();
        for (Recipe recipe : packet.recipes()) {
            for (UUID worker : recipe.workers()) {
                String name = nameOf(packet, worker);
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names.isEmpty() ? "未指派" : String.join("、", names);
    }

    private static String nameOf(IndustryPanelStatePacket packet, UUID workerId) {
        for (Worker worker : packet.workers()) {
            if (worker.id().equals(workerId)) {
                return worker.name();
            }
        }
        return "市民";
    }

    private void openDetail(String recipeId) {
        if (recipeId == null || recipeId.isBlank()) {
            return;
        }
        selectedRecipeId = recipeId;
        setLevel(true);
        apply(latest);
    }

    private void closeDetail() {
        selectedRecipeId = null;
        setLevel(false);
        apply(latest);
    }

    private void setLevel(boolean detail) {
        boolean mine = selectedMineId != null;
        setShown(mainHead, !detail && !mine);
        setShown(mainBody, !detail && !mine);
        setShown(mainFoot, !detail && !mine);
        setShown(detailHead, detail && !mine);
        setShown(detailBody, detail && !mine);
        setShown(detailFoot, detail && !mine);
        setShown(mineHead, mine);
        setShown(mineBody, mine);
        setShown(mineFoot, mine);
    }

    private void refreshMines() {
        if (mineListHost == null) {
            return;
        }
        List<MiningPanelStatePacket.Mine> mines = mineState.mines();
        setText(mineCountText, mines.size() + " 处");
        if (mines.isEmpty()) {
            mineListHost.setInnerHTML("");
            setShown(mineEmptyHint, true);
            setText(mineEmptyHint, mineState.inside() ? "这座建筑还没有矿坑" : "载入中…");
            return;
        }
        setShown(mineEmptyHint, false);
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < mines.size(); index++) {
            MiningPanelStatePacket.Mine mine = mines.get(index);
            boolean active = mine.id().equals(selectedMineId);
            html.append("<div class=\"").append(ROW).append(active ? " " + ON : "")
                    .append("\" id=\"im-mine-row-").append(index).append("\">")
                    .append("<span class=\"bp-name\">").append(escape(mine.name())).append("</span>")
                    .append("<span class=\"bp-sub\">")
                    .append(mine.maxX() - mine.minX() + 1).append("×")
                    .append(mine.maxZ() - mine.minZ() + 1).append("</span>")
                    .append("<span class=\"bp-sub\">y ").append(mine.currentY()).append("</span>")
                    .append("<span class=\"bp-tag\">").append(statusLabel(mine.status())).append("</span>")
                    .append("<span class=\"bp-sub\">").append(mine.workers().size()).append(" 人</span>")
                    .append("</div>");
        }
        mineListHost.setInnerHTML(html.toString());
        for (int index = 0; index < mines.size(); index++) {
            UUID mineId = mines.get(index).id();
            Element row = boundDocument.getElementById("im-mine-row-" + index);
            if (row != null) {
                row.addEventListener("click", event -> openMineDetail(mineId));
            }
        }
    }

    private void refreshMineDetail() {
        MiningPanelStatePacket.Mine mine = mine(selectedMineId);
        setText(mineNameText, mine == null ? "矿坑" : mine.name());
        setText(mineAreaText, mine == null ? "—"
                : mine.minX() + "," + mine.minZ() + " ~ " + mine.maxX() + "," + mine.maxZ());
        setText(mineLayerText, mine == null ? "—" : ("y " + mine.currentY() + "（地表 " + mine.surfaceY() + "）"));
        setText(mineProgressText, mine == null ? "—"
                : (mine.cursor() + " / " + mine.layerSize()));
        setText(mineStatusText, mine == null ? "未开始" : statusLabel(mine.status()));
        if (minePauseButton != null) {
            setText(minePauseButton, mine != null && "PAUSED".equals(mine.status()) ? "继续开采" : "暂停开采");
        }
    }

    private void refreshMineWorkers() {
        if (mineWorkersHost == null) {
            return;
        }
        MiningPanelStatePacket.Mine mine = mine(selectedMineId);
        List<UUID> crew = mine == null ? List.of() : mine.workers();
        setText(mineWorkersNote, crew.size() + " 人");
        if (mine == null) {
            mineWorkersHost.setInnerHTML("");
            setShown(mineWorkersEmpty, true);
            setText(mineWorkersEmpty, "先选一处矿坑");
            return;
        }
        Map<UUID, MiningPanelStatePacket.WorkerOption> unique = new LinkedHashMap<>();
        for (MiningPanelStatePacket.WorkerOption worker : mineState.workers()) {
            if (mineWorkerFilter.isBlank() || worker.name().contains(mineWorkerFilter)) {
                unique.putIfAbsent(worker.id(), worker);
            }
        }
        List<MiningPanelStatePacket.WorkerOption> visible = new ArrayList<>(unique.values());
        if (visible.isEmpty()) {
            AuiLazyList.clear(mineWorkersHost);
            mineWorkersHost.setInnerHTML("");
            setShown(mineWorkersEmpty, true);
            setText(mineWorkersEmpty, mineState.workers().isEmpty() ? "城里还没有可雇的市民" : "没有匹配的市民");
            return;
        }
        setShown(mineWorkersEmpty, false);
        AuiLazyList.render(mineWorkersHost, visible.size(),
                index -> {
                    MiningPanelStatePacket.WorkerOption worker = visible.get(index);
                    boolean hired = crew.contains(worker.id());
                    String state = worker.note().isBlank() ? (hired ? "在岗" : "空闲") : worker.note();
                    return "<div class=\"" + ROW + (hired ? " " + ON : "")
                            + "\" id=\"in-worker-" + index + "\">"
                            + "<kingdom-entity-avatar class=\"bp-worker-face\" skin=\""
                            + escape(worker.skin()) + "\"></kingdom-entity-avatar>"
                            + "<span class=\"bp-name\">" + escape(worker.name()) + "</span>"
                            + "<span class=\"bp-sub\">" + escape(state) + "</span>"
                            + "</div>";
                },
                index -> {
                    UUID workerId = visible.get(index).id();
                    Element row = boundDocument.getElementById("in-worker-" + index);
                    if (row != null) {
                        row.addEventListener("click", event -> toggleMineWorker(workerId));
                    }
                });
    }

    private void openMineDetail(UUID mineId) {
        if (mineId == null) {
            return;
        }
        selectedMineId = mineId;
        MiningPlotOverlay.setActive(mineId);
        setLevel(false);
        refreshMineDetail();
        refreshMineWorkers();
    }

    private void closeMineDetail() {
        selectedMineId = null;
        MiningPlotOverlay.setActive(null);
        setLevel(selectedRecipeId != null);
        refreshMines();
    }

    private void toggleMineWorker(UUID workerId) {
        if (selectedMineId == null) {
            return;
        }
        send(MiningPanelActionPacket.toggleWorker(selectedMineId, workerId));
    }

    private void toggleMineStatus() {
        MiningPanelStatePacket.Mine mine = mine(selectedMineId);
        if (mine == null) {
            return;
        }
        send("PAUSED".equals(mine.status())
                ? MiningPanelActionPacket.resume(mine.id())
                : MiningPanelActionPacket.pause(mine.id()));
    }

    private void deleteMine() {
        if (selectedMineId == null) {
            return;
        }
        send(MiningPanelActionPacket.delete(selectedMineId));
        selectedMineId = null;
        setLevel(false);
    }

    private void redrawMine() {
        onClose();
        if (!RtsModeManager.isActive()) {
            RtsModeManager.enter();
        }
        MiningBrushSelector.begin(null);
        PacketDistributor.sendToServer(new MiningPanelRequestPacket());
    }

    private void startMineBrush() {
        onClose();
        if (!RtsModeManager.isActive()) {
            RtsModeManager.enter();
        }
        MiningBrushSelector.begin(null);
        PacketDistributor.sendToServer(new MiningPanelRequestPacket());
    }

    private void setMineWorkerFilter(String value) {
        mineWorkerFilter = value == null ? "" : value;
        refreshMineWorkers();
    }

    private void send(MiningPanelActionPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    private MiningPanelStatePacket.Mine mine(UUID mineId) {
        if (mineId == null) {
            return null;
        }
        for (MiningPanelStatePacket.Mine mine : mineState.mines()) {
            if (mine.id().equals(mineId)) {
                return mine;
            }
        }
        return null;
    }

    private static String statusLabel(String status) {
        if ("RUNNING".equals(status)) {
            return "开采中";
        }
        if ("PAUSED".equals(status)) {
            return "已暂停";
        }
        if ("DONE".equals(status)) {
            return "已挖完";
        }
        return "待开工";
    }

    private void toggleWorker(UUID workerId) {
        if (selectedRecipeId == null) {
            hint("先点一张配方卡片");
            return;
        }
        send(IndustryPanelActionPacket.toggleWorker(selectedRecipeId, workerId));
    }

    private void clearWorkers() {
        if (selectedRecipeId == null) {
            return;
        }
        send(IndustryPanelActionPacket.clearWorkers(selectedRecipeId));
    }

    private void openWarehouse() {
        PacketDistributor.sendToServer(new BuildingStorageRequestPacket());
    }

    private void setFilter(String value) {
        filter = value == null ? "" : value;
        refreshList(latest);
    }

    private void setWorkerFilter(String value) {
        workerFilter = value == null ? "" : value;
        refreshWorkers(selected());
    }

    private Recipe selected() {
        if (selectedRecipeId == null) {
            return null;
        }
        for (Recipe recipe : latest.recipes()) {
            if (selectedRecipeId.equals(recipe.id())) {
                return recipe;
            }
        }
        return null;
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
        var item = BuiltInRegistries.ITEM.get(key);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return null;
        }
        return new ItemStack(item);
    }

    private static void send(IndustryPanelActionPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    private static void hint(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static void setText(Element element, String text) {
        if (element != null) {
            element.setTextContent(text == null ? "" : text);
        }
    }

    private static void setShown(Element element, boolean shown) {
        if (element != null) {
            element.setAttribute("style", shown ? "display:flex" : "display:none");
        }
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
