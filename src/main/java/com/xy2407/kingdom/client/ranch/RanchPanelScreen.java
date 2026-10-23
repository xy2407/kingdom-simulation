package com.xy2407.kingdom.client.ranch;

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
import com.xy2407.kingdom.common.network.building.BuildingStorageRequestPacket;
import com.xy2407.kingdom.common.network.ranch.RanchPanelActionPacket;
import com.xy2407.kingdom.common.network.ranch.RanchPanelRequestPacket;
import com.xy2407.kingdom.common.network.ranch.RanchPanelStatePacket;
import com.xy2407.kingdom.common.network.ranch.RanchPanelStatePacket.Animal;
import com.xy2407.kingdom.common.network.ranch.RanchPanelStatePacket.Worker;
import com.xy2407.kingdom.common.npc.NpcShift;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 养殖操作界面的驱动：一级页铺蓝图 json 的配方并开始养殖，二级页选配方详情与雇饲养员
 */
@OnlyIn(Dist.CLIENT)
public final class RanchPanelScreen extends ApricityScreen {

    public static final String PAGE = "buildings/ranch.html";

    private static final String ON = "on";
    private static final String ROW = "bp-item";
    private static final String TAG_ITEM = "item";
    private static final String MODE_SLAUGHTER = "slaughter";
    private static final String SHIFT_DAY = NpcShift.DAY_SHIFT.name();
    private static final String SHIFT_NIGHT = NpcShift.NIGHT_SHIFT.name();
    private static final int REFRESH_INTERVAL = 40;

    private Document boundDocument;
    private Element titleText;
    private Element levelText;
    private Element position;
    private Element blueprint;
    private Element recipeText;
    private Element keeperText;
    private Element totalText;
    private Element listHost;
    private Element emptyHint;
    private Element searchInput;
    private Element startButton;
    private Element mainHead;
    private Element mainBody;
    private Element mainFoot;
    private Element detailHead;
    private Element detailBody;
    private Element detailFoot;
    private Element detailName;
    private Element detailAnimal;
    private Element detailRecipe;
    private Element detailStock;
    private Element detailStatus;
    private Element workersHost;
    private Element workersNote;
    private Element workersEmpty;
    private Element workerSearchInput;
    private Element warehouseRow;

    private RanchPanelStatePacket latest = RanchPanelStatePacket.outside();
    private UUID selectedPenId;
    private String filter = "";
    private String workerFilter = "";
    private String shownFilter;
    private String shownWorkerFilter;
    private int refreshIn;

    public RanchPanelScreen() {
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
        mainHead = document.getElementById("rm-head");
        mainBody = document.getElementById("rm-body");
        mainFoot = document.getElementById("rm-foot");
        detailHead = document.getElementById("rc-head");
        detailBody = document.getElementById("rc-body");
        detailFoot = document.getElementById("rc-foot");
        titleText = document.getElementById("bp-name");
        levelText = document.getElementById("bp-level");
        position = document.getElementById("rm-pos");
        blueprint = document.getElementById("rm-blueprint");
        recipeText = document.getElementById("rm-recipe");
        keeperText = document.getElementById("rm-keeper");
        totalText = document.getElementById("rm-total");
        listHost = document.getElementById("rm-animals");
        emptyHint = document.getElementById("rm-empty");
        searchInput = document.getElementById("rm-search");
        startButton = document.getElementById("rm-start");
        detailName = document.getElementById("rc-name");
        detailAnimal = document.getElementById("rc-animal");
        detailRecipe = document.getElementById("rc-recipe");
        detailStock = document.getElementById("rc-stock");
        detailStatus = document.getElementById("rc-status");
        workersHost = document.getElementById("rc-workers");
        workersNote = document.getElementById("rc-workers-note");
        workersEmpty = document.getElementById("rc-workers-empty");
        workerSearchInput = document.getElementById("rc-worker-search");
        warehouseRow = document.getElementById("bp-warehouse-row");

        on("rm-search-clear", () -> setFilter(""));
        on("rm-start", this::toggleRun);
        on("rm-area", this::startRanchArea);
        on("rc-back", this::closeDetail);
        on("rc-close", this::closeDetail);
        on("rc-clear-workers", this::clearKeepers);
        on("rc-worker-search-clear", () -> setWorkerFilter(""));
        on("bp-warehouse", this::openWarehouse);
        on("bp-close", this::onClose);

        setShown(warehouseRow, true);
        setLevel(false);
        apply(latest);
        PacketDistributor.sendToServer(new RanchPanelRequestPacket());
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

    public static void accept(RanchPanelStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RanchPanelScreen screen) {
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
            PacketDistributor.sendToServer(new RanchPanelRequestPacket());
        }
    }

