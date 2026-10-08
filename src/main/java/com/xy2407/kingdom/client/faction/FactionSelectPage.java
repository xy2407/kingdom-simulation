package com.xy2407.kingdom.client.faction;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 择旗而誓页面的静态数据与入口：阵营清单、色板、纹章与自建国的默认值。
 */
@OnlyIn(Dist.CLIENT)
public final class FactionSelectPage {

    public static final String PAGE = "ui/start.html";

    public static final String ID_STAGE = "stage";
    public static final String ID_ENVELOPE = "envelope";
    public static final String ID_HINT = "hint";
    public static final String ID_GRID = "grid";
    public static final String ID_SELECTED_NAME = "selName";
    public static final String ID_CONFIRM_BUTTON = "btnConfirm";
    public static final String ID_CREATE_BUTTON = "btnCreate";
    public static final String ID_TOAST = "toast";
    public static final String CARD_ID_PREFIX = "card-";

    public static final String ID_OVERLAY = "overlay";
    public static final String ID_MODAL_CLOSE = "btnClose";
    public static final String ID_MODAL_CANCEL = "btnCancel";
    public static final String ID_MODAL_FORGE = "btnForge";
    public static final String ID_PREVIEW_SHIELD = "pvShield";
    public static final String ID_PREVIEW_EMBLEM = "pvEmblem";
    public static final String ID_PREVIEW_NAME = "pvName";
    public static final String ID_PREVIEW_RULER = "pvRuler";
    public static final String ID_PREVIEW_MOTTO = "pvMotto";
    public static final String ID_INPUT_NAME = "inName";
    public static final String ID_INPUT_RULER = "inRuler";
    public static final String ID_INPUT_MOTTO = "inMotto";
    public static final String ID_SWATCH_LEFT = "sw1";
    public static final String ID_SWATCH_RIGHT = "sw2";
    public static final String ID_CREST_ROW = "crestRow";
    public static final String ID_EMBLEM_COLORS = "emblemColors";

    public static final String CLASS_EMPTY = "empty";
    public static final String CLASS_ACTIVE = "active";
    public static final String CLASS_OPEN = "open";
    public static final String CLASS_SHOW = "show";

    public static final List<String> SWATCHES = List.of(
            "#7a1414", "#8b1a1a", "#b8860b", "#c9a227",
            "#1f4d8b", "#2b2b6b", "#2f6b3a", "#1f6b6b",
            "#4b2f6b", "#5c1f2b", "#6b3a1f", "#1a1a1a",
            "#e8d8b0", "#d4d4d4"
    );

    public static final List<String> CRESTS = List.of(
            "★", "☆", "◆", "◇", "●", "○", "■", "□",
            "▲", "△", "†", "※", "♥", "◎", "〓", "⊕"
    );

    public static final List<String> EMBLEM_COLORS = List.of(
            "#ffffff", "#e8d8b0", "#d4d4d4", "#1a1a1a",
            "#f5e08a", "#c9a227", "#b8860b", "#d4433a",
            "#7a1414", "#e8a04a", "#8ab84a", "#2f6b3a",
            "#4a90d9", "#1f4d8b", "#7b5ea7", "#6b3a1f"
    );

    public static final String DEFAULT_COLOR_LEFT = SWATCHES.get(0);
    public static final String DEFAULT_COLOR_RIGHT = SWATCHES.get(12);
    public static final String DEFAULT_EMBLEM = CRESTS.get(0);
    public static final String DEFAULT_EMBLEM_COLOR = EMBLEM_COLORS.get(0);

    private static final Map<String, Faction> FACTIONS = buildFactions();

    private FactionSelectPage() {
    }

    public static void open(Consumer<FactionChoice> onConfirm) {
        Minecraft.getInstance().setScreen(new FactionSelectScreen(onConfirm));
    }

    public static Collection<Faction> factions() {
        return FACTIONS.values();
    }

    public static Faction byId(String id) {
        return id == null ? null : FACTIONS.get(id);
    }

    public static FactionChoice presetChoice(String id) {
        Faction faction = byId(id);
        if (faction == null) {
            return null;
        }
        return new FactionChoice(faction.id(), faction.name(), "", "", "", "",
                faction.emblem(), "", false);
    }

    private static Map<String, Faction> buildFactions() {
        Map<String, Faction> factions = new LinkedHashMap<>();
        add(factions, "valarian", "瓦拉里安", "VALARIA", "✠");
        add(factions, "bohemian", "波西米亚", "BOHEMIA", "✦");
        add(factions, "caladrian", "卡拉德里安", "CALADRIA", "⚜");
        add(factions, "numerian", "努梅里安", "NUMERIA", "✧");
        add(factions, "visgothian", "维斯哥特", "VISGOTHIA", "☩");
        add(factions, "galician", "加利西亚", "GALICIA", "♆");
        add(factions, "lancastian", "兰卡斯特", "LANCASTIA", "✠");
        add(factions, "hospitaller", "医院骑士", "HOSPITALIS", "✚");
        add(factions, "templar", "圣殿骑士", "TEMPLUM", "✝");
        add(factions, "barathian", "巴拉提安", "BARATHIA", "❖");
        add(factions, "orleanian", "奥尔良", "ORLEANIA", "⚜");
        add(factions, "burgundian", "勃艮第", "BURGUNDIA", "★");
        return factions;
    }

    private static void add(Map<String, Faction> factions, String id, String name, String latin, String emblem) {
        factions.put(id, new Faction(id, name, latin, emblem));
    }

    public record Faction(String id, String name, String latin, String emblem) {
    }

    public record FactionChoice(String id, String name, String ruler, String motto,
                                String colorLeft, String colorRight,
                                String emblem, String emblemColor, boolean custom) {
    }
}
