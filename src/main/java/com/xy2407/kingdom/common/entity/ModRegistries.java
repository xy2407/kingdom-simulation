package com.xy2407.kingdom.common.entity;

import com.xy2407.kingdom.ModMain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

/**
 * 实体属性与能力注册（common 侧）：市民与假玩家的属性、市民行装的物品能力。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class ModRegistries {

    private ModRegistries() {
    }

    @SubscribeEvent
    public static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.NPC.get(), NpcEntity.createAttributes().build());
        event.put(ModEntities.RTS_FAKE_PLAYER.get(), RtsFakePlayerEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerEntity(Capabilities.ItemHandler.ENTITY, ModEntities.NPC.get(),
                (npc, context) -> npc.getNpcInventory());
        event.registerEntity(Capabilities.ItemHandler.ENTITY_AUTOMATION, ModEntities.NPC.get(),
                (npc, context) -> npc.getNpcInventory());
    }
}
