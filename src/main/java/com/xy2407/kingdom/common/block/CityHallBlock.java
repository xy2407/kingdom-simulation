package com.xy2407.kingdom.common.block;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.faction.PlayerProfile;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.network.cityhall.CityHallDataPacket;
import com.xy2407.kingdom.common.network.cityhall.CityHallOpenPacket;
import com.xy2407.kingdom.server.cityhall.CityHallDataCollector;
import com.xy2407.kingdom.server.faction.FactionService;
import com.xy2407.kingdom.server.faction.PlayerProfileStore;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 市政厅操作台：右键打开城镇管理面板
 */
public class CityHallBlock extends Block {

    public CityHallBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            /* 「有没有领地」必须**两处都查、任一为真即算有**：
               档案里的 territoryId 与领地表的 byOwner 是两份记录，谁先落盘不定。
               早先这里只查 TerritoryRegistry.byOwner——重进世界时那张表可能还没读完、
               一律返回 null，把"其实有领地"的人判成没领地，右键就被引去建城界面，
               看着就是"市政厅 UI 打不开"。onCreateTerritory 早就是双重判断，这里对齐。 */
            Territory owned = TerritoryRegistry.INSTANCE.byOwner(serverPlayer.getUUID());
            PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(serverPlayer.getUUID());
            boolean hasTerritory = owned != null || (profile != null && profile.hasTerritory());

            /* 只有"确实还没领地"且"领地表已读完"时才引去建城——表没读完不下"你没领地"的结论。
               其余一律开管理面板：那是只读的，误开也不会建出第二块地。 */
            if (!hasTerritory && TerritoryRegistry.INSTANCE.isLoaded()) {
                ModMain.LOGGER.info("[市政厅] pos={} 判定无领地 → 建城界面", pos);
                FactionService.promptCityCreate(serverPlayer, pos);
                return InteractionResult.SUCCESS;
            }
            ModMain.LOGGER.info("[市政厅] pos={} 有领地={} → 管理面板", pos, hasTerritory);

            /* **开屏包在前**：界面能否打开只取决于这一个包，先把它送出去。
               数据包在后，且单独兜异常——collect() 要读 NPC / 建筑 / 领地 / 国家四张表，
               任何一张在冷启动时没就绪都可能在半路抛异常；若按老顺序先发数据包，
               它一抛，开屏包就跟着发不出去，界面彻底打不开。 */
            PacketDistributor.sendToPlayer(serverPlayer,
                    new CityHallOpenPacket(pos.getX(), pos.getZ()));
            CityHallDataPacket data;
            try {
                data = CityHallDataCollector.collect(serverLevel, pos, serverPlayer.getUUID());
            } catch (RuntimeException failure) {
                /* 数据凑不齐不该连累界面——空数据打开，页面上会显示 "—" 与空态提示 */
                ModMain.LOGGER.warn("[市政厅] 数据收集失败，界面按空数据打开", failure);
                data = CityHallDataPacket.EMPTY;
            }
            PacketDistributor.sendToPlayer(serverPlayer, data);
        }
        return InteractionResult.SUCCESS;
    }
}
