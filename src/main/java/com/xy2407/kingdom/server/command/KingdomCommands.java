package com.xy2407.kingdom.server.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcFarmService;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.ranch.RanchPenManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 王国指令入口：市民生成、测试移动，以及地图与农田、养殖的调试命令。 */
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
                                                .executes(KingdomCommands::placeStructure))))
                        .then(mapCommand())
                        .then(farmCommand())
                        .then(ranchCommand())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mapCommand() {
        return Commands.literal("map")
                .then(Commands.literal("export")
                        .executes(ctx -> exportMap(ctx, 128)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> farmCommand() {
        return Commands.literal("farm")
                .executes(KingdomCommands::farmReport);
    }

    private static int farmReport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        source.sendSuccess(() -> Component.literal("—— 农田诊断 ——"), false);
        for (String line : NpcFarmService.debugReport(player.serverLevel())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> ranchCommand() {
        return Commands.literal("ranch")
                .then(Commands.literal("keeper")
                        .executes(KingdomCommands::hireKeeper));
    }

    private static int hireKeeper(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BuildingRecord building = BuildingRegistry.INSTANCE.locate(player);
        if (building == null) {
            source.sendFailure(Component.literal("这里没有已完工的建筑"));
            return 0;
        }
        List<RanchPen> pens = RanchPenManager.INSTANCE.pensOf(building.id());
        if (pens.isEmpty()) {
            source.sendFailure(Component.literal(building.name() + " 不是养殖建筑（蓝图 json 里没有 animals）"));
            return 0;
        }
        NpcData nearest = nearestCitizen(player);
        if (nearest == null) {
            source.sendFailure(Component.literal("这个维度还没有市民，先用 /kingdom npc spawn 生成"));
            return 0;
        }
        for (RanchPen pen : pens) {
            RanchPenManager.INSTANCE.update(pen.withKeeperIds(List.of(nearest.getId())));
        }
        source.sendSuccess(() -> Component.literal("已指派 " + nearest.getName() + " 担任 "
                + pens.size() + " 栏的饲养员"), true);
        return pens.size();
    }

    private static NpcData nearestCitizen(ServerPlayer player) {
        String dimension = player.level().dimension().location().toString();
        NpcData best = null;
        double bestDistance = Double.MAX_VALUE;
        for (NpcData npc : NpcManager.INSTANCE.allNpcs()) {
            if (npc.isDead() || !dimension.equals(npc.getDimension())) {
                continue;
            }
            double dx = npc.getX() - player.getX();
            double dy = npc.getY() - player.getY();
            double dz = npc.getZ() - player.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = npc;
            }
        }
        return best;
    }

    private static int exportMap(CommandContext<CommandSourceStack> ctx, int radius)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();

        long started = System.nanoTime();
        WorldSurfaceSampler.Snapshot snapshot =
                WorldSurfaceSampler.sample(level, center.getX(), center.getZ(), radius);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        int size = snapshot.size();
        var image = new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, size, size, false);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int packed = snapshot.colors()[z * size + x];
                int abgr = packed == WorldSurfaceSampler.UNEXPLORED
                        ? 0xFF000000
                        : MapColor.getColorFromPackedId(packed);
                image.setPixelRGBA(x, z, abgr);
            }
        }

        Path dir = FMLPaths.GAMEDIR.get().resolve("kingdom");
        Path file = dir.resolve("map_preview.png");
        try {
            Files.createDirectories(dir);
            image.writeToFile(file);
        } catch (IOException exception) {
            image.close();
            source.sendFailure(Component.literal("地图导出失败：" + exception.getMessage()));
            return 0;
        }
        image.close();

        int explored = snapshot.exploredCount();
        int total = size * size;
        source.sendSuccess(() -> Component.literal(
                "地图已导出：" + file
                        + "　边长 " + size
                        + "　已探索 " + explored + "/" + total
                        + "　耗时 " + elapsedMs + "ms"), false);
        return explored;
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