package com.xy2407.kingdom.client.faction;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.init.Node;
import com.sighs.apricityui.screen.ApricityScreen;

import com.xy2407.kingdom.client.faction.FactionSelectPage.FactionChoice;
import com.xy2407.kingdom.common.network.faction.FactionSelectPacket;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 择旗界面的 Java 驱动：拆封时间线、卡片选择、立国建邦与实时预览
 */
@OnlyIn(Dist.CLIENT)
public final class FactionSelectScreen extends ApricityScreen {

    private static final int TICKS_LETTER_OUT = 20;
    private static final int TICKS_FADE = 82;
    private static final int TICKS_DONE = 105;
    private static final int TICKS_TOAST = 48;
    private static final int NOT_OPENED = -1;
    private static final int SLOT_COUNT = 4;

    private static final String CLASS_OPEN = "open";
    private static final String CLASS_LETTER_OUT = "letter-out";
    private static final String CLASS_FADING = "fading";
    private static final String CLASS_DONE = "done";
    private static final String CLASS_REVEALED = "revealed";
    private static final String CLASS_HIDDEN = "hidden";
    private static final String CLASS_SELECTED = "is-selected";
    private static final String CLASS_CUSTOM = "is-custom";
    private static final String CLASS_ACTIVE = FactionSelectPage.CLASS_ACTIVE;
    private static final String CLASS_EMPTY = FactionSelectPage.CLASS_EMPTY;
    private static final String CLASS_SHOW = FactionSelectPage.CLASS_SHOW;
    private static final String CLASS_MODAL_OPEN = "modal-open";
    private static final String ATTRIBUTE_DISABLED = "disabled";
    private static final String ATTRIBUTE_STYLE = "style";
    private static final String TAG_SHIELD = "kingdom-shield";
    private static final String TAG_NAME = "h3";
    private static final String CLASS_SHIELD_WRAP = "shield-wrap";
    private static final String CLASS_EMBLEM = "card-emblem";
    private static final String CLASS_DESC = "card-desc";
    private static final String ATTR_COLOR_LEFT = "c1";
    private static final String ATTR_COLOR_RIGHT = "c2";
    private static final String TEXT_UNPLEDGED = "尚未立誓";
    private static final String DEFAULT_REALM_NAME = "无名之国";
    private static final String DEFAULT_REALM_RULER = "无名君主";
    private static final String DEFAULT_REALM_MOTTO = "铁与血";
    private static final String CUSTOM_LATIN = "REG NUM";
    private static final String CUSTOM_ID_PREFIX = "realm-";
    private static final String SLOT_ID_PREFIX = "card-custom-";
    private static final String SLOT_DELETE_PREFIX = "card-del-";
    private static final String SLOT_FULL_HINT = "自建国最多 4 个";

    private final Consumer<FactionChoice> onConfirm;
    private final Map<Element, String> cards = new LinkedHashMap<>();
    private final Map<Element, String> leftSwatches = new LinkedHashMap<>();
    private final Map<Element, String> rightSwatches = new LinkedHashMap<>();
    private final Map<Element, String> crestButtons = new LinkedHashMap<>();
    private final Map<Element, String> emblemColorButtons = new LinkedHashMap<>();
    private final FactionChoice[] slotChoices = new FactionChoice[SLOT_COUNT];
    private final Slot[] slotElements = new Slot[SLOT_COUNT];

    private Document boundDocument;
    private Element envelope;
    private Element hint;
    private Element stage;
    private Element selectedName;
    private Element confirmButton;
    private Element overlay;
    private Element toast;
    private Element previewShield;
    private Element previewEmblem;
    private Element previewName;
    private Element previewRuler;
    private Element previewMotto;
    private Element inputName;
    private Element inputRuler;
    private Element inputMotto;

    private String selectedId;
    private String customColorLeft = FactionSelectPage.DEFAULT_COLOR_LEFT;
    private String customColorRight = FactionSelectPage.DEFAULT_COLOR_RIGHT;
    private String customEmblem = FactionSelectPage.DEFAULT_EMBLEM;
    private String customEmblemColor = FactionSelectPage.DEFAULT_EMBLEM_COLOR;
    private String shownName = "";
    private String shownRuler = "";
    private String shownMotto = "";
    private int realmSerial;
    private int envelopeTicks = NOT_OPENED;
    private int toastTicks;

    public FactionSelectScreen(Consumer<FactionChoice> onConfirm) {
        super(FactionSelectPage.PAGE);
        this.onConfirm = onConfirm;
        setShowDefaultBackground(false);
        setPauseGame(true);
    }

