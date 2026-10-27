package com.xy2407.kingdom.client.rts;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.UUID;

/** 客户端按 UUID 找实体的兜底：ClientLevel 的 getEntities() 是 protected，只能遍历渲染列表。 */
@OnlyIn(Dist.CLIENT)
public final class ClientEntities {

    private ClientEntities() {
    }

    public static Entity byUuid(ClientLevel level, UUID id) {
        if (level == null || id == null) {
            return null;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (id.equals(entity.getUUID())) {
                return entity;
            }
        }
        return null;
    }
}
