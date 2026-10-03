package com.xy2407.kingdom.common.entity;

import com.xy2407.kingdom.ModMain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

/** 实体属性注册(common 侧)。渲染器在 client 包注册。 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class ModRegistries {

    private ModRegistries() {
    }

    @SubscribeEvent
    public static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.NPC.get(), NpcEntity.createAttributes().build());
        event.put(ModEntities.RTS_FAKE_PLAYER.get(), RtsFakePlayerEntity.createAttributes().build());
    }

    /**
     * 把市民的 24 格行装暴露成实体的物品能力。
     *
     * <p>AUI 的容器菜单走 {@code EntityDataSource}，它按服务端实体 id 取
     * {@code Capabilities.ItemHandler.ENTITY}；不注册这一条，右键打开的界面里
     * 槽位只是摆设（点不动、也不显示真实物品）。</p>
     */
    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerEntity(Capabilities.ItemHandler.ENTITY, ModEntities.NPC.get(),
                (npc, context) -> npc.getNpcInventory());
        event.registerEntity(Capabilities.ItemHandler.ENTITY_AUTOMATION, ModEntities.NPC.get(),
                (npc, context) -> npc.getNpcInventory());
    }
}
