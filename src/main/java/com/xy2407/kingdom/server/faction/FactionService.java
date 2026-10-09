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
import com.xy2407.kingdom.server.mapoverlay.MapOverlayCollector;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 阵营与领地的服务端逻辑：首登弹窗、选国/建国、立领地。
 */
public final class FactionService {

    private static final Set<UUID> PROMPTED = new HashSet<>();

    static {
        FactionHooks.selectHandler = FactionService::onSelect;
        FactionHooks.territoryCreateHandler = FactionService::onCreateTerritory;
    }

    private FactionService() {
    }


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
            PlayerProfileStore.INSTANCE.setName(uuid, player.getGameProfile().getName());
            if (profile == null || profile.onboarded() || !PROMPTED.add(uuid)) {
                continue;
            }
            send(player, FactionStatePacket.state(false, "", "", "", playerNations()));
        }
        PROMPTED.retainAll(online);
    }


    private static void onSelect(ServerPlayer player, FactionSelectPacket packet) {
        PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(player.getUUID());
        if (profile == null) {
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


    private static void onCreateTerritory(ServerPlayer player, TerritoryCreatePacket packet) {
        PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(player.getUUID());
        if (profile == null) {
            return;
        }
        if (!profile.hasNation()) {
            sendState(player);
            return;
        }
        if (profile.hasTerritory() || TerritoryRegistry.INSTANCE.byOwner(player.getUUID()) != null) {
            sendState(player);
            return;
        }

        String name = packet.name().isBlank() ? "无名领地" : packet.name();
        ServerLevel level = player.serverLevel();
        BlockPos hallPos = new BlockPos(packet.hallX(), packet.hallY(), packet.hallZ());

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
        MapOverlayCollector.pushAll();
        ModMain.LOGGER.info("{} 建立了领地 {}，范围区块 X[{}..{}] Z[{}..{}]",
                player.getName().getString(), name,
                territory.minChunkX(), territory.maxChunkX(),
                territory.minChunkZ(), territory.maxChunkZ());
    }


    public static void promptCityCreate(ServerPlayer player, BlockPos hallPos) {
        PlayerProfile profile = PlayerProfileStore.INSTANCE.byUuid(player.getUUID());
        boolean onboarded = profile != null && profile.onboarded();
        String nationId = profile == null ? "" : profile.nationId();
        send(player, FactionStatePacket.openCityCreate(onboarded, nationId,
                hallPos.getX(), hallPos.getY(), hallPos.getZ(), playerNations()));
    }


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
