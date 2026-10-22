package com.xy2407.kingdom.client.map;

import java.util.Set;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.client.aui.WorldMapElement;
import com.xy2407.kingdom.client.cityhall.CityHallScreen;
import com.xy2407.kingdom.common.network.faction.TerritoryBuyPacket;
import com.xy2407.kingdom.common.network.road.RoadTeleportPacket;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

/**
 * 世界地图的宿主：M 键全屏打开，交互与市政厅放大地图一致（无道路画笔）。
 */
@OnlyIn(Dist.CLIENT)
public final class WorldMapScreen extends ApricityScreen {

    public static final String PAGE = "ui/world_map.html";

    private static Document boundDocument;

    private static int centerX;

    private static int centerZ;

    private Set<Long> buySelection;

    private boolean roadTeleportOpen;

    private int roadTeleportChunkX;

    private int roadTeleportChunkZ;

    public WorldMapScreen() {
        super(PAGE);
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        centerX = minecraft.player.blockPosition().getX();
        centerZ = minecraft.player.blockPosition().getZ();
        Minecraft.getInstance().setScreen(new WorldMapScreen());
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
        if (boundDocument == null) {
            bind();
        }
    }

    @Override
    public void onClose() {
        WorldMapElement.clearSelection();
        WorldMapElement.setSelectionListener(null);
        WorldMapElement.setRoadClickListener(null);
        MapOverlayStore.setChangeListener(null);
        buySelection = null;
        roadTeleportOpen = false;
        boundDocument = null;
        super.onClose();
    }

    private void bind() {
        Document document = getLinkedDocument();
        if (document == null || document == boundDocument) {
            return;
        }
        boundDocument = document;
        pointMap(document);
        WorldMapElement.setSelectionListener(this::openBuyModal);
        WorldMapElement.setRoadClickListener(this::openRoadTeleport);
        click(document, "wmBuyCancel", this::closeBuyModal);
        click(document, "wmBuyConfirm", this::confirmBuy);
        click(document, "wmRoadCancel", this::closeRoadTeleport);
        click(document, "wmRoadConfirm", this::confirmRoadTeleport);
        MapOverlayStore.setChangeListener(() -> refreshMapTip(document));
        Element stage = document.getElementById("wmStage");
        if (stage != null) {
            stage.addEventListener("wheel", event -> event.preventDefault());
        }
        refreshMapTip(document);
    }

    private void pointMap(Document document) {
        Element map = document.getElementById("wmMap");
        if (map == null) {
            return;
        }
        map.setAttribute("center-x", String.valueOf(centerX));
        map.setAttribute("center-z", String.valueOf(centerZ));
        if (map instanceof WorldMapElement worldMap) {
            worldMap.resetView();
        }
    }

    private void openBuyModal(Set<Long> chunks) {
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        buySelection = Set.copyOf(chunks);
        Element body = document.getElementById("wmBuyBody");
        if (body != null) {
            body.setInnerHTML("<div class=\"rline\"><span class=\"rline-k\">已选区块</span>"
                    + "<span class=\"rline-v\">" + chunks.size() + " 个</span></div>"
                    + "<div class=\"rline\"><span class=\"rline-k\">操作</span>"
                    + "<span class=\"rline-v\">确认后并入你的领地</span></div>");
        }
        toggleClass(document, "wmBuyModal", "is-open", true);
    }

    private void closeBuyModal() {
        buySelection = null;
        WorldMapElement.clearSelection();
        if (boundDocument != null) {
            toggleClass(boundDocument, "wmBuyModal", "is-open", false);
        }
    }

    private void confirmBuy() {
        Set<Long> selection = buySelection;
        closeBuyModal();
        if (selection == null || selection.isEmpty()) {
            return;
        }
        long[] chunks = new long[selection.size()];
        int index = 0;
        for (long key : selection) {
            chunks[index++] = key;
        }
        PacketDistributor.sendToServer(new TerritoryBuyPacket(chunks));
    }

    private void openRoadTeleport(int chunkX, int chunkZ) {
        Document document = boundDocument;
        if (document == null || roadTeleportOpen) {
            return;
        }
        roadTeleportChunkX = chunkX;
        roadTeleportChunkZ = chunkZ;
        Element body = document.getElementById("wmRoadBody");
        if (body != null) {
            body.setInnerHTML("<div class=\"rline\"><span class=\"rline-k\">目标区块</span>"
                    + "<span class=\"rline-v\">" + chunkX + ", " + chunkZ + "</span></div>"
                    + "<div class=\"rline\"><span class=\"rline-k\">落点</span>"
                    + "<span class=\"rline-v\">区块中心的地表，保留朝向</span></div>");
        }
        roadTeleportOpen = true;
        toggleClass(document, "wmRoadModal", "is-open", true);
    }

    private void closeRoadTeleport() {
        roadTeleportOpen = false;
        if (boundDocument != null) {
            toggleClass(boundDocument, "wmRoadModal", "is-open", false);
        }
    }

    private void confirmRoadTeleport() {
        closeRoadTeleport();
        PacketDistributor.sendToServer(new RoadTeleportPacket(roadTeleportChunkX, roadTeleportChunkZ));
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && boundDocument != null) {
            if (roadTeleportOpen) {
                closeRoadTeleport();
                return true;
            }
            if (buySelection != null) {
                closeBuyModal();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static void refreshMapTip(Document document) {
        String html = CityHallScreen.mapTipHtml();
        Element tip = document.getElementById("wmTip");
        if (tip == null) {
            return;
        }
        if (html.isEmpty()) {
            tip.getClassList().toggle("is-open", false);
        } else {
            tip.setInnerHTML(html);
            tip.getClassList().toggle("is-open", true);
        }
    }

    private static void click(Document document, String id, Runnable action) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.addEventListener("click", event -> action.run());
        }
    }

    private static void toggleClass(Document document, String id, String token, boolean on) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.getClassList().toggle(token, on);
        }
    }
}
