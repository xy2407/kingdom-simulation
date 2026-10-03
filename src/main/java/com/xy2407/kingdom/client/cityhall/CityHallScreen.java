package com.xy2407.kingdom.client.cityhall;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.screen.ApricityScreen;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 市政厅界面的宿主：右键市政厅操作台打开，页面数据由 Java 填充
 */
@OnlyIn(Dist.CLIENT)
public final class CityHallScreen extends ApricityScreen {

    public static final String PAGE = "ui/city_hall.html";

    private Document boundDocument;

    public CityHallScreen() {
        super(PAGE);
        setShowDefaultBackground(false);
        setPauseGame(false);
    }

    /** 供开屏包钩子调用，已在客户端线程上。 */
    public static void open() {
        Minecraft.getInstance().setScreen(new CityHallScreen());
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
        // TODO: 填入城镇数据（名称 / 天数 / 居民 / 金库 / 政令 / 税率）
    }
}
