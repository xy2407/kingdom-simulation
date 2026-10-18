package com.xy2407.kingdom.mixin;

import java.util.List;

import com.xy2407.kingdom.server.collision.NpcCollisionGrid;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 用空间网格接管移动碰撞的候选收集。
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    public List<VoxelShape> getEntityCollisions(Entity entity, AABB collisionBox) {
        return NpcCollisionGrid.entityCollisions((Level) (Object) this, entity, collisionBox);
    }
}
