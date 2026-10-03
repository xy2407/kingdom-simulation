package com.xy2407.kingdom.common.entity;

import com.xy2407.kingdom.ModMain;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 实体类型注册。 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ModMain.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<NpcEntity>> NPC =
            ENTITY_TYPES.register("npc",
                    () -> EntityType.Builder.of(NpcEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, 1.8F)
                            .build("npc"));

    public static final DeferredHolder<EntityType<?>, EntityType<RtsFakePlayerEntity>> RTS_FAKE_PLAYER =
            ENTITY_TYPES.register("rts_fake_player",
                    () -> EntityType.Builder.<RtsFakePlayerEntity>of(RtsFakePlayerEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(20)
                            .updateInterval(1)
                            .noSummon()
                            .build("rts_fake_player"));

    private ModEntities() {
    }
}