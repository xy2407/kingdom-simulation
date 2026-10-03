package com.xy2407.kingdom.client.aui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.Rect;
import com.xy2407.kingdom.client.npc.NpcBackpackClientState;
import com.xy2407.kingdom.client.watchdog.ClientWatchdog;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

/**
 * 行装页左侧展示框里的市民模型，效果对齐原版背包里那个"跟着鼠标转的玩家模型"。
 *
 * <p>AUI 本身没有实体渲染能力，所以这里直接借 MC 的 {@code EntityRenderDispatcher}，
 * 变换与深度处理照搬同一套已验证可用的 immediate 渲染范式
 * （见 {@link StructurePreviewElement}）：</p>
 * <ul>
 *   <li>进 MC 管线前先 {@code Base.commitDraws()} 冲掉 AUI 共享缓冲，否则会把文档里还没提交的
 *       几何用实体着色器提前冲出去，表现是整页 UI 一起消失；</li>
 *   <li>深度测试走 AUI 的状态栈并在退出时还原，否则后续 GUI 都会被深度测试挡掉；</li>
 *   <li>3D 变换用原版 {@code renderEntityInInventory} 的那一套：
 *       {@code translate(x, y, 50)} + {@code scale(s, s, -s)} + {@code rotateZ(PI)}，
 *       实体原点在其脚底，所以 y 取框内偏下的"地平线"位置。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(KingdomNpcModelElement.TAG_NAME)
public final class KingdomNpcModelElement extends Element {

    public static final String TAG_NAME = "KINGDOM-NPC-MODEL";

    /** 参考原版：模型高度约 1.8 格。 */
    private static final double MODEL_HEIGHT = 1.8D;
    /** 模型占框高的比例，上下各留余量：太大就会顶到框边，观感也笨。 */
    private static final double MODEL_FILL = 0.74D;
    /** 脚底在框内的相对高度：贴近底边，模型向上生长。 */
    private static final double FLOOR_RATIO = 0.94D;
    /** 鼠标偏离中心时的最大转身幅度（原版是 atan(dx / 40)）。 */
    private static final double LOOK_DIVISOR = 40.0D;

    public KingdomNpcModelElement(Document document) {
        super(document, TAG_NAME);
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
        Rect rect = Rect.of(this);
        switch (phase) {
            case SHADOW -> rect.drawShadow(poseStack);
            case BODY -> {
                rect.drawBody(poseStack);
                if (ClientWatchdog.isSafeMode()) {
                    // 看门狗确认卡死过之后，只保留底框：进 MC 渲染管线画 3D 实体是本元素唯一的高风险动作。
                    return;
                }
                // 进出各留一条阶段标记：万一卡在这段里，看门狗报告会直接写出阶段名与已持续时间。
                ClientWatchdog.beginPhase("kingdom-npc-model.drawEntity");
                try {
                    drawEntity(poseStack, rect);
                } finally {
                    ClientWatchdog.endPhase();
                }
            }
            case BORDER -> rect.drawBorder(poseStack);
        }
    }

    private void drawEntity(PoseStack poseStack, Rect rect) {
        Size size = rect.getBodyRectSize();
        if (size.width() <= 0 || size.height() <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        int entityId = NpcBackpackClientState.entityId();
        if (entityId < 0) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity == null) {
            return;
        }

        Position position = rect.getBodyRectPosition();
        float centerX = (float) (position.x + size.width() / 2.0D);
        float floorY = (float) (position.y + size.height() * FLOOR_RATIO);
        float scale = (float) (size.height() * MODEL_FILL / MODEL_HEIGHT);
        // 鼠标必须先转成文档坐标：centerX 是文档坐标，而 guiMouseX 是 GUI 坐标——
        // mode=gui 下两者恰好重合，fixed+scale=fit+居中后差一个缩放+偏移，
        // 混用会把转头角算到 ±90°，模型被转成侧面（画面上就是一根"柱子"）。
        Position docMouse = this.document.screenToDocumentPosition(
                new Position(guiMouseX(minecraft), 0.0D));
        float mouseOffsetX = centerX - (float) docMouse.x;
        float lookYaw = (float) Math.atan(mouseOffsetX / LOOK_DIVISOR);

        float bodyRot = 0.0F;
        float yRot = 0.0F;
        float xRot = 0.0F;
        float headRot = 0.0F;
        float headRotO = 0.0F;
        LivingEntity living = entity instanceof LivingEntity value ? value : null;
        if (living != null) {
            bodyRot = living.yBodyRot;
            yRot = living.getYRot();
            xRot = living.getXRot();
            headRot = living.yHeadRot;
            headRotO = living.yHeadRotO;
            living.yBodyRot = 180.0F + lookYaw * 20.0F;
            living.setYRot(180.0F + lookYaw * 40.0F);
            living.setXRot(0.0F);
            living.yHeadRot = living.getYRot();
            living.yHeadRotO = living.getYRot();
        }

        poseStack.pushPose();
        Base.commitDraws();
        boolean depthTestWasOn = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
        Base.pushDepthTest(true);
        RenderSystem.enableDepthTest();
        try {
            poseStack.translate(centerX, floorY, 50.0F);
            poseStack.scale(scale, scale, -scale);
            poseStack.mulPose(new Quaternionf().rotateZ((float) Math.PI));
            Lighting.setupForEntityInInventory();
            // 用独立的即时缓冲，而不是关卡共享的 bufferSource：
            // 在 AUI 文档（离屏 FBO）里往关卡缓冲塞几何会打乱它的提交时机，
            // 实测会刷 "Depth formats do not match" 这类 GL 错误并导致卡顿。
            MultiBufferSource.BufferSource buffers =
                    MultiBufferSource.immediate(new ByteBufferBuilder(1536));
            minecraft.getEntityRenderDispatcher().render(entity, 0.0D, 0.0D, 0.0D,
                    living == null ? 0.0F : living.getYRot(), 1.0F, poseStack, buffers,
                    LightTexture.FULL_BRIGHT);
            buffers.endBatch();
        } finally {
            Lighting.setupForFlatItems();
            Base.popDepthTest();
            if (!depthTestWasOn) {
                RenderSystem.disableDepthTest();
            }
            poseStack.popPose();
            if (living != null) {
                living.yBodyRot = bodyRot;
                living.setYRot(yRot);
                living.setXRot(xRot);
                living.yHeadRot = headRot;
                living.yHeadRotO = headRotO;
            }
        }
    }

    /** 把物理鼠标换算成 GUI 逻辑坐标，供"跟着鼠标转"使用。 */
    private static double guiMouseX(Minecraft minecraft) {
        double screenWidth = minecraft.getWindow().getScreenWidth();
        if (screenWidth <= 0.0D) {
            return 0.0D;
        }
        return minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / screenWidth;
    }
}
