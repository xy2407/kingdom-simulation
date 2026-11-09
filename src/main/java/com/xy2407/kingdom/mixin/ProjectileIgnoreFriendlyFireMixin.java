package com.xy2407.kingdom.mixin;

import com.xy2407.kingdom.server.combat.NpcFriendlyFireGuard;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 市民射出的箭矢：在碰撞检测阶段直接跳过友军（同领地/同主），
 * 让箭真正从友军身上穿过——不是命中后取消，而是从头就不把他们当可命中目标。
 */
@Mixin(Projectile.class)
public abstract class ProjectileIgnoreFriendlyFireMixin {

    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void kingdom$ignoreFriendlyFire(Entity target, CallbackInfoReturnable<Boolean> cir) {
        Projectile self = (Projectile) (Object) this;
        if (!NpcFriendlyFireGuard.canArrowHit(self.getOwner(), target)) {
            cir.setReturnValue(false);
        }
    }
}
