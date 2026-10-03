package com.xy2407.kingdom.server.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** 王国指令：市民生成与测试移动 */
@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class KingdomCommands {

    private static final int MAX_SPAWN = 1000;

    private KingdomCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("kingdom").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("npc")
                                .then(Commands.literal("spawn")
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_SPAWN))
                                                .executes(KingdomCommands::spawn))))
                        .then(Commands.literal("test")
                                .then(Commands.literal("move")
                                        .then(Commands.literal("clear")
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> moveClear(ctx, Vec3Argument.getVec3(ctx, "pos")))))
                                        .then(Commands.literal("over")
                                                .executes(ctx -> moveOver(ctx, null))
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> moveOver(ctx, Vec3Argument.getVec3(ctx, "pos"))))))
                                .then(Commands.literal("highmove")
                                        .then(Commands.literal("clear")
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> highMoveClear(ctx, Vec3Argument.getVec3(ctx, "pos")))))
                                        .then(Commands.literal("over")
                                                .executes(ctx -> highMoveOver(ctx, null))
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> highMoveOver(ctx, Vec3Argument.getVec3(ctx, "pos"))))))
                        .then(Commands.literal("place")
                                .then(Commands.literal("structures")
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .suggests(KingdomCommands::suggestStructures)
                                                .executes(KingdomCommands::placeStructure))))));
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int count = IntegerArgumentType.getInteger(ctx, "count");
        ServerLevel level = player.serverLevel();
        var type = ModEntities.NPC.get();
        for (int i = 0; i < count; i++) {
            double a = level.random.nextDouble() * Math.PI * 2.0D;
            double r = 1.0D + Math.sqrt((i + 1.0D) / (double) count) * 32.0D;
            double x = player.getX() + Math.cos(a) * r;
            double z = player.getZ() + Math.sin(a) * r;
            var entity = type.create(level);
            if (entity == null) continue;
            NpcData data = new NpcData(UUID.randomUUID());
            data.setName("市民" + (i + 1));
            data.setGender(level.random.nextBoolean() ? "MALE" : "FEMALE");
            data.setEntityUuid(entity.getUUID());
            NpcManager.INSTANCE.registerNpc(data);
            entity.setCitizenId(data.getId());
            entity.moveTo(x, player.getY(), z, player.getYRot(), 0.0F);
            level.addFreshEntity(entity);
        }
        KingdomPersistence.flushSync();
        ctx.getSource().sendSuccess(() -> Component.literal("已生成 " + count + " 名市民"), true);
        return count;
    }

    private static int moveClear(CommandContext<CommandSourceStack> ctx, Vec3 pos) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos target = BlockPos.containing(pos);
        List<NpcEntity> npcs = npcs(player.serverLevel());
        for (NpcEntity npc : npcs) {
            npc.setRtsTarget(target, true);
        }
        int total = npcs.size();
        source.sendSuccess(() -> Component.literal("已派遣 " + total + " 名市民前往 " + target.toShortString() + "，抵达后清除"), true);
        return total;
    }

    private static int moveOver(CommandContext<CommandSourceStack> ctx, Vec3 pos) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos center = pos == null ? player.blockPosition() : BlockPos.containing(pos);
        List<NpcEntity> npcs = npcs(player.serverLevel());
        for (int i = 0; i < npcs.size(); i++) {
            BlockPos offset = ringOffset(i);
            npcs.get(i).setRtsTarget(center.offset(offset.getX(), 0, offset.getZ()), false);
        }
        int total = npcs.size();
        source.sendSuccess(() -> Component.literal("已派遣 " + total + " 名市民在 " + center.toShortString() + " 附近密集集结"), true);
        return total;
    }

    private static int highMoveClear(CommandContext<CommandSourceStack> ctx, Vec3 pos) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos target = BlockPos.containing(pos);
        List<NpcEntity> npcs = npcs(player.serverLevel());
        for (NpcEntity npc : npcs) {
            npc.setHighRtsTarget(target, true);
        }
        int total = npcs.size();
        source.sendSuccess(() -> Component.literal("已派遣 " + total + " 名市民以自定义A*前往 " + target.toShortString() + "，抵达后清除"), true);
        return total;
    }

    private static int highMoveOver(CommandContext<CommandSourceStack> ctx, Vec3 pos) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos center = pos == null ? player.blockPosition() : BlockPos.containing(pos);
        List<NpcEntity> npcs = npcs(player.serverLevel());
        for (int i = 0; i < npcs.size(); i++) {
            BlockPos offset = ringOffset(i);
            npcs.get(i).setHighRtsTarget(center.offset(offset.getX(), 0, offset.getZ()), false);
        }
        int total = npcs.size();
        source.sendSuccess(() -> Component.literal("已派遣 " + total + " 名市民以自定义A*在 " + center.toShortString() + " 附近密集集结"), true);
        return total;
    }

    private static int placeStructure(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.literal(
                "建筑放置已迁到 RTS：F6 进入 RTS 后按 Tab 打开建筑列表，点卡片下的 [加载] 放置投影"));
        return 0;
    }

    private static java.util.concurrent.CompletableFuture<Suggestions> suggestStructures(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String name : BuildingBlueprints.listNames()) {
            if (name.toLowerCase().startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private static List<NpcEntity> npcs(ServerLevel level) {
        List<NpcEntity> result = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof NpcEntity npc) {
                result.add(npc);
            }
        }
        return result;
    }

    private static BlockPos ringOffset(int index) {
        if (index <= 0) {
            return BlockPos.ZERO;
        }
        int ring = (int) Math.ceil((Math.sqrt(index + 1.0D) - 1.0D) / 2.0D);
        int side = 2 * ring;
        int step = index - (2 * ring - 1) * (2 * ring - 1);
        if (step < side) {
            return new BlockPos(-ring, 0, -ring + step);
        }
        if (step < 2 * side) {
            return new BlockPos(-ring + step - side, 0, ring);
        }
        if (step < 3 * side) {
            return new BlockPos(ring, 0, ring - (step - 2 * side));
        }
        return new BlockPos(ring - (step - 3 * side), 0, -ring);
    }
}