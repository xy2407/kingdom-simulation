package com.xy2407.kingdom.client.building;

import java.util.Map;
import java.util.Set;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.common.network.building.BuildingStorageRequestPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 各类建筑操作面板的共用宿主：工业/养殖/出售铺/餐厅/军事/住宅/仓库都开这一个类，底栏仓库按钮由它按页显隐。
 */
@OnlyIn(Dist.CLIENT)
public final class BuildingPanelScreen extends ApricityScreen {

    private static final String ID_WAREHOUSE = "bp-warehouse";
    private static final String ID_WAREHOUSE_ROW = "bp-warehouse-row";
    private static final String ID_CLOSE = "bp-close";
    private static final String ID_NAME = "bp-name";
    private static final String ID_LEVEL = "bp-level";
    private static final String CLASS_FOOT_OFF = "bp-foot-off";

    private static final Map<String, String> PREFIXES = Map.of(
            "industry", "im",
            "ranch", "rm",
            "restaurant", "rs",
            "shop", "sh",
            "military", "mt",
            "residential", "rd",
            "warehouse", "wh");

    private static final Map<String, String> MAIN_BUTTONS = Map.of(
            "industry", "im-start",
            "ranch", "rm-start",
            "restaurant", "rs-pick",
            "shop", "sh-shelve",
            "military", "mt-army",
            "residential", "rd-move",
            "warehouse", "wh-link");

    private static final Set<String> WAREHOUSE_PAGES = Set.of(
            "im", "rm", "rs", "sh", "mt", "wh");

    private final String page;
    private final String buildingName;
    private final int buildingLevel;
    private final String buildingPos;
    private boolean applied;

    public BuildingPanelScreen(String page, String buildingName, int buildingLevel, String buildingPos) {
        super(page);
        this.page = page == null ? "" : page;
        this.buildingName = buildingName == null ? "" : buildingName;
        this.buildingLevel = buildingLevel;
        this.buildingPos = buildingPos == null ? "" : buildingPos;
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    @Override
    protected void init() {
        super.init();
        applied = false;
        sync();
    }

    @Override
    public void tick() {
        super.tick();
        if (!applied) {
            sync();
        }
    }

    private void sync() {
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        applied = true;
        String prefix = prefixOf(page);
        Element warehouse = document.getElementById(ID_WAREHOUSE);
        if (warehouse != null) {
            warehouse.addEventListener("click", event -> openWarehouse());
        }
        setOff(document.getElementById(ID_WAREHOUSE_ROW), CLASS_FOOT_OFF, !WAREHOUSE_PAGES.contains(prefix));
        Element close = document.getElementById(ID_CLOSE);
        if (close != null) {
            close.addEventListener("click", event -> onClose());
        }
        Element main = document.getElementById(MAIN_BUTTONS.getOrDefault(prefix, ""));
        if (main != null) {
            main.addEventListener("click", event -> hint("该功能尚未接入"));
        }
        text(document, ID_NAME, buildingName.isBlank() ? "建筑" : buildingName);
        text(document, ID_LEVEL, "Lv." + buildingLevel);
        text(document, prefix + "-pos", buildingPos.isBlank() ? "—" : buildingPos);
        text(document, prefix + "-blueprint", buildingName.isBlank() ? "—" : buildingName);
    }

    static String prefixOf(String page) {
        if (page == null) {
            return "";
        }
        int slash = page.lastIndexOf('/');
        String file = slash < 0 ? page : page.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        return PREFIXES.getOrDefault(dot < 0 ? file : file.substring(0, dot), "");
    }

    private void openWarehouse() {
        PacketDistributor.sendToServer(new BuildingStorageRequestPacket());
    }

    private static void setOff(Element element, String token, boolean off) {
        if (element != null) {
            element.getClassList().toggle(token, off);
        }
    }

    private static void hint(String message) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static void text(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setTextContent(value);
        }
    }
}