    private void apply(RanchPanelStatePacket packet) {
        latest = packet;
        if (boundDocument == null) {
            return;
        }
        setText(titleText, packet.buildingName().isBlank() ? "养殖" : packet.buildingName());
        setText(levelText, "Lv." + packet.buildingLevel());
        setText(blueprint, packet.buildingName().isBlank() ? "—" : packet.buildingName());
        setText(position, packet.position().isBlank() ? "—" : packet.position());

        Animal selected = selected();
        if (selected == null) {
            selectedPenId = null;
            for (Animal animal : packet.animals()) {
                if (animal.running()) {
                    selectedPenId = animal.penId();
                    selected = animal;
                    break;
                }
            }
        }
        setText(keeperText, keeperLabel(selected));
        setText(recipeText, recipeLabel(selected));
        setText(startButton, selected != null && selected.running() ? "暂停养殖" : "开始养殖");
        setText(totalText, totalLabel(packet));
        refreshDetail(selected);
        refreshList(packet);
        refreshWorkers(selected);
    }

    private void refreshList(RanchPanelStatePacket packet) {
        if (listHost == null) {
            return;
        }
        if (!packet.inside()) {
            listHost.setInnerHTML("");
            setShown(emptyHint, true);
            setText(emptyHint, "载入中…");
            return;
        }
        List<Animal> visible = new ArrayList<>();
        for (Animal animal : packet.animals()) {
            if (filter.isBlank() || animal.name().contains(filter) || animal.id().contains(filter)) {
                visible.add(animal);
            }
        }
        if (visible.isEmpty()) {
            listHost.setInnerHTML("");
            setShown(emptyHint, true);
            setText(emptyHint, packet.animals().isEmpty() ? "这座建筑没有配置可养动物" : "没有匹配的动物");
            return;
        }
        setShown(emptyHint, false);
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < visible.size(); index++) {
            Animal animal = visible.get(index);
            boolean active = animal.penId() != null && animal.penId().equals(selectedPenId);
            html.append("<div class=\"").append(ROW).append(active ? " " + ON : "")
                    .append("\" id=\"rm-row-").append(index).append("\">")
                    .append("<span class=\"bp-animal\"><").append(TAG_ITEM)
                    .append(" class=\"bp-animal-icon\" id=\"rm-row-icon-").append(index)
                    .append("\"></").append(TAG_ITEM).append("></span>")
                    .append("<span class=\"bp-name\">").append(escape(animal.name())).append("</span>")
                    .append("<span class=\"bp-sub\">").append(animal.adults()).append("/")
                    .append(animal.capacity()).append(" 头</span>")
                    .append("<span class=\"bp-tag\">").append(escape(animal.status())).append("</span>")
                    .append("</div>");
        }
        listHost.setInnerHTML(html.toString());

