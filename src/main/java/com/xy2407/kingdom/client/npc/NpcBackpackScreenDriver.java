package com.xy2407.kingdom.client.npc;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityContainerScreen;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.client.watchdog.ClientWatchdog;
import com.xy2407.kingdom.common.network.npc.NpcBackpackClientHooks;
import com.xy2407.kingdom.common.network.npc.NpcBackpackOpenPacket;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcInventory;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 行装页面的 Java 驱动：等 AUI 建好 Document 后填入档案文本与生命/饥饿数值。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class NpcBackpackScreenDriver {

    private static final String CARD_ID = "idCard";

    private static final String ID_BAG_META = "bagMeta";
    private static final String ID_BIO_NO = "bioNo";
    private static final String ID_BIO_NAME = "bioName";
    private static final String ID_BIO_JOB = "bioJob";
    private static final String ID_BIO_GENDER = "bioGender";
    private static final String ID_BIO_AGE = "bioAge";
    private static final String ID_BIO_PERSONALITY = "bioPersonality";
    private static final String ID_BIO_LIFECYCLE = "bioLifecycle";
    private static final String ID_BIO_NATION = "bioNation";
    private static final String ID_BIO_WEALTH = "bioWealth";
    private static final String ID_BIO_DESC = "bioDesc";
    private static final String ID_HP_BAR = "hpBar";
    private static final String ID_HP_NOW = "hpNow";
    private static final String ID_HP_MAX = "hpMax";
    private static final String ID_HUNGER_BAR = "hungerBar";
    private static final String ID_HUNGER_NOW = "hungerNow";
    private static final String ID_HUNGER_MAX = "hungerMax";

    private static final String ATTR_VALUE = "value";
    private static final String ATTR_MAX = "max";

    private static final Logger LOGGER = LoggerFactory.getLogger("xy2407_kingdom/npc_backpack");

    private static Document boundDocument;
    private static NpcBackpackOpenPacket appliedSnapshot;
    private static double shownHealth = Double.NaN;
    private static double shownHealthMax = Double.NaN;
    private static double shownHunger = Double.NaN;

    static {
        NpcBackpackClientHooks.openSink = NpcBackpackClientState::accept;
    }

    private NpcBackpackScreenDriver() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ApricityContainerScreen screen)) {
            return;
        }
        Document document = screen.getLinkedDocument();
        NpcBackpackOpenPacket snapshot = NpcBackpackClientState.snapshot();
        if (document == null || snapshot == null || document.getElementById(CARD_ID) == null) {
            return;
        }
        boundDocument = document;
        appliedSnapshot = snapshot;
        apply(document, snapshot);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof ApricityContainerScreen screen)) {
            boundDocument = null;
            appliedSnapshot = null;
            resetVitals();
            return;
        }
        Document document = screen.getLinkedDocument();
        if (document == null) {
            return;
        }
        NpcBackpackOpenPacket snapshot = NpcBackpackClientState.snapshot();
        if (snapshot == null) {
            return;
        }
        if (document.getElementById(CARD_ID) == null) {
            return;
        }
        if (document != boundDocument || snapshot != appliedSnapshot) {
            boundDocument = document;
            appliedSnapshot = snapshot;
            resetVitals();
            apply(document, snapshot);
        }
        refreshVitals(document, minecraft, snapshot);
    }

    private static void resetVitals() {
        shownHealth = Double.NaN;
        shownHealthMax = Double.NaN;
        shownHunger = Double.NaN;
    }

    private static void refreshVitals(Document document, Minecraft minecraft, NpcBackpackOpenPacket data) {
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(data.entityId());
        if (!(entity instanceof NpcEntity npc)) {
            return;
        }
        double health = npc.getHealth();
        double healthMax = npc.getMaxHealth();
        double hunger = npc.getSyncedHunger();
        if (health == shownHealth && healthMax == shownHealthMax && hunger == shownHunger) {
            return;
        }
        shownHealth = health;
        shownHealthMax = healthMax;
        shownHunger = hunger;
        bar(document, ID_HP_BAR, ID_HP_NOW, ID_HP_MAX, health, healthMax);
        bar(document, ID_HUNGER_BAR, ID_HUNGER_NOW, ID_HUNGER_MAX, hunger, NpcData.HUNGER_MAX);
    }

    private static void apply(Document document, NpcBackpackOpenPacket data) {
        ClientWatchdog.crumb("bag-apply", "entity=" + data.entityId() + " doc=" + document.getPath());
        text(document, ID_BIO_NO, data.no());
        text(document, ID_BIO_NAME, data.name());
        text(document, ID_BIO_JOB, data.job());
        text(document, ID_BIO_GENDER, data.gender());
        text(document, ID_BIO_AGE, data.age());
        text(document, ID_BIO_PERSONALITY, data.personality());
        text(document, ID_BIO_LIFECYCLE, data.lifecycle());
        text(document, ID_BIO_NATION, data.nation());
        text(document, ID_BIO_WEALTH, data.wealth());
        text(document, ID_BIO_DESC, data.desc());
        text(document, ID_BAG_META, NpcInventory.SLOT_COUNT + " 格");

        bar(document, ID_HP_BAR, ID_HP_NOW, ID_HP_MAX, data.health(), data.healthMax());
        bar(document, ID_HUNGER_BAR, ID_HUNGER_NOW, ID_HUNGER_MAX, data.hunger(), data.hungerMax());

        LOGGER.info("行装页数据已填入: entity={} no={}", data.entityId(), data.no());
    }

    private static void bar(Document document, String barId, String nowId, String maxId,
                            double value, double max) {
        Element bar = document.getElementById(barId);
        if (bar != null) {
            bar.setAttribute(ATTR_VALUE, format(value));
            bar.setAttribute(ATTR_MAX, format(max));
        }
        text(document, nowId, String.valueOf(Math.round(value)));
        text(document, maxId, "/" + Math.round(max));
    }

    private static String format(double value) {
        return String.valueOf((long) Math.round(value));
    }

    private static void text(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null && value != null) {
            element.setTextContent(value);
        }
    }
}
