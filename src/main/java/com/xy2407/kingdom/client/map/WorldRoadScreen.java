package com.xy2407.kingdom.client.map;

import java.util.List;
import java.util.Set;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.common.network.road.RoadTeleportPacket;
import com.xy2407.kingdom.common.network.road.RoadWorldSubmitPacket;
import com.xy2407.kingdom.common.road.RoadPolyline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import com.xy2407.kingdom.client.aui.WorldMapElement;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.client.rts.RtsSubMode;
import com.xy2407.kingdom.common.network.road.RoadLoadedChunksRequestPacket;

import org.lwjgl.glfw.GLFW;

/**
 * 世界道路的宿主：ApricityScreen 全屏地图页，右键画折线、C 撤回整笔，退出时归还子模式。
 */
@OnlyIn(Dist.CLIENT)
public final class WorldRoadScreen extends ApricityScreen {

    public static final String PAGE = "ui/world_road.html";

    private static final int LOADED_REFRESH_TICKS = 100;

    private static final int STATUS_INTERVAL_TICKS = 10;

    private static Document boundDocument;

    private boolean viewInitialized;

    private int loadedCounter;

    private int statusCounter;

    private int lastRoadRevision;

    private boolean confirmOpen;

    private boolean teleportOpen;

    private int teleportChunkX;

    private int teleportChunkZ;

