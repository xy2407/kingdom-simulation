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
 * 建城立邦界面：在市政厅方块上立一块领地。
 *
 * <p>页面 {@code ui/city-create.html} 只有一行控件（取消 / 名称 / 确认），
 * 交互全由这里接：取消关界面，确认把名字和市政厅坐标发给服务端。</p>
 *
 * <p><b>坐标只是请求</b>：服务端会先确认那个位置真的是自家市政厅方块，再按
 * "所在区块 ±2 格"算出 5×5 的地界——领地范围不由这一侧决定。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CityCreateScreen extends ApricityScreen {

    public static final String PAGE = "ui/city-create.html";

    /** 这块领地要挂靠的市政厅方块位置（含 Y：服务端要靠它取方块来验）。 */
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

    /** 供状态包钩子调用，已在客户端线程上。 */
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
        /* 名字空着就不提交——比默默用"无名领地"更清楚，玩家知道要填 */
        if (name.isEmpty()) {
            return;
        }
        if (name.length() > TerritoryCreatePacket.MAX_NAME_LENGTH) {
            name = name.substring(0, TerritoryCreatePacket.MAX_NAME_LENGTH);
        }

        PacketDistributor.sendToServer(new TerritoryCreatePacket(name, hallX, hallY, hallZ));
        /* 不等回包就关：服务端处理完会回推一次状态，那边才是权威 */
        onClose();
    }
}
