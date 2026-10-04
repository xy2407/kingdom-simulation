package com.xy2407.kingdom.server.faction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.block.CityHallBlock;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.faction.Nation;
import com.xy2407.kingdom.common.faction.PlayerProfile;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.network.faction.FactionHooks;
import com.xy2407.kingdom.common.network.faction.FactionSelectPacket;
import com.xy2407.kingdom.common.network.faction.FactionStatePacket;
import com.xy2407.kingdom.common.network.faction.TerritoryCreatePacket;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 阵营与领地的服务端逻辑：首登弹窗、选国/建国、立领地。
 *
 * <p>静态块里把 {@link FactionHooks} 的两个处理器指向本类——包只负责编解码，
 * 业务全部收在这一处，跟 {@code NpcBackpackScreenDriver} 的接法一致。</p>
 */
public final class FactionService {

    /**
     * 已经弹过择旗界面的玩家，避免每个 tick 都发一次。
     *
     * <p>登出即清：玩家关掉界面没选、下次登录时会再弹一次——这是他唯一能重新选择的机会，
     * 所以不能永久记住"已提示"。</p>
     */
    private static final Set<UUID> PROMPTED = new HashSet<>();

    static {
        FactionHooks.selectHandler = FactionService::onSelect;
        FactionHooks.territoryCreateHandler = FactionService::onCreateTerritory;
    }

    private FactionService() {
    }

    // ------------------------------------------------------------ 首登驱动

