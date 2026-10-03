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
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市民渲染器：使用自定义模型(NpcModel)与按性别分配的市民皮肤贴图。
 *
 * <p>皮肤名来自市民数据 {@code skin}（服务端按性别与市民编号稳定分配后同步下发，
 * 客户端 {@code getSyncedSkin()} 读取），对应 {@code textures/entity/npc/<skin>.png}；
 * 读不到（名字非法或贴图缺失）时回落到默认皮肤 {@link NpcSkins#DEFAULT_SKIN}。</p>
 *
 * <p>分配按市民 UUID 取模，所以同一个市民每次进世界都是同一张脸。</p>
 */
@OnlyIn(Dist.CLIENT)
public class NpcEntityRenderer extends MobRenderer<NpcEntity, NpcModel> {

    private static final ResourceLocation DEFAULT_SKIN =
            ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID,
                    "textures/entity/npc/" + NpcSkins.DEFAULT_SKIN + ".png");
    private static final String SKIN_DIRECTORY = "textures/entity/npc/";
    /** 只缓存确实存在的贴图：这样新丢进来的 png 在资源重载后立刻生效。 */
    private static final Map<String, ResourceLocation> RESOLVED = new ConcurrentHashMap<>();

    public NpcEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new NpcModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    @Override
    public void render(NpcEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        if (entity.isSitting()) {
            poseStack.translate(0.0F, -0.5F, 0.0F);
        }
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(NpcEntity entity) {
        String assigned = entity.getSyncedSkin();
        if (assigned != null && !assigned.isBlank()) {
            return resolve(assigned);
        }
        return DEFAULT_SKIN;
    }

    /**
     * 解析皮肤名；文件不存在或名字非法时回落默认皮肤。
     *
     * <p>公开给 AUI 元素复用：界面里要按<b>皮肤名</b>直接画头像时（住户可能不在附近、
     * 实体都拿不到），走的是同一条"按名找贴图 + 找不到回落默认"的逻辑。</p>
     */
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