        for (int index = 0; index < visible.size(); index++) {
            Animal animal = visible.get(index);
            UUID penId = animal.penId();
            Element row = boundDocument.getElementById("rm-row-" + index);
            if (row != null && penId != null) {
                row.addEventListener("click", event -> openDetail(penId));
            }
            applyIcon(boundDocument.getElementById("rm-row-icon-" + index),
                    animal.output().isBlank() ? animal.feed() : animal.output());
        }
    }

    private void refreshDetail(Animal animal) {
        setText(detailName, animal == null ? "可养动物" : animal.name());
        setText(detailAnimal, animal == null ? "—" : label(animal.entity()));
        setText(detailRecipe, recipeLabel(animal));
        setText(detailStock, animal == null ? "0 / 0 头"
                : animal.adults() + " / " + animal.capacity() + " 头（共 " + animal.total() + " 头）");
        setText(detailStatus, animal == null ? "未开始" : animal.status());
    }

    private void refreshWorkers(Animal animal) {
        if (workersHost == null) {
            return;
        }
        List<UUID> keepers = animal == null ? List.of() : animal.keepers();
        setText(workersNote, keepers.size() + " 人");
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
                    boolean hired = keepers.contains(worker.id());
                    boolean onDay = hired && SHIFT_DAY.equals(worker.shift());
                    boolean onNight = hired && SHIFT_NIGHT.equals(worker.shift());
                    String state = worker.note().isBlank() ? (hired ? "饲养中" : "空闲") : worker.note();
                    return "<div class=\"" + ROW + (hired ? " " + ON : "")
                            + "\" id=\"rc-worker-" + index + "\">"
                            + "<kingdom-entity-avatar class=\"bp-worker-face\" skin=\""
                            + escape(worker.skin()) + "\"></kingdom-entity-avatar>"
                            + "<span class=\"bp-name\">" + escape(worker.name()) + "</span>"
                            + "<span class=\"bp-sub\">" + escape(state) + "</span>"
                            + "<span class=\"bp-shift" + (onDay ? " " + ON : "")
                            + "\" id=\"rc-worker-" + index + "-day\">白天</span>"
                            + "<span class=\"bp-shift" + (onNight ? " " + ON : "")
                            + "\" id=\"rc-worker-" + index + "-night\">夜晚</span>"
                            + "</div>";
                },
                index -> {
                    Worker worker = visible.get(index);
                    Element day = boundDocument.getElementById("rc-worker-" + index + "-day");
                    if (day != null) {
                        day.addEventListener("click", event -> setKeeper(worker.id(), NpcShift.DAY_SHIFT));
                    }
                    Element night = boundDocument.getElementById("rc-worker-" + index + "-night");
                    if (night != null) {
                        night.addEventListener("click", event -> setKeeper(worker.id(), NpcShift.NIGHT_SHIFT));
                    }
                });
    }

    private void openDetail(UUID penId) {
        selectedPenId = penId;
        setLevel(true);
        apply(latest);
    }

    private void closeDetail() {
        setLevel(false);
    }

    private void setLevel(boolean detail) {
        setShown(mainHead, !detail);
        setShown(mainBody, !detail);
        setShown(mainFoot, !detail);
        setShown(detailHead, detail);
        setShown(detailBody, detail);
        setShown(detailFoot, detail);
    }

    private void startRanchArea() {
        onClose();
        if (!com.xy2407.kingdom.client.rts.RtsModeManager.isActive()) {
            com.xy2407.kingdom.client.rts.RtsModeManager.enter();
        }
        com.xy2407.kingdom.client.area.AreaSelectionState.begin(
                com.xy2407.kingdom.common.area.PlotAreaKind.RANCH);
    }

    private void toggleRun() {
        if (selectedPenId == null) {
            hint("先点一种动物");
            return;
        }
        send(RanchPanelActionPacket.toggleRun(selectedPenId));
    }

    private void setKeeper(UUID workerId, NpcShift shift) {
        if (selectedPenId == null) {
            hint("先点一种动物");
            return;
        }
        send(RanchPanelActionPacket.setKeeper(selectedPenId, workerId, shift));
    }

    private void clearKeepers() {
        if (selectedPenId == null) {
            return;
        }
        send(RanchPanelActionPacket.clearKeepers(selectedPenId));
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

    private Animal selected() {
        if (selectedPenId == null) {
            return null;
        }
        for (Animal animal : latest.animals()) {
            if (selectedPenId.equals(animal.penId())) {
                return animal;
            }
        }
        return null;
    }

    private String keeperLabel(Animal animal) {
        if (animal == null || animal.keepers().isEmpty()) {
            return "未指派";
        }
        List<String> names = new ArrayList<>();
        for (UUID keeper : animal.keepers()) {
            names.add(nameOf(keeper));
        }
        return String.join("、", names);
    }

    private String nameOf(UUID workerId) {
        for (Worker worker : latest.workers()) {
            if (worker.id().equals(workerId)) {
                return worker.name();
            }
        }
        return "市民";
    }

    private static String recipeLabel(Animal animal) {
        if (animal == null) {
            return "—";
        }
        return label(animal.feed()) + " → " + label(animal.output()) + " · " + modeLabel(animal.mode())
                + " · 上限 " + animal.capacity() + " 头";
    }

    private static String totalLabel(RanchPanelStatePacket packet) {
        int adults = 0;
        int capacity = 0;
        for (Animal animal : packet.animals()) {
            adults += animal.adults();
            capacity += animal.capacity();
        }
        return adults + " / " + capacity + " 头";
    }

    private static String modeLabel(String mode) {
        return mode != null && MODE_SLAUGHTER.equalsIgnoreCase(mode) ? "宰杀" : "采集";
    }

    private static String label(String itemId) {
        ItemStack stack = stackOf(itemId);
        return stack == null || stack.isEmpty() ? "—" : stack.getHoverName().getString();
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

    private void applyIcon(Element element, String itemId) {
        if (!(element instanceof Item item)) {
            return;
        }
        ItemStack stack = stackOf(itemId);
        item.setIngredientStack(stack == null ? ItemStack.EMPTY : stack);
    }

    private static void send(RanchPanelActionPacket packet) {
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
