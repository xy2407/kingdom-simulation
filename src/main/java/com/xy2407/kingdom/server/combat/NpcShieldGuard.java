package com.xy2407.kingdom.server.combat;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.entity.NpcEntity;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;

/**
 * NPC 盾牌格挡：举盾状态下每次挨打有 30% 概率真正挡下来。
 * <p>
 * 用原版 {@link LivingShieldBlockEvent}，也就是 {@code LivingEntity.hurt} 里紧跟
 * {@code LivingIncomingDamageEvent} 之后、任何伤害结算/受伤动画之前的那一步：
 * <ul>
 *   <li>挡成功：伤害归零（原版会跳过 markHurt，所以连红闪都没有），盾按「1 + 伤害」磨损，
 *       近战攻击者被原版 {@code blockUsingShield} 弹开；</li>
 *   <li>挡失败：{@code setBlocked(false)}，等价于「盾没生效」，伤害照常进。</li>
 * </ul>
 * 举盾姿态由 {@link NpcEntity#syncShieldPose(boolean)} 维护，本处理器只在受击事件里掷一次骰子，
 * 没有任何每 tick 的检测。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class NpcShieldGuard {

    /** 每次挨打触发格挡的概率。 */
    private static final float BLOCK_CHANCE = 0.3F;

    private NpcShieldGuard() {
    }

    @SubscribeEvent
    public static void onShieldBlock(LivingShieldBlockEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc)) {
            return;
        }
        // 原版判定：举着盾、正面受击、不是穿盾伤害/穿刺箭。判定不成立就没有“白白格挡”这回事
        if (!event.getOriginalBlock()) {
            return;
        }
        if (!npc.hasShield()) {
            return;
        }
        if (npc.getRandom().nextFloat() >= BLOCK_CHANCE) {
            event.setBlocked(false);
            return;
        }
        float blocked = event.getOriginalBlockedDamage();
        // 全额免疫本次伤害
        event.setBlockedDamage(blocked);
        // 耐久不在这里换算：event 的 shieldDamage 语义就是「挡下的伤害」，
        // 原版口径（≥3 才磨、1 + floor(伤害)）在 NpcEntity#hurtCurrentlyUsedShield 里算。
        event.setShieldDamage(blocked);
    }
}
