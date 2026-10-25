package com.xy2407.kingdom.client.employment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.common.network.employment.EmploymentDismissPacket;
import com.xy2407.kingdom.common.network.employment.EmploymentPanelStatePacket;
import com.xy2407.kingdom.common.network.employment.EmploymentSubmitPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 雇佣界面：搜索、分页、多选与上限截断，作为覆盖层压在原建筑面板之上
 */
@OnlyIn(Dist.CLIENT)
public final class EmploymentScreen extends ApricityScreen {

    public static final String PAGE = "buildings/employment.html";

    private static final int PER_PAGE = 12;

    private static final String CARD_ON = "ep-card on";
    private static final String CARD = "ep-card";

    private EmploymentPanelStatePacket packet;
    private final Screen parent;
    private final List<UUID> chosen = new ArrayList<>();
    private final Set<UUID> chosenSet = new HashSet<>();

    private Document boundDocument;
    private Element title;
    private Element limitText;
    private Element searchInput;
    private Element grid;
    private Element prev;
    private Element pageInput;
    private Element pages;
    private Element next;
    private Element cancel;
    private Element confirm;
    private Element hint;

    private String filter = "";
    private String shownFilter = "";
    private String shownPage = "";
    private int page;

    public EmploymentScreen(EmploymentPanelStatePacket packet, Screen parent) {
        super(PAGE);
        this.packet = packet == null ? EmploymentPanelStatePacket.empty() : packet;
        this.parent = parent;
        setShowDefaultBackground(false);
        setPauseGame(false);
        for (UUID id : this.packet.selected()) {
            if (chosenSet.add(id)) {
                chosen.add(id);
            }
        }
    }

