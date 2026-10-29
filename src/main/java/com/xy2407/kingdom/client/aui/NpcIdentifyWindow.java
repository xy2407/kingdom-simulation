package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.platform.InputConstants;
import com.sighs.apricityui.ApricityUI;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.world.WorldWindow;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcIdentifyInfo;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcTalent;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * 准星对准市民时在其侧边显示身份卡片，另提供调试用的空卡片开关。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class NpcIdentifyWindow {
    private static final String PAGE = "ui/identify.html";
    private static final double RANGE = 10.0D;
    private static final double RANGE_SQR = RANGE * RANGE;
    private static final int DISPLAY_RANGE = 12;
    private static final int HIDE_DELAY_TICKS = 10;
    private static final float CARD_SCALE = 0.00316F;
    private static final double SIDE_OFFSET = 1.0D;
    private static final double HEIGHT_RATIO = 0.55D;
    private static final double MIN_SIDE_LENGTH = 1.0E-4D;
    private static final double PARK_DISTANCE = 1024.0D;
    private static final int EMPTY_DISPLAY_RANGE = 64;

    private static final String ID_NAME = "npc-name";
    private static final String ID_NATION = "npc-nation";
    private static final String ID_AGE = "npc-age";
    private static final String ID_LIEGE = "npc-liege";
    private static final String ID_STATUS = "npc-status";
    private static final String ID_STATUS_DOT = "npc-status-dot";
    private static final String ID_POWER = "npc-power";
    private static final String ID_POWER_MAX = "npc-power-max";
    private static final String ID_WISDOM = "npc-wisdom";
    private static final String ID_WISDOM_MAX = "npc-wisdom-max";
    private static final String ID_AGILITY = "npc-agility";
    private static final String ID_AGILITY_MAX = "npc-agility-max";
    private static final String ID_TRIANGLE = "npc-triangle";
    private static final String ID_HEALTH = "npc-health";
    private static final String ID_HEALTH_MAX = "npc-health-max";
    private static final String ID_HEALTH_BAR = "npc-health-bar";
    private static final String ID_HUNGER = "npc-hunger";
    private static final String ID_HUNGER_MAX = "npc-hunger-max";
    private static final String ID_HUNGER_BAR = "npc-hunger-bar";

    private static final String ATTRIBUTE_CLASS = "class";
    private static final String CLASS_STATUS_DOT = "status-dot";
    private static final String CLASS_STATUS_DOT_UNKNOWN = "status-dot unknown";
    private static final String MAX_PREFIX = "MAX ";
    private static final String SLASH = "/";

    public static final KeyMapping SPAWN_EMPTY_CARD = new KeyMapping(
            "key.xy2407_kingdom.identify_spawn_empty",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.xy2407_kingdom"
    );

    public static final KeyMapping CLEAR_EMPTY_CARD = new KeyMapping(
            "key.xy2407_kingdom.identify_clear_empty",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            "key.categories.xy2407_kingdom"
    );

    private static WorldWindow window;
    private static NpcEntity target;
    private static int lostTicks;
    private static NpcIdentifyInfo shown;
    private static Element probe;

    private static WorldWindow emptyCard;
    private static Document emptyBound;

    private NpcIdentifyWindow() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SPAWN_EMPTY_CARD);
        event.register(CLEAR_EMPTY_CARD);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            destroy();
            return;
        }
        handleEmptyCard(player);
        NpcEntity aimed = pick(player);
        if (aimed == null) {
            if (window != null && ++lostTicks >= HIDE_DELAY_TICKS) {
                hide(player);
            }
            return;
        }
        lostTicks = 0;
        target = aimed;
        ensureWindow(player, aimed);
        refresh(aimed);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                && stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        WorldWindow identified = window;
        WorldWindow spawned = emptyCard;
        if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            if (identified != null) {
                draw(identified, event);
            }
            if (spawned != null) {
                draw(spawned, event);
            }
            return;
        }
        if (identified == null) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        NpcEntity npc = target;
        if (npc == null) {
            identified.setPosition(park(player.getEyePosition(partial)));
            return;
        }
        identified.setPosition(anchor(player, npc, partial));
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            destroy();
        }
    }

    private static void draw(WorldWindow current, RenderLevelStageEvent event) {
        Matrix4f projection = event.getProjectionMatrix();
        Matrix4f modelView = event.getModelViewMatrix();
        Matrix4f viewProjection = modelView == null ? projection : new Matrix4f(projection).mul(modelView);
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        current.render(event.getPoseStack(), viewProjection, partial);
    }

    private static void ensureWindow(LocalPlayer player, NpcEntity npc) {
        if (window != null) {
            return;
        }
        window = new WorldWindow(PAGE, anchor(player, npc, 1.0F), DISPLAY_RANGE);
        window.setScale(CARD_SCALE);
        window.setFacing(true);
        window.setDepthTest(true);
        window.setMaxDisplayDistance(DISPLAY_RANGE);
        shown = null;
        probe = null;
    }

    private static void hide(LocalPlayer player) {
        lostTicks = 0;
        target = null;
        if (window != null) {
            window.setPosition(park(player.getEyePosition()));
        }
    }

    private static void destroy() {
        lostTicks = 0;
        target = null;
        shown = null;
        probe = null;
        WorldWindow current = window;
        window = null;
        if (current != null && current.document != null) {
            ApricityUI.removeWorldWindow(current);
        }
        removeEmptyCard();
    }

    private static void handleEmptyCard(LocalPlayer player) {
        if (SPAWN_EMPTY_CARD.consumeClick()) {
            spawnEmptyCard(player);
        }
        if (CLEAR_EMPTY_CARD.consumeClick() && !RtsModeManager.isActive()) {
            removeEmptyCard();
        }
        WorldWindow card = emptyCard;
        if (card != null && card.document != null && card.document != emptyBound) {
            bindEmpty(card);
        }
    }

    public static void spawnEmptyCard(LocalPlayer player) {
        removeEmptyCard();
        WorldWindow card = new WorldWindow(PAGE, player.getEyePosition(), EMPTY_DISPLAY_RANGE);
        card.setScale(CARD_SCALE);
        card.setFacing(true);
        card.setDepthTest(true);
        card.setMaxDisplayDistance(EMPTY_DISPLAY_RANGE);
        emptyCard = card;
        bindEmpty(card);
    }

    public static void removeEmptyCard() {
        WorldWindow current = emptyCard;
        emptyCard = null;
        emptyBound = null;
        if (current != null && current.document != null) {
            ApricityUI.removeWorldWindow(current);
        }
    }

    private static void bindEmpty(WorldWindow card) {
        emptyBound = card.document;
        bind(card.document, NpcIdentifyInfo.EMPTY);
    }

    private static Vec3 park(Vec3 eye) {
        return eye.add(0.0D, PARK_DISTANCE, 0.0D);
    }

    private static NpcEntity pick(LocalPlayer player) {
        Vec3 from = player.getEyePosition();
        Vec3 direction = player.getViewVector(1.0F);
        Vec3 to = from.add(direction.scale(RANGE));
        AABB search = player.getBoundingBox().expandTowards(direction.scale(RANGE)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, from, to, search, NpcIdentifyWindow::isTargetable, RANGE_SQR);
        if (hit == null || !(hit.getEntity() instanceof NpcEntity npc)) {
            return null;
        }
        return isOccluded(player, from, hit) ? null : npc;
    }

    private static boolean isOccluded(LocalPlayer player, Vec3 from, EntityHitResult hit) {
        Vec3 target = hit.getLocation();
        BlockHitResult blockHit = player.level().clip(new ClipContext(
                from, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return blockHit.getType() != HitResult.Type.MISS
                && blockHit.getLocation().distanceToSqr(from) < target.distanceToSqr(from);
    }

    private static boolean isTargetable(Entity entity) {
        return entity instanceof NpcEntity npc && npc.isAlive() && npc.isPickable();
    }

    public static NpcEntity currentTarget() {
        return target;
    }

    private static Vec3 anchor(LocalPlayer player, NpcEntity npc, float partial) {
        Vec3 look = player.getViewVector(partial);
        double length = Math.sqrt(look.x * look.x + look.z * look.z);
        double sideX = length < MIN_SIDE_LENGTH ? 1.0D : -look.z / length;
        double sideZ = length < MIN_SIDE_LENGTH ? 0.0D : look.x / length;
        Vec3 position = npc.getPosition(partial);
        double height = position.y + npc.getBbHeight() * HEIGHT_RATIO;
        Vec3 preferred = new Vec3(
                position.x + sideX * SIDE_OFFSET, height, position.z + sideZ * SIDE_OFFSET);
        if (!isBuried(player, preferred)) {
            return preferred;
        }
        Vec3 flipped = new Vec3(
                position.x - sideX * SIDE_OFFSET, height, position.z - sideZ * SIDE_OFFSET);
        return isBuried(player, flipped) ? preferred : flipped;
    }

    private static boolean isBuried(LocalPlayer player, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        BlockState state = player.level().getBlockState(pos);
        return !state.getVisualShape(player.level(), pos, CollisionContext.empty()).isEmpty();
    }

    private static void refresh(NpcEntity npc) {
        Document document = window.document;
        if (document == null) {
            return;
        }
        Element current = document.getElementById(ID_NAME);
        if (current == null) {
            return;
        }
        if (current != probe) {
            probe = current;
            shown = null;
        }
        NpcIdentifyInfo info = profileOf(npc);
        if (info.equals(shown)) {
            return;
        }
        shown = info;
        bind(document, info);
    }

    private static void bind(Document document, NpcIdentifyInfo info) {
        if (document == null) {
            return;
        }
        text(document, ID_NAME, info.name());
        text(document, ID_NATION, info.nation());
        text(document, ID_AGE, info.ageLine());
        text(document, ID_LIEGE, info.liege());
        text(document, ID_STATUS, info.status());

        text(document, ID_POWER, info.power().currentText());
        text(document, ID_POWER_MAX, maxText(info.power()));
        text(document, ID_WISDOM, info.wisdom().currentText());
        text(document, ID_WISDOM_MAX, maxText(info.wisdom()));
        text(document, ID_AGILITY, info.agility().currentText());
        text(document, ID_AGILITY_MAX, maxText(info.agility()));

        text(document, ID_HEALTH, info.health().currentText());
        text(document, ID_HEALTH_MAX, SLASH + display(info.health().maxText()));
        text(document, ID_HUNGER, info.hunger().currentText());
        text(document, ID_HUNGER_MAX, SLASH + display(info.hunger().maxText()));

        bar(document, ID_HEALTH_BAR, info.health());
        bar(document, ID_HUNGER_BAR, info.hunger());
        triangle(document, info);
        statusDot(document, info.status());
    }

    private static NpcIdentifyInfo profileOf(NpcEntity npc) {
        double maxHealth = npc.getMaxHealth();
        if (maxHealth <= 0.0D) {
            return NpcIdentifyInfo.EMPTY;
        }
        String syncedName = npc.getSyncedName();
        return new NpcIdentifyInfo(
                syncedName == null || syncedName.isBlank() ? null : syncedName,
                null,
                null,
                null,
                null,
                null,
                talent(npc.getSyncedStrength(), NpcTalent.STRENGTH_MAX),
                talent(npc.getSyncedIntelligence(), NpcTalent.INTELLIGENCE_MAX),
                talent(npc.getSyncedAgility(), NpcTalent.AGILITY_MAX),
                npc.getSyncedStatus(),
                new NpcIdentifyInfo.Stat((double) npc.getHealth(), maxHealth),
                new NpcIdentifyInfo.Stat(npc.getSyncedHunger(), NpcData.HUNGER_MAX));
    }

    private static NpcIdentifyInfo.Stat talent(int value, int max) {
        if (value <= 0) {
            return NpcIdentifyInfo.Stat.ABSENT;
        }
        return new NpcIdentifyInfo.Stat((double) value, (double) max);
    }

    private static void text(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setTextContent(display(value));
        }
    }

    private static void bar(Document document, String id, NpcIdentifyInfo.Stat stat) {
        Element element = document.getElementById(id);
        if (element == null) {
            return;
        }
        element.setAttribute(NpcBarElement.ATTRIBUTE_VALUE, stat.currentAttribute());
        element.setAttribute(NpcBarElement.ATTRIBUTE_MAX, stat.maxAttribute());
    }

    private static void triangle(Document document, NpcIdentifyInfo info) {
        Element element = document.getElementById(ID_TRIANGLE);
        if (element == null) {
            return;
        }
        stat(element, NpcTriangleElement.ATTRIBUTE_POWER, NpcTriangleElement.ATTRIBUTE_POWER_MAX, info.power());
        stat(element, NpcTriangleElement.ATTRIBUTE_WISDOM, NpcTriangleElement.ATTRIBUTE_WISDOM_MAX, info.wisdom());
        stat(element, NpcTriangleElement.ATTRIBUTE_AGILITY, NpcTriangleElement.ATTRIBUTE_AGILITY_MAX, info.agility());
    }

    private static void stat(Element element, String valueAttribute, String maxAttribute, NpcIdentifyInfo.Stat stat) {
        element.setAttribute(valueAttribute, stat.currentAttribute());
        element.setAttribute(maxAttribute, stat.maxAttribute());
    }

    private static void statusDot(Document document, String status) {
        Element dot = document.getElementById(ID_STATUS_DOT);
        if (dot == null) {
            return;
        }
        dot.setAttribute(ATTRIBUTE_CLASS,
                status == null || status.isBlank() ? CLASS_STATUS_DOT_UNKNOWN : CLASS_STATUS_DOT);
    }

    private static String maxText(NpcIdentifyInfo.Stat stat) {
        return MAX_PREFIX + display(stat.maxText());
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? NpcIdentifyInfo.UNKNOWN : value;
    }
}
