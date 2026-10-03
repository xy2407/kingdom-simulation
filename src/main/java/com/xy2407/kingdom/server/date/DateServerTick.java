package com.xy2407.kingdom.server.date;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.xy2407.kingdom.common.date.DailyProcessor;
import com.xy2407.kingdom.common.date.DateSyncPacket;
import com.xy2407.kingdom.common.date.DateSystem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端日期驱动，仅主世界推进、广播与调试命令
 */
public final class DateServerTick {
    private static final int REQUIRED_PERMISSION = 2;
    private static int lastSyncedDay = Integer.MIN_VALUE;

    private DateServerTick() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.serverLevel().getServer();
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new DateSyncPacket(DateSystem.totalDay(overworld)));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        DateSystem.tick(overworld);
        DailyProcessor.tick(overworld);
        int totalDay = DateSystem.totalDay(overworld);
        if (totalDay != lastSyncedDay) {
            lastSyncedDay = totalDay;
            DateSyncPacket packet = new DateSyncPacket(totalDay);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom")
                .then(Commands.literal("date")
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(REQUIRED_PERMISSION))
                                .then(Commands.argument("day", IntegerArgumentType.integer(0))
                                        .executes(ctx -> setDay(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "day")))))));
    }

    private static int setDay(CommandSourceStack source, int day) {
        MinecraftServer server = source.getServer();
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return 0;
        }
        overworld.setDayTime(day * DateSystem.DAY_TICKS);
        source.sendSuccess(() -> Component.literal("日期已设置为第 " + day + " 天，欠账将自动补齐"), true);
        return 1;
    }
}