    public WorldRoadScreen() {
        super(PAGE);
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    public static void open() {
        RtsModeManager.enterSubMode(RtsSubMode.ROAD_WORLD);
        Minecraft.getInstance().setScreen(new WorldRoadScreen());
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
    public void tick() {
        super.tick();
        // 失步自愈：子模式被外部重置（如 RTS 退出）时，页面还开着就成了"没主的画线板"，直接关掉
        if (RtsModeManager.getSubMode() != RtsSubMode.ROAD_WORLD) {
            onClose();
            return;
        }
        if (++loadedCounter >= LOADED_REFRESH_TICKS) {
            loadedCounter = 0;
            requestLoadedChunks();
        }
        if (RoadClientStore.revision() != lastRoadRevision) {
            lastRoadRevision = RoadClientStore.revision();
            statusCounter = 0;
        }
        if (++statusCounter >= STATUS_INTERVAL_TICKS) {
            statusCounter = 0;
            refreshStatus();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_C) {
            RoadClientStore.undo();
            WorldMapElement.requestRepaint();
            refreshStatus();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (teleportOpen) {
                return true;
            }
            if (confirmOpen) {
                submitPending();
            } else {
                openConfirm();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && (confirmOpen || teleportOpen)) {
            closeConfirm();
            closeTeleportConfirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        WorldMapElement.setRoadMode(false);
        WorldMapElement.setRoadClickListener(null);
        RoadClientStore.reset();
        MapOverlayStore.setChangeListener(null);
        RtsModeManager.exitSubMode();
        boundDocument = null;
        super.onClose();
    }

    private void bind() {
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        boolean first = document != boundDocument;
        boundDocument = document;
        if (!first) {
            return;
        }
        WorldMapElement.setRoadMode(true);
        MapOverlayStore.setChangeListener(WorldMapElement::requestRepaint);
        WorldMapElement.setRoadClickListener(this::openTeleportConfirm);
        click(document, "wr-exit", this::onClose);
        click(document, "wr-undo", () -> {
            RoadClientStore.undo();
            WorldMapElement.requestRepaint();
            refreshStatus();
        });
        click(document, "wr-submit", this::openConfirm);
        click(document, "wrConfirmYes", this::submitPending);
        click(document, "wrConfirmNo", this::closeConfirm);
        click(document, "wrTeleportYes", this::confirmTeleport);
        click(document, "wrTeleportNo", this::closeTeleportConfirm);
        pointMap(document);
        requestLoadedChunks();
        refreshStatus();
    }

    private void pointMap(Document document) {
        if (viewInitialized) {
            return;
        }
        viewInitialized = true;
        LocalPlayer player = Minecraft.getInstance().player;
        int centerX = player == null ? 0 : (int) Math.round(player.getX());
        int centerZ = player == null ? 0 : (int) Math.round(player.getZ());
        Element map = document.getElementById("wrMap");
        if (map != null) {
            map.setAttribute("center-x", String.valueOf(centerX));
            map.setAttribute("center-z", String.valueOf(centerZ));
            if (map instanceof WorldMapElement worldMap) {
                worldMap.resetView();
            }
        }
    }

    // D7：世界道路提交同样要二次确认；取消则保留折线继续画，确认才发包
    private void openConfirm() {
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        List<RoadPolyline> lines = RoadClientStore.flatten();
        if (lines.isEmpty()) {
            notice("还没有画任何线条");
            return;
        }
        int chunks = RoadClientStore.coveredChunks(lines).size();
        Element body = document.getElementById("wrConfirmBody");
        if (body != null) {
            body.setInnerHTML("<div class=\"rline\"><span class=\"rline-k\">折线段</span>"
                    + "<span class=\"rline-v\">" + lines.size() + " 条</span></div>"
                    + "<div class=\"rline\"><span class=\"rline-k\">跨越区块</span>"
                    + "<span class=\"rline-v\">" + chunks + " 个</span></div>"
                    + "<div class=\"rline\"><span class=\"rline-k\">说明</span>"
                    + "<span class=\"rline-v\">服务器将分批强加载铺路，未连通领地的线会被丢弃</span></div>");
        }
        confirmOpen = true;
        Element modal = document.getElementById("wrConfirm");
        if (modal != null) {
            modal.getClassList().toggle("is-open", true);
        }
    }

    private void closeConfirm() {
        confirmOpen = false;
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        Element modal = document.getElementById("wrConfirm");
        if (modal != null) {
            modal.getClassList().toggle("is-open", false);
        }
    }

    private void submitPending() {
        List<RoadPolyline> lines = RoadClientStore.flatten();
        closeConfirm();
        if (lines.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new RoadWorldSubmitPacket(lines));
        RoadClientStore.clearPending();
        WorldMapElement.requestRepaint();
        refreshStatus();
        notice("已提交 " + lines.size() + " 条折线，服务器将分批铺设");
    }

    // W15：左键点中已提交道路区块 → 弹传送确认；确认后关屏传送（服务端校验 D3 归属）
    private void openTeleportConfirm(int chunkX, int chunkZ) {
        Document document = boundDocument;
        if (document == null || teleportOpen) {
            return;
        }
        teleportChunkX = chunkX;
        teleportChunkZ = chunkZ;
        Element body = document.getElementById("wrTeleportBody");
        if (body != null) {
            body.setInnerHTML("<div class=\"rline\"><span class=\"rline-k\">目标区块</span>"
                    + "<span class=\"rline-v\">" + chunkX + ", " + chunkZ + "</span></div>"
                    + "<div class=\"rline\"><span class=\"rline-k\">落点</span>"
                    + "<span class=\"rline-v\">区块中心的地表，保留朝向</span></div>");
        }
        teleportOpen = true;
        Element modal = document.getElementById("wrTeleport");
        if (modal != null) {
            modal.getClassList().toggle("is-open", true);
        }
    }

    private void closeTeleportConfirm() {
        teleportOpen = false;
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        Element modal = document.getElementById("wrTeleport");
        if (modal != null) {
            modal.getClassList().toggle("is-open", false);
        }
    }

    private void confirmTeleport() {
        closeTeleportConfirm();
        PacketDistributor.sendToServer(new RoadTeleportPacket(teleportChunkX, teleportChunkZ));
        Minecraft.getInstance().setScreen(null);
    }

    private static void requestLoadedChunks() {
        PacketDistributor.sendToServer(new RoadLoadedChunksRequestPacket());
    }

    private void refreshStatus() {
        Document document = boundDocument;
        if (document == null) {
            return;
        }
        var strokes = RoadClientStore.strokes();
        List<RoadPolyline> lines = RoadClientStore.flatten();
        Set<Long> own = WorldMapElement.ownTerritoryChunks();
        boolean[] rooted = RoadClientStore.computeRooted(lines, own);
        int rootedCount = 0;
        for (boolean flag : rooted) {
            if (flag) {
                rootedCount++;
            }
        }
        Element status = document.getElementById("wrStatus");
        if (status != null) {
            status.setTextContent("笔画 " + strokes.size() + " · 线段 " + lines.size()
                    + " · 连通 " + rootedCount + "/" + lines.size()
                    + " · 跨越区块 " + RoadClientStore.coveredChunks(lines).size());
        }
    }

    private static void click(Document document, String id, Runnable action) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.addEventListener("click", event -> action.run());
        }
    }

    private static void notice(String text) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(text), false);
        }
    }
}
