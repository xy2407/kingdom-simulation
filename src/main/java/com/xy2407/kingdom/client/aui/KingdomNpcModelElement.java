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
 * 行装页展示框里的市民模型，跟随鼠标转向，通过原版实体渲染器绘制。
 */
@OnlyIn(Dist.CLIENT)
@ElementRegister(KingdomNpcModelElement.TAG_NAME)
public final class KingdomNpcModelElement extends Element {

    public static final String TAG_NAME = "KINGDOM-NPC-MODEL";

    private static final double MODEL_HEIGHT = 1.8D;
    private static final double MODEL_FILL = 0.74D;
    private static final double FLOOR_RATIO = 0.94D;
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
                    return;
                }
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

    private static double guiMouseX(Minecraft minecraft) {
        double screenWidth = minecraft.getWindow().getScreenWidth();
        if (screenWidth <= 0.0D) {
            return 0.0D;
        }
        return minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / screenWidth;
    }
}
