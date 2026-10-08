package com.xy2407.kingdom.client.faction;

import com.sighs.apricityui.element.Input;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import com.xy2407.kingdom.common.network.faction.TerritoryCreatePacket;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建城立邦界面：在市政厅方块上立一块领地，名称与坐标发给服务端。
 */
@OnlyIn(Dist.CLIENT)
public final class CityCreateScreen extends ApricityScreen {

    public static final String PAGE = "ui/city-create.html";

    private static int hallX;
    private static int hallY;
    private static int hallZ;

    private Document boundDocument;

    public CityCreateScreen(int hallX, int hallY, int hallZ) {
        super(PAGE);
        CityCreateScreen.hallX = hallX;
        CityCreateScreen.hallY = hallY;
        CityCreateScreen.hallZ = hallZ;
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    public static void open(int hallX, int hallY, int hallZ) {
        Minecraft.getInstance().setScreen(new CityCreateScreen(hallX, hallY, hallZ));
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

        Element cancel = document.getElementById("ccCancel");
        if (cancel != null) {
            cancel.addEventListener("click", event -> onClose());
        }
        Element create = document.getElementById("ccCreate");
        if (create != null) {
            create.addEventListener("click", event -> submit());
        }
    }

    private void submit() {
        if (boundDocument == null) {
            return;
        }
        String name = "";
        Element field = boundDocument.getElementById("ccName");
        if (field instanceof Input input) {
            String value = input.getValue();
            name = value == null ? "" : value.trim();
        }
        if (name.isEmpty()) {
            return;
        }
        if (name.length() > TerritoryCreatePacket.MAX_NAME_LENGTH) {
            name = name.substring(0, TerritoryCreatePacket.MAX_NAME_LENGTH);
        }

        PacketDistributor.sendToServer(new TerritoryCreatePacket(name, hallX, hallY, hallZ));
        onClose();
    }
}