    private record Slot(Element card, Element shield, Element emblem, Element name, Element desc) {
    }

    public String getSelectedId() {
        return selectedId;
    }

    @Override
    protected void init() {
        super.init();
        bind();
    }

    private void bind() {
        Document document = getLinkedDocument();
        if (document == null || document == boundDocument) {
            return;
        }
        boundDocument = document;
        cards.clear();
        leftSwatches.clear();
        rightSwatches.clear();
        crestButtons.clear();
        emblemColorButtons.clear();

        envelope = document.getElementById(FactionSelectPage.ID_ENVELOPE);
        hint = document.getElementById(FactionSelectPage.ID_HINT);
        stage = document.getElementById(FactionSelectPage.ID_STAGE);
        selectedName = document.getElementById(FactionSelectPage.ID_SELECTED_NAME);
        confirmButton = document.getElementById(FactionSelectPage.ID_CONFIRM_BUTTON);
        toast = document.getElementById(FactionSelectPage.ID_TOAST);
        overlay = document.getElementById(FactionSelectPage.ID_OVERLAY);
        previewShield = document.getElementById(FactionSelectPage.ID_PREVIEW_SHIELD);
        previewEmblem = document.getElementById(FactionSelectPage.ID_PREVIEW_EMBLEM);
        previewName = document.getElementById(FactionSelectPage.ID_PREVIEW_NAME);
        previewRuler = document.getElementById(FactionSelectPage.ID_PREVIEW_RULER);
        previewMotto = document.getElementById(FactionSelectPage.ID_PREVIEW_MOTTO);
        inputName = document.getElementById(FactionSelectPage.ID_INPUT_NAME);
        inputRuler = document.getElementById(FactionSelectPage.ID_INPUT_RULER);
        inputMotto = document.getElementById(FactionSelectPage.ID_INPUT_MOTTO);

        if (envelope != null) {
            envelope.addEventListener("click", event -> openEnvelope());
        }
        for (FactionSelectPage.Faction faction : FactionSelectPage.factions()) {
            Element card = document.getElementById(FactionSelectPage.CARD_ID_PREFIX + faction.id());
            if (card != null) {
                bindCard(card, faction.id());
            }
        }
        bindSlots(document);
        collect(document.getElementById(FactionSelectPage.ID_SWATCH_LEFT), leftSwatches, false);
        collect(document.getElementById(FactionSelectPage.ID_SWATCH_RIGHT), rightSwatches, false);
        collect(document.getElementById(FactionSelectPage.ID_CREST_ROW), crestButtons, true);
        collect(document.getElementById(FactionSelectPage.ID_EMBLEM_COLORS), emblemColorButtons, false);
        bindPickerClicks();

        if (confirmButton != null) {
            confirmButton.addEventListener("click", event -> confirm());
        }
        Element createButton = document.getElementById(FactionSelectPage.ID_CREATE_BUTTON);
        if (createButton != null) {
            createButton.addEventListener("click", event -> openModal());
        }
        Element closeButton = document.getElementById(FactionSelectPage.ID_MODAL_CLOSE);
        if (closeButton != null) {
            closeButton.addEventListener("click", event -> closeModal());
        }
        Element cancelButton = document.getElementById(FactionSelectPage.ID_MODAL_CANCEL);
        if (cancelButton != null) {
            cancelButton.addEventListener("click", event -> closeModal());
        }
        Element forgeButton = document.getElementById(FactionSelectPage.ID_MODAL_FORGE);
        if (forgeButton != null) {
            forgeButton.addEventListener("click", event -> forge());
        }
        restoreInitialState();
    }

    private void bindCard(Element card, String factionId) {
        cards.put(card, factionId);
        card.addEventListener("click", event -> select(factionId));
    }

    private void bindSlots(Document document) {
        for (int index = 0; index < SLOT_COUNT; index++) {
            Element card = document.getElementById(SLOT_ID_PREFIX + (index + 1));
            if (card == null) {
                continue;
            }
            Element wrap = findChild(card, "div", CLASS_SHIELD_WRAP);
            Element shield = wrap == null ? null : findChild(wrap, TAG_SHIELD, null);
            slotElements[index] = new Slot(card, shield,
                    findChild(card, "span", CLASS_EMBLEM),
                    findChild(card, TAG_NAME, null),
                    findChild(card, "p", CLASS_DESC));
            int slotIndex = index;
            card.addEventListener("click", event -> {
                FactionChoice choice = slotChoices[slotIndex];
                if (choice != null) {
                    select(choice.id());
                }
            });
            Element delete = document.getElementById(SLOT_DELETE_PREFIX + (index + 1));
            if (delete != null) {
                delete.addEventListener("click", event -> deleteCustom(slotIndex));
            }
            FactionChoice choice = slotChoices[index];
            if (choice == null) {
                card.getClassList().add(CLASS_CUSTOM);
            } else {
                applySlot(index, choice);
            }
        }
    }

