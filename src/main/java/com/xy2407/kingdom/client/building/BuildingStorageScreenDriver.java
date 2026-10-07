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
 * 建筑仓库页面的 Java 驱动：填标题与容量，并把超出"可用格数"的槽位隐藏掉。
 *
 * <p><b>槽位本身不归它管</b>：格子是页面里静态写死的 54 个 {@code <slot>}，
 * 取放、拖拽、shift-click 全部由 AUI 的容器屏处理。这里只做两件界面层的事——
 * 把建筑名与"能用几格"填上，再给用不到的格子打 {@code .is-off} 隐藏。</p>
 *
 * <p>为什么要隐藏：容器容量恒为 54（与 {@code BuildingStorageMenu.MAX_SLOTS} 一致），
 * 而蓝图声明的 {@code storage_slots} 可能只有 27。页面若按真实容量画格子就得为每座建筑
 * 备一份 HTML，所以改成"永远画满、打开时隐藏多余"。</p>
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class BuildingStorageScreenDriver {

    /** 页面根节点 id，用来确认"这一份 Document 是仓库页"，免得认领了别的容器页。 */
    private static final String ROOT_ID = "storageRoot";

    private static final String ID_TITLE = "storageTitle";
    private static final String ID_CAPACITY = "storageCapacity";
    /** 仓库槽位的 id 前缀，对应页面里的 {@code bslot0..bslot53}。 */
    private static final String SLOT_ID_PREFIX = "bslot";
    /** 隐藏超出容量槽位用的类名，样式在页面 CSS 里。 */
    private static final String OFF_CLASS = "is-off";

    private static final Logger LOGGER = LoggerFactory.getLogger("xy2407_kingdom/building_storage");

    /** 最近一次收到的展示参数（由网络包写入）。 */
    private static volatile BuildingStorageOpenPacket snapshot;
    /** 已经填进页面的那一份，用来判断"要不要重填"。 */
    private static BuildingStorageOpenPacket appliedSnapshot;
    private static Document boundDocument;

    static {
        BuildingStorageClientHooks.openSink = packet -> snapshot = packet;
    }

    private BuildingStorageScreenDriver() {
    }

    /** 屏幕一初始化就填一次，否则第一帧会先闪一下页面里的占位文本。 */
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
            // 是别的容器页，不认领
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
