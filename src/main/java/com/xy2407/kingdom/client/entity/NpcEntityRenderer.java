package com.xy2407.kingdom.client.entity;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcSkins;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市民渲染器：用自定义模型与按 UUID 稳定分配的市民皮肤贴图绘制市民。
 */
@OnlyIn(Dist.CLIENT)
public class NpcEntityRenderer extends MobRenderer<NpcEntity, NpcModel> {

    private static final ResourceLocation DEFAULT_SKIN =
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "textures/entity/npc/" + NpcSkins.DEFAULT_SKIN + ".png");
    private static final String SKIN_DIRECTORY = "textures/entity/npc/";
    private static final Map<String, ResourceLocation> RESOLVED = new ConcurrentHashMap<>();

    public NpcEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new NpcModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.0F);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public void render(NpcEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        setAnimSkip(entity);
        if (entity.isSitting()) {
            poseStack.translate(0.0F, -0.5F, 0.0F);
        }
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    // 姿态更新降频：远处 NPC 隔帧/隔四帧才算姿态动画，位置插值照常
    private void setAnimSkip(NpcEntity entity) {
        double distSqr = this.entityRenderDispatcher.camera.getPosition()
                .distanceToSqr(entity.position());
        boolean skip;
        if (distSqr > 80.0D * 80.0D) {
            skip = entity.tickCount % 4 != 0;
        } else if (distSqr > 32.0D * 32.0D) {
            skip = entity.tickCount % 2 != 0;
        } else {
            skip = false;
        }
        this.model.setSkipAnim(skip);
    }

    @Override
    public ResourceLocation getTextureLocation(NpcEntity entity) {
        String assigned = entity.getSyncedSkin();
        if (assigned != null && !assigned.isBlank()) {
            return resolve(assigned);
        }
        return DEFAULT_SKIN;
    }

    public static ResourceLocation resolve(String skin) {
        ResourceLocation known = RESOLVED.get(skin);
        if (known != null) {
            return known;
        }
        ResourceLocation location = buildLocation(skin);
        if (location == null || !exists(location)) {
            return DEFAULT_SKIN;
        }
        RESOLVED.put(skin, location);
        return location;
    }

    private static ResourceLocation buildLocation(String skin) {
        String cleaned = skin.trim().toLowerCase(Locale.ROOT);
        if (!cleaned.matches("[a-z0-9_/\\-]+")) {
            return null;
        }
        try {
            return ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, SKIN_DIRECTORY + cleaned + ".png");
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean exists(ResourceLocation location) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getResourceManager() != null
                && minecraft.getResourceManager().getResource(location).isPresent();
    }
}
