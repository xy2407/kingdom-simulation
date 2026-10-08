package com.xy2407.kingdom.client.structure;

import com.mojang.blaze3d.platform.InputConstants;
import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingType;
import com.xy2407.kingdom.client.aui.KingdomAuiStencil;
import com.xy2407.kingdom.client.aui.StructurePreviewElement;
import com.xy2407.kingdom.client.building.BuildingPanelClient;
import com.xy2407.kingdom.client.building.BuildingPlacementManager;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * RTS 建筑列表入口，Tab 开关 HUD 卡片并负责关闭时释放模型。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class StructureDebugKeybind {

    public static final String VIEWER_PAGE = "structures/viewer.html";

    private static final int MAX_CARDS = 512;
    private static final double HIT_PADDING = 3.0D;
    private static final double HIT_BOTTOM_BAND = 24.0D;

    public static final KeyMapping OPEN_VIEWER = new KeyMapping(
            "key.xy2407_kingdom.structure_viewer",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_TAB,
            "key.categories.xy2407_kingdom"
    );

    private static boolean rtsWasActive;

    private static final String TIP_ID = "building-tip";

    private static List<KingdomSimulationStructures.Entry> viewerEntries = List.of();
    private static boolean tipVisible;
    private static String tipShownId;

    private StructureDebugKeybind() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_VIEWER);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        KingdomSimulationStructures.deploy();
        boolean rtsActive = RtsModeManager.isActive();
        if (rtsWasActive && !rtsActive) {
            closeViewer();
        }
        rtsWasActive = rtsActive;

        while (OPEN_VIEWER.consumeClick()) {
            if (rtsActive) {
                if (isViewerOpen()) {
                    closeViewer();
                    StructureDebugChat.chat("已隐藏建筑预览 HUD 栏");
                    continue;
                }
                openViewer();
                continue;
            }
            BuildingPanelClient.requestAtPlayer();
        }
        KingdomAuiStencil.setSuppressed(isViewerOpen());
        updateCardTooltip();
    }

    public static void closeViewer() {
        if (isViewerOpen()) {
            ApricityUI.removeDocument(VIEWER_PAGE);
        }
        StructurePreviewElement.releaseAll();
        StructurePreviewTarget.clear();
        StructureDebugChat.reset();
        viewerEntries = List.of();
        tipVisible = false;
        tipShownId = null;
    }

    public static boolean isViewerOpen() {
        return !ApricityUI.getDocument(VIEWER_PAGE).isEmpty();
    }

    private static void openViewer() {
        StructureDebugChat.reset();
        StructureDebugChat.chat("按 Tab 打开建筑预览底部 HUD 栏");
        Document document = ApricityUI.createDocument(VIEWER_PAGE);
        if (document == null) {
            StructureDebugChat.chat("创建 HUD 文档失败：模板缺失：%s", VIEWER_PAGE);
            return;
        }
        document.setReloadPersistent(true);
        injectCards(document);
        StructureDebugChat.chat("已显示建筑预览 HUD 栏，建筑渲染将在元素绘制时触发");
    }

    private static void injectCards(Document document) {
        Element container = document.getElementById("viewer-cards");
        if (container == null) {
            StructureDebugChat.chat("找不到卡片容器 #viewer-cards");
            return;
        }
        List<KingdomSimulationStructures.Entry> entries = KingdomSimulationStructures.scan();
        if (entries.isEmpty()) {
            viewerEntries = List.of();
            StructureDebugChat.chat("structures 文件夹为空或无可用结构，无卡片可显示：%s",
                    KingdomSimulationStructures.structuresDir());
            return;
        }
        viewerEntries = entries;
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            html.append(buildCardHtml(entries.get(index), index));
        }
        container.setInnerHTML(html.toString());
        bindLoadButtons(document, entries);
        StructureDebugChat.chat("已注入 %d 张卡片", entries.size());
    }

    private static void updateCardTooltip() {
        List<Document> documents = ApricityUI.getDocument(VIEWER_PAGE);
        if (documents.isEmpty() || viewerEntries.isEmpty()) {
            return;
        }
        Document document = documents.get(0);
        Element tip = document.getElementById(TIP_ID);
        if (tip == null) {
            return;
        }
        KingdomSimulationStructures.Entry hovered = hoveredEntry(document);
        if (hovered == null) {
            if (tipVisible) {
                tipVisible = false;
                tip.setAttribute("class", "building-tip");
            }
            return;
        }
        if (!hovered.id().equals(tipShownId)) {
            tipShownId = hovered.id();
            tip.setInnerHTML(tooltipHtml(hovered));
        }
        if (!tipVisible) {
            tipVisible = true;
            tip.setAttribute("class", "building-tip tip-show");
        }
    }

    private static KingdomSimulationStructures.Entry hoveredEntry(Document document) {
        int limit = Math.min(viewerEntries.size(), MAX_CARDS);
        for (int index = 0; index < limit; index++) {
            Element card = document.getElementById("card-" + index);
            if (card == null) {
                return null;
            }
            if (card.isHover) {
                return viewerEntries.get(index);
            }
        }
        return null;
    }

    private static String tooltipHtml(KingdomSimulationStructures.Entry entry) {
        BuildingMeta meta = entry.meta();
        StringBuilder html = new StringBuilder();
        appendTipRow(html, "建筑", meta.name());
        appendTipRow(html, "类型", entry.typeLabel());
        appendTipRow(html, "等级", String.valueOf(meta.level()));
        if (meta.type() == BuildingType.RESIDENTIAL) {
            appendTipRow(html, "床位", String.valueOf(entry.beds()));
        } else {
            appendTipRow(html, "岗位", String.valueOf(meta.jobs()));
        }
        if (!meta.tags().isEmpty()) {
            appendTipRow(html, "标签", String.join("、", meta.tags()));
        }
        if (!meta.nation().isBlank()) {
            appendTipRow(html, "势力", meta.nation());
        }
        appendTipRow(html, "尺寸", entry.size().getX() + "×" + entry.size().getY() + "×" + entry.size().getZ());
        appendTipRow(html, "蓝图", meta.id());
        return html.toString();
    }

    private static void appendTipRow(StringBuilder html, String key, String value) {
        html.append("<span class=\"tip-row\"><span class=\"tip-key\">")
                .append(escapeAttr(key))
                .append("</span>")
                .append(escapeAttr(value))
                .append("</span>");
    }

    private static String buildCardHtml(KingdomSimulationStructures.Entry entry, int index) {
        String id = escapeAttr(entry.id());
        String name = escapeAttr(entry.displayName());
        String type = escapeAttr(entry.typeLabel());
        String open = "<div class=\"viewer-card\" id=\"card-" + index + "\">";
        String load = "<div class=\"card-load\" id=\"load-" + index + "\" data-id=\"" + id + "\">[加载]</div>";
        String cap = "<div class=\"card-cap\"><span class=\"card-type\">" + type + "</span>" + name + "</div>";
        if (!entry.previewable()) {
            return open + "<div class=\"card-preview card-toobig\" id=\"big-" + index + "\">"
                    + "<span class=\"toobig-title\">（建筑过大）</span>"
                    + "<span class=\"toobig-hint\">请点击查看</span>"
                    + "</div>" + cap + load + "</div>";
        }
        return open + "<div class=\"card-preview\">"
                + "<structure-preview structure=\"" + id + "\"></structure-preview>"
                + "</div>" + cap + load + "</div>";
    }

    private static void bindLoadButtons(Document document, List<KingdomSimulationStructures.Entry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            String id = entries.get(index).id();
            Element tooBig = document.getElementById("big-" + index);
            if (tooBig != null) {
                tooBig.addEventListener("click", event -> expandPreview(tooBig, id));
            }
            Element load = document.getElementById("load-" + index);
            if (load != null) {
                load.addEventListener("click", event -> loadIntoWorld(id));
            }
        }
    }

    private static void expandPreview(Element preview, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        preview.setAttribute("class", "card-preview");
        preview.setInnerHTML("<structure-preview structure=\"" + escapeAttr(id) + "\"></structure-preview>");
        StructureDebugChat.chat("已在卡片内加载预览：%s", id);
    }

    private static void loadIntoWorld(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        BlockPos start = BlockPos.containing(minecraft.player.position()).offset(2, 0, 2);
        BuildingPlacementManager.beginRtsFollow(name, start);
        StructureDebugChat.chat("已加载建筑投影：%s（移动鼠标选点，右键确认开工）", name);
    }

    private static String escapeAttr(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