    /**
     * 每 tick 扫一遍在线玩家：档案还没 onboarded 的，发一次状态包让客户端弹择旗界面。
     *
     * <p>为什么不用 {@code PlayerLoggedInEvent}：那张表是懒加载的，玩家登录那一刻
     * 数据库可能还没就绪，此时 {@code remember} 会误建一份"全新玩家"的档案、
     * 把老玩家的国家覆盖掉。改成 tick 里驱动，等 {@code isLoaded()} 为真再处理，
     * 就没有这个时间窗。</p>
     */
    public static void tick(MinecraftServer server) {
        if (server == null || !PlayerProfileStore.INSTANCE.isLoaded()) {
            return;
        }
        ServerLevel overworld = server.overworld();
        int day = DateSystem.totalDay(overworld);

        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            online.add(uuid);
            PlayerProfile profile = PlayerProfileStore.INSTANCE.remember(uuid, day);
            if (profile == null || profile.onboarded() || !PROMPTED.add(uuid)) {
                continue;
            }
            send(player, FactionStatePacket.state(false, "", "", "", playerNations()));
        }
        PROMPTED.retainAll(online);
    }

    // ------------------------------------------------------------ 选国 / 建国

    private static void onSelect(ServerPlayer player, FactionSelectPacket packet) {
        PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(player.getUUID());
        if (profile == null) {
            /* 档案还没建起来（懒加载未完成），拒绝比误记好 */
            return;
        }

        String nationId;
        String label;
        if (packet.custom()) {
            String name = packet.name().isBlank() ? "无名之国" : packet.name();
            Nation founded = NationRegistry.INSTANCE.create(
                    name, packet.ruler(), packet.motto(),
                    packet.colorLeft(), packet.colorRight(),
                    packet.emblem(), packet.emblemColor(),
                    player.getUUID(), DateSystem.totalDay(player.serverLevel()));
            nationId = founded.id();
            label = name;
            ModMain.LOGGER.info("{} 建立了国家 {}（{}）", player.getName().getString(), name, nationId);
        } else {
            if (!NationRegistry.INSTANCE.isKnown(packet.id())) {
                ModMain.LOGGER.warn("{} 提交了未知阵营 id：{}",
                        player.getName().getString(), packet.id());
                return;
            }
            nationId = packet.id();
            label = packet.id();
        }

        PlayerProfileStore.INSTANCE.markOnboarded(player.getUUID(), nationId);
        sendState(player);
        ModMain.LOGGER.info("{} 已归附 {}", player.getName().getString(), label);
    }

    // ------------------------------------------------------------ 立领地

    private static void onCreateTerritory(ServerPlayer player, TerritoryCreatePacket packet) {
        PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(player.getUUID());
        if (profile == null) {
            return;
        }
        /* 得先有国家才有领地——领地是国家下面的子集 */
        if (!profile.hasNation()) {
            sendState(player);
            return;
        }
        if (profile.hasTerritory() || TerritoryRegistry.INSTANCE.byOwner(player.getUUID()) != null) {
            /* 当前规则是一人一块，已有的不覆盖 */
            sendState(player);
            return;
        }

        String name = packet.name().isBlank() ? "无名领地" : packet.name();
        ServerLevel level = player.serverLevel();
        /* 用右键那一刻的方块坐标。早先拿 player.getBlockY() 猜，那是玩家脚下那一格，
           跟方块所在格差一格，校验永远不过——领地建不出来就是这个原因。 */
        BlockPos hallPos = new BlockPos(packet.hallX(), packet.hallY(), packet.hallZ());

        /* 客户端给的坐标不可信：必须真的是自家市政厅方块，才让它当领地中心 */
        if (!(level.getBlockState(hallPos).getBlock() instanceof CityHallBlock)) {
            ModMain.LOGGER.warn("{} 试图在非市政厅位置立领地：{}",
                    player.getName().getString(), hallPos);
            return;
        }

        Territory territory = TerritoryRegistry.INSTANCE.create(
                player.getUUID(), profile.nationId(), name,
                level.dimension().location().toString(), hallPos,
                DateSystem.totalDay(level));
        if (territory == null) {
            sendState(player);
            return;
        }

        PlayerProfileStore.INSTANCE.setTerritory(player.getUUID(), territory.id());
        sendState(player);
        ModMain.LOGGER.info("{} 建立了领地 {}，范围区块 X[{}..{}] Z[{}..{}]",
                player.getName().getString(), name,
                territory.minChunkX(), territory.maxChunkX(),
                territory.minChunkZ(), territory.maxChunkZ());
    }

    // ------------------------------------------------------------ 打开建城界面

    /**
     * 让客户端弹建城界面。由 {@code CityHallBlock} 在"右键者还没有领地"时调用。
     *
     * @param hallPos 市政厅方块位置——建城时会被当成领地中心
     */
    public static void promptCityCreate(ServerPlayer player, BlockPos hallPos) {
        PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(player.getUUID());
        boolean onboarded = profile != null && profile.onboarded();
        String nationId = profile == null ? "" : profile.nationId();
        send(player, FactionStatePacket.openCityCreate(onboarded, nationId,
                hallPos.getX(), hallPos.getY(), hallPos.getZ(), playerNations()));
    }

    // ------------------------------------------------------------ 回推

    /** 把当前状态回推给玩家（选国、建领地做完后各调一次）。 */
    public static void sendState(ServerPlayer player) {
        PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(player.getUUID());
        if (profile == null) {
            return;
        }
        String territoryName = "";
        Territory territory = TerritoryRegistry.INSTANCE.byOwner(player.getUUID());
        if (territory != null) {
            territoryName = territory.name();
        }
        send(player, FactionStatePacket.state(profile.onboarded(), profile.nationId(),
                profile.territoryId(), territoryName, playerNations()));
    }

    /** 可选的自建国列表，供客户端追加到 start 页的自建卡槽。 */
    private static List<FactionStatePacket.NationEntry> playerNations() {
        List<FactionStatePacket.NationEntry> entries = new ArrayList<>();
        for (Nation nation : NationRegistry.INSTANCE.playerNations()) {
            entries.add(new FactionStatePacket.NationEntry(
                    nation.id(), nation.name(), nation.ruler(), nation.motto(),
                    nation.colorLeft(), nation.colorRight(),
                    nation.emblem(), nation.emblemColor()));
        }
        return entries;
    }

    private static void send(ServerPlayer player, FactionStatePacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
