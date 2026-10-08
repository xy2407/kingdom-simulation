package com.xy2407.kingdom.client.building;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityContainerScreen;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.network.building.BuildingStorageClientHooks;
import com.xy2407.kingdom.common.network.building.BuildingStorageOpenPacket;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 建筑仓库页面的 Java 驱动：填标题与容量，并把超出可用格数的槽位隐藏。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class BuildingStorageScreenDriver {

    private static final String ROOT_ID = "storageRoot";

    private static final String ID_TITLE = "storageTitle";
    private static final String ID_CAPACITY = "storageCapacity";
    private static final String SLOT_ID_PREFIX = "bslot";
    private static final String OFF_CLASS = "is-off";

    private static final Logger LOGGER = LoggerFactory.getLogger("xy2407_kingdom/building_storage");

    private static volatile BuildingStorageOpenPacket snapshot;
    private static BuildingStorageOpenPacket appliedSnapshot;
    private static Document boundDocument;

    static {
        BuildingStorageClientHooks.openSink = packet -> snapshot = packet;
    }

    private BuildingStorageScreenDriver() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ApricityContainerScreen screen)) {
            return;
        }
        Document document = screen.getLinkedDocument();
        BuildingStorageOpenPacket packet = snapshot;
        if (document == null || packet == null || document.getElementById(ROOT_ID) == null) {
            return;
        }
        boundDocument = document;
        apply(document, packet);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof ApricityContainerScreen screen)) {
            boundDocument = null;
            appliedSnapshot = null;
            return;
        }
        Document document = screen.getLinkedDocument();
        BuildingStorageOpenPacket packet = snapshot;
        if (document == null || packet == null) {
            return;
        }
        if (document.getElementById(ROOT_ID) == null) {
            return;
        }
        if (document != boundDocument || packet != appliedSnapshot) {
            boundDocument = document;
            apply(document, packet);
        }
    }

    private static void apply(Document document, BuildingStorageOpenPacket packet) {
        appliedSnapshot = packet;
        String name = packet.buildingName();
        text(document, ID_TITLE, name == null || name.isBlank() ? "仓 库" : name);
        text(document, ID_CAPACITY, packet.capacity() + " / " + packet.maxSlots() + " 格");
        int hidden = 0;
        for (int index = 0; index < packet.maxSlots(); index++) {
            Element slot = document.getElementById(SLOT_ID_PREFIX + index);
            if (slot == null) {
                continue;
            }
            boolean off = index >= packet.capacity();
            slot.getClassList().toggle(OFF_CLASS, off);
            if (off) {
                hidden++;
            }
        }
        LOGGER.info("仓库页数据已填入: {} 可用 {}/{} 格（隐藏 {} 格）",
                name, packet.capacity(), packet.maxSlots(), hidden);
    }

    private static void text(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null && value != null) {
            element.setTextContent(value);
        }
    }
}