    private static void collect(Element container, Map<Element, String> registry, boolean textValue) {
        if (container == null) {
            return;
        }
        for (Node node : container.getChildNodes()) {
            if (!(node instanceof Element child)) {
                continue;
            }
            if (hasElementChild(child)) {
                collect(child, registry, textValue);
                continue;
            }
            String value = textValue ? child.getTextContent() : colorOf(child);
            if (value == null || value.isBlank()) {
                continue;
            }
            registry.put(child, textValue ? value.trim() : value);
        }
    }

    private static boolean hasElementChild(Element element) {
        for (Node node : element.getChildNodes()) {
            if (node instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private void bindPickerClicks() {
        for (Map.Entry<Element, String> entry : leftSwatches.entrySet()) {
            entry.getKey().addEventListener("click", event -> {
                customColorLeft = entry.getValue();
                highlight(leftSwatches, customColorLeft);
                updatePreview();
            });
        }
        for (Map.Entry<Element, String> entry : rightSwatches.entrySet()) {
            entry.getKey().addEventListener("click", event -> {
                customColorRight = entry.getValue();
                highlight(rightSwatches, customColorRight);
                updatePreview();
            });
        }
        for (Map.Entry<Element, String> entry : crestButtons.entrySet()) {
            entry.getKey().addEventListener("click", event -> {
                customEmblem = entry.getValue();
                highlight(crestButtons, customEmblem);
                updatePreview();
            });
        }
        for (Map.Entry<Element, String> entry : emblemColorButtons.entrySet()) {
            entry.getKey().addEventListener("click", event -> {
                customEmblemColor = entry.getValue();
                highlight(emblemColorButtons, customEmblemColor);
                updatePreview();
            });
        }
    }

    private void restoreInitialState() {
        envelopeTicks = NOT_OPENED;
        if (selectedName != null) {
            selectedName.setTextContent(TEXT_UNPLEDGED);
            selectedName.getClassList().add(CLASS_EMPTY);
        }
        if (confirmButton != null) {
            confirmButton.setAttribute(ATTRIBUTE_DISABLED, "true");
        }
        if (selectedId != null && (cards.containsValue(selectedId) || findSlotChoice(selectedId) != null)) {
            select(selectedId);
        } else {
            selectedId = null;
        }
    }

    private void openEnvelope() {
        if (envelopeTicks != NOT_OPENED) {
            return;
        }
        addClass(envelope, CLASS_OPEN);
        addClass(hint, CLASS_HIDDEN);
        envelopeTicks = 0;
    }

    @Override
    public void tick() {
        super.tick();
        advanceEnvelope();
        advanceToast();
        refreshPreviewFromInputs();
    }

    private void advanceEnvelope() {
        if (envelopeTicks == NOT_OPENED) {
            return;
        }
        envelopeTicks++;
        if (envelopeTicks == TICKS_LETTER_OUT) {
            addClass(envelope, CLASS_LETTER_OUT);
        } else if (envelopeTicks == TICKS_FADE) {
            addClass(stage, CLASS_FADING);
            if (boundDocument != null) {
                addClass(boundDocument.body, CLASS_REVEALED);
            }
        } else if (envelopeTicks == TICKS_DONE) {
            addClass(stage, CLASS_DONE);
            envelopeTicks = NOT_OPENED;
        }
    }

    private void advanceToast() {
        if (toastTicks <= 0) {
            return;
        }
        if (--toastTicks == 0) {
            removeClass(toast, CLASS_SHOW);
        }
    }

    private void showToast(String message) {
        if (toast == null) {
            return;
        }
        toast.setTextContent(message);
        addClass(toast, CLASS_SHOW);
        toastTicks = TICKS_TOAST;
    }

    private void select(String factionId) {
        selectedId = factionId;
        for (Map.Entry<Element, String> entry : cards.entrySet()) {
            entry.getKey().getClassList().toggle(CLASS_SELECTED, factionId.equals(entry.getValue()));
        }
        for (int index = 0; index < SLOT_COUNT; index++) {
            Slot slot = slotElements[index];
            FactionChoice choice = slotChoices[index];
            if (slot != null) {
                slot.card().getClassList().toggle(CLASS_SELECTED,
                        choice != null && factionId.equals(choice.id()));
            }
        }
        String label = nameOf(factionId);
        if (selectedName != null && label != null) {
            selectedName.setTextContent(label);
            selectedName.getClassList().remove(CLASS_EMPTY);
        }
        if (confirmButton != null) {
            confirmButton.removeAttribute(ATTRIBUTE_DISABLED);
        }
    }

    private String nameOf(String factionId) {
        FactionSelectPage.Faction faction = FactionSelectPage.byId(factionId);
        if (faction != null) {
            return faction.name();
        }
        FactionChoice choice = findSlotChoice(factionId);
        return choice == null ? null : choice.name();
    }

    private FactionChoice findSlotChoice(String factionId) {
        for (FactionChoice choice : slotChoices) {
            if (choice != null && choice.id().equals(factionId)) {
                return choice;
            }
        }
        return null;
    }

    private void confirm() {
        if (selectedId == null) {
            return;
        }
        FactionChoice choice = findSlotChoice(selectedId);
        if (choice == null) {
            choice = FactionSelectPage.presetChoice(selectedId);
        }
        if (choice == null) {
            return;
        }

        /* 真流程：把决定交给服务端落库。
           自建国的 id 只是这一侧的临时编号（realm-1、realm-2……），发出去时留空，
           由服务端统一分配、避免两个客户端撞号。 */
        boolean custom = choice.custom();
        PacketDistributor.sendToServer(new FactionSelectPacket(
                custom,
                custom ? "" : choice.id(),
                choice.name(), choice.ruler(), choice.motto(),
                choice.colorLeft(), choice.colorRight(),
                choice.emblem(), choice.emblemColor()));

        Consumer<FactionChoice> callback = onConfirm;
        onClose();
        if (callback != null) {
            callback.accept(choice);
        }
    }

    private void openModal() {
        if (overlay == null) {
            return;
        }
        customColorLeft = FactionSelectPage.DEFAULT_COLOR_LEFT;
        customColorRight = FactionSelectPage.DEFAULT_COLOR_RIGHT;
        customEmblem = FactionSelectPage.DEFAULT_EMBLEM;
        customEmblemColor = FactionSelectPage.DEFAULT_EMBLEM_COLOR;
        setInputValue(inputName, "");
        setInputValue(inputRuler, "");
        setInputValue(inputMotto, "");
        shownName = "";
        shownRuler = "";
        shownMotto = "";
        highlight(leftSwatches, customColorLeft);
        highlight(rightSwatches, customColorRight);
        highlight(crestButtons, customEmblem);
        highlight(emblemColorButtons, customEmblemColor);
        updatePreview();
        addClass(overlay, CLASS_OPEN);
        if (boundDocument != null) {
            addClass(boundDocument.body, CLASS_MODAL_OPEN);
        }
    }

    private void closeModal() {
        removeClass(overlay, CLASS_OPEN);
        if (boundDocument != null) {
            removeClass(boundDocument.body, CLASS_MODAL_OPEN);
        }
    }

    private void forge() {
        int index = freeSlot();
        if (index < 0) {
            showToast(SLOT_FULL_HINT);
            return;
        }
        String name = limit(textOfInput(inputName, DEFAULT_REALM_NAME), 10);
        String ruler = limit(textOfInput(inputRuler, DEFAULT_REALM_RULER), 12);
        String motto = limit(textOfInput(inputMotto, DEFAULT_REALM_MOTTO), 16);
        FactionChoice choice = new FactionChoice(CUSTOM_ID_PREFIX + (++realmSerial),
                name, ruler, motto, customColorLeft, customColorRight, customEmblem, customEmblemColor, true);
        slotChoices[index] = choice;
        applySlot(index, choice);
        closeModal();
        select(choice.id());
        showToast("「" + name + "」已载入编年史");
    }

    private void applySlot(int index, FactionChoice choice) {
        Slot slot = slotElements[index];
        if (slot == null) {
            return;
        }
        slot.card().getClassList().remove(CLASS_CUSTOM);
        if (slot.shield() != null) {
            slot.shield().setAttribute(ATTR_COLOR_LEFT, choice.colorLeft());
            slot.shield().setAttribute(ATTR_COLOR_RIGHT, choice.colorRight());
        }
        if (slot.emblem() != null) {
            slot.emblem().setTextContent(choice.emblem());
            slot.emblem().setAttribute(ATTRIBUTE_STYLE, "color:" + choice.emblemColor());
        }
        if (slot.name() != null) {
            slot.name().setTextContent(choice.name());
        }
        Element latin = findChild(slot.card(), "p", "card-latin");
        if (latin != null) {
            latin.setTextContent(CUSTOM_LATIN);
        }
        if (slot.desc() != null) {
            slot.desc().setTextContent(choice.motto());
        }
    }

    private void deleteCustom(int index) {
        FactionChoice choice = slotChoices[index];
        if (choice == null) {
            return;
        }
        slotChoices[index] = null;
        Slot slot = slotElements[index];
        if (slot != null) {
            slot.card().getClassList().remove(CLASS_SELECTED);
            slot.card().getClassList().add(CLASS_CUSTOM);
        }
        if (choice.id().equals(selectedId)) {
            selectedId = null;
            if (selectedName != null) {
                selectedName.setTextContent(TEXT_UNPLEDGED);
                selectedName.getClassList().add(CLASS_EMPTY);
            }
            if (confirmButton != null) {
                confirmButton.setAttribute(ATTRIBUTE_DISABLED, "true");
            }
        }
        showToast("「" + choice.name() + "」已从史册抹去");
    }

    private int freeSlot() {
        for (int index = 0; index < SLOT_COUNT; index++) {
            if (slotChoices[index] == null) {
                return index;
            }
        }
        return -1;
    }

    private void updatePreview() {
        if (previewShield != null) {
            previewShield.setAttribute(ATTR_COLOR_LEFT, customColorLeft);
            previewShield.setAttribute(ATTR_COLOR_RIGHT, customColorRight);
        }
        if (previewEmblem != null) {
            previewEmblem.setTextContent(customEmblem);
            previewEmblem.setAttribute(ATTRIBUTE_STYLE, "color:" + customEmblemColor);
        }
        refreshPreviewTexts();
    }

    private void refreshPreviewFromInputs() {
        if (overlay == null || !overlay.getClassList().contains(CLASS_OPEN)) {
            return;
        }
        refreshPreviewTexts();
    }

    private void refreshPreviewTexts() {
        String name = textOfInput(inputName, DEFAULT_REALM_NAME);
        String ruler = textOfInput(inputRuler, DEFAULT_REALM_RULER);
        String motto = textOfInput(inputMotto, DEFAULT_REALM_MOTTO);
        if (!name.equals(shownName)) {
            shownName = name;
            setTextContent(previewName, name);
        }
        if (!ruler.equals(shownRuler)) {
            shownRuler = ruler;
            setTextContent(previewRuler, ruler);
        }
        if (!motto.equals(shownMotto)) {
            shownMotto = motto;
            setTextContent(previewMotto, motto);
        }
    }

    private static void highlight(Map<Element, String> registry, String value) {
        for (Map.Entry<Element, String> entry : registry.entrySet()) {
            entry.getKey().getClassList().toggle(CLASS_ACTIVE,
                    value != null && value.equalsIgnoreCase(entry.getValue()));
        }
    }

    private static Element findChild(Element parent, String tag, String classToken) {
        if (parent == null) {
            return null;
        }
        for (Node node : parent.getChildNodes()) {
            if (!(node instanceof Element child)) {
                continue;
            }
            if (tag != null && !tag.equalsIgnoreCase(child.tagName)) {
                continue;
            }
            if (classToken != null && !child.getClassList().contains(classToken)) {
                continue;
            }
            return child;
        }
        return null;
    }

    private static String colorOf(Element element) {
        String style = element.getAttribute(ATTRIBUTE_STYLE);
        if (style == null) {
            return null;
        }
        int hash = style.indexOf('#');
        if (hash < 0) {
            return null;
        }
        String value = style.substring(hash).trim();
        int end = value.indexOf(';');
        return end < 0 ? value : value.substring(0, end);
    }

    private static String textOfInput(Element element, String fallback) {
        if (element instanceof Input input) {
            String value = input.getValue();
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback;
    }

    private static void setInputValue(Element element, String value) {
        if (element instanceof Input input) {
            input.setValue(value);
        }
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void setTextContent(Element element, String text) {
        if (element != null) {
            element.setTextContent(text);
        }
    }

    private static void addClass(Element element, String token) {
        if (element != null) {
            element.getClassList().add(token);
        }
    }

    private static void removeClass(Element element, String token) {
        if (element != null) {
            element.getClassList().remove(token);
        }
    }
}
