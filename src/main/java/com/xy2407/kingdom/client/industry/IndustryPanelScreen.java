package com.xy2407.kingdom.client.industry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.element.Item;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.client.aui.AuiLazyList;
import com.xy2407.kingdom.common.network.building.BuildingStorageRequestPacket;
import com.xy2407.kingdom.common.network.industry.IndustryPanelActionPacket;
import com.xy2407.kingdom.common.network.industry.IndustryPanelRequestPacket;
import com.xy2407.kingdom.common.network.industry.IndustryPanelStatePacket;
import com.xy2407.kingdom.common.network.industry.IndustryPanelStatePacket.Recipe;
import com.xy2407.kingdom.common.network.industry.IndustryPanelStatePacket.Worker;

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

        on("im-search-clear", () -> setFilter(""));
        on("ic-back", this::closeDetail);
        on("ic-close", this::closeDetail);
        on("ic-clear-workers", this::clearWorkers);
        on("ic-worker-search-clear", () -> setWorkerFilter(""));
        on("bp-warehouse", this::openWarehouse);
        on("bp-close", this::onClose);
        on("im-start", () -> hint("工业生产由在岗工人自动推进，点配方卡片派人即可开产"));

        setLevel(false);
        apply(latest);
        PacketDistributor.sendToServer(new IndustryPanelRequestPacket());
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
        if (--refreshIn <= 0) {
            refreshIn = REFRESH_INTERVAL;
            PacketDistributor.sendToServer(new IndustryPanelRequestPacket());
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
        List<Worker> visible = new ArrayList<>();
        for (Worker worker : latest.workers()) {
            if (workerFilter.isBlank() || worker.name().contains(workerFilter)) {
                visible.add(worker);
            }
        }
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
        setShown(mainHead, !detail);
        setShown(mainBody, !detail);
        setShown(mainFoot, !detail);
        setShown(detailHead, detail);
        setShown(detailBody, detail);
        setShown(detailFoot, detail);
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