    public static void open(EmploymentPanelStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof EmploymentScreen current) {
            current.accept(packet);
            return;
        }
        minecraft.setScreen(new EmploymentScreen(packet, minecraft.screen));
    }

    public void accept(EmploymentPanelStatePacket next) {
        if (next == null || next.entries().isEmpty() && packet.entries().isEmpty()) {
            return;
        }
        this.packet = next;
        this.chosen.clear();
        this.chosenSet.clear();
        for (UUID id : next.selected()) {
            if (chosenSet.add(id)) {
                chosen.add(id);
            }
        }
        paint();
    }

    @Override
    protected void init() {
        super.init();
        bind();
        paint();
    }

    @Override
    public void tick() {
        super.tick();
        if (searchInput instanceof Input input) {
            String value = input.getValue();
            if (!value.equals(shownFilter)) {
                shownFilter = value;
                filter = value == null ? "" : value.trim();
                page = 0;
                paint();
            }
        }
        if (pageInput instanceof Input input) {
            String value = input.getValue();
            if (!value.equals(shownPage)) {
                shownPage = value;
                applyPageInput(value);
            }
        }
    }

    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
            return;
        }
        super.onClose();
    }

    private void bind() {
        Document document = getLinkedDocument();
        if (document == null || document == boundDocument) {
            return;
        }
        boundDocument = document;
        title = document.getElementById("ep-title");
        limitText = document.getElementById("ep-limit");
        searchInput = document.getElementById("ep-search");
        grid = document.getElementById("ep-grid");
        prev = document.getElementById("ep-prev");
        pageInput = document.getElementById("ep-page");
        pages = document.getElementById("ep-pages");
        next = document.getElementById("ep-next");
        cancel = document.getElementById("ep-cancel");
        confirm = document.getElementById("ep-confirm");
        hint = document.getElementById("ep-hint");

        if (title != null) {
            setText(title, packet.kindLabel());
        }
        if (limitText != null) {
            setText(limitText, packet.limit() <= 0 ? "上限：不限" : "上限：" + packet.limit());
        }
        on(prev, () -> {
            if (page > 0) {
                page--;
                paint();
            }
        });
        on(next, () -> {
            if (page + 1 < pageCount()) {
                page++;
                paint();
            }
        });
        on(cancel, this::onClose);
        on(confirm, this::submit);
    }

    private void on(Element element, Runnable action) {
        if (element != null) {
            element.addEventListener("click", event -> action.run());
        }
    }

    private void submit() {
        PacketDistributor.sendToServer(new EmploymentSubmitPacket(
                packet.buildingId(), packet.targetId(), packet.kind(), chosen));
        onClose();
    }

    private void applyPageInput(String raw) {
        try {
            int wanted = Integer.parseInt(raw.trim());
            page = Math.max(0, Math.min(pageCount() - 1, wanted - 1));
        } catch (NumberFormatException exception) {
            page = Math.max(0, Math.min(pageCount() - 1, page));
        }
        paint();
    }

    private List<EmploymentPanelStatePacket.Entry> filtered() {
        List<EmploymentPanelStatePacket.Entry> result = new ArrayList<>();
        for (EmploymentPanelStatePacket.Entry entry : packet.entries()) {
            if (!filter.isEmpty() && !entry.name().contains(filter)) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    private int pageCount() {
        int count = filtered().size();
        return Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
    }

    private void paint() {
        if (grid == null) {
            return;
        }
        List<EmploymentPanelStatePacket.Entry> visible = filtered();
        int pagesTotal = Math.max(1, (visible.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pagesTotal) {
            page = pagesTotal - 1;
        }
        if (page < 0) {
            page = 0;
        }
        int offset = page * PER_PAGE;
        StringBuilder html = new StringBuilder();
        boolean full = packet.limit() > 0 && chosen.size() >= packet.limit();
        for (int index = 0; index < visible.size() && index < PER_PAGE; index++) {
            EmploymentPanelStatePacket.Entry entry = visible.get(offset + index);
            boolean picked = chosenSet.contains(entry.id());
            String style = picked ? CARD_ON : (full ? CARD + " busy" : CARD);
            html.append("<div id=\"ep-card-").append(index).append("\" class=\"")
                    .append(style).append("\">")
                    .append("<kingdom-entity-avatar class=\"ep-face\" skin=\"")
                    .append(escape(entry.skin())).append("\"></kingdom-entity-avatar>")
                    .append("<div class=\"ep-info\"><div class=\"ep-name\">")
                    .append(escape(entry.name())).append("</div>");
            if (!entry.job().isEmpty()) {
                html.append("<div class=\"ep-job\">").append(escape(entry.job())).append("</div>");
            }
            html.append("</div>");
            if (entry.hired()) {
                html.append("<div class=\"ep-fire\" id=\"ep-fire-").append(index).append("\">✕</div>");
            }
            html.append("</div>");
        }
        if (visible.isEmpty()) {
            html.append("<div class=\"ep-empty\">没有符合条件的市民</div>");
        }
        grid.setInnerHTML(html.toString());
        for (int index = 0; index < visible.size() && index < PER_PAGE; index++) {
            EmploymentPanelStatePacket.Entry entry = visible.get(offset + index);
            Element card = boundDocument == null ? null
                    : boundDocument.getElementById("ep-card-" + index);
            if (card != null) {
                card.addEventListener("click", event -> toggle(entry));
            }
            if (entry.hired()) {
                Element fire = boundDocument == null ? null
                        : boundDocument.getElementById("ep-fire-" + index);
                if (fire != null) {
                    fire.addEventListener("click", event -> dismiss(entry));
                }
            }
        }
        if (pages != null) {
            setText(pages, "/ " + pagesTotal);
        }
        if (pageInput instanceof Input input) {
            shownPage = input.getValue();
        }
        if (hint != null) {
            setText(hint, "已选 " + chosen.size()
                    + (packet.limit() <= 0 ? "" : " / " + packet.limit()) + " 人");
        }
    }

    private void dismiss(EmploymentPanelStatePacket.Entry entry) {
        PacketDistributor.sendToServer(new EmploymentDismissPacket(
                packet.buildingId(), packet.targetId(), packet.kind(), entry.id()));
    }

    private void toggle(EmploymentPanelStatePacket.Entry entry) {
        if (chosenSet.contains(entry.id())) {
            chosenSet.remove(entry.id());
            chosen.remove(entry.id());
            paint();
            return;
        }
        if (packet.limit() > 0 && chosen.size() >= packet.limit()) {
            if (hint != null) {
                setText(hint, "已达上限 " + packet.limit() + " 人");
            }
            return;
        }
        if (!entry.note().isEmpty()) {
            if (hint != null) {
                setText(hint, entry.name() + "：" + entry.note());
            }
            return;
        }
        if (chosenSet.add(entry.id())) {
            chosen.add(entry.id());
        }
        paint();
    }

    private static void setText(Element element, String text) {
        if (element != null) {
            element.setTextContent(text == null ? "" : text);
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
