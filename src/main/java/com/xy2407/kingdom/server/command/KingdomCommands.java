package com.xy2407.kingdom.server.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.building.IndustryKind;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.mining.MiningPlot;
import com.xy2407.kingdom.common.entity.ModEntities;
import com.xy2407.kingdom.common.entity.NpcEntity;
import com.xy2407.kingdom.common.map.WorldSurfaceSampler;
import com.xy2407.kingdom.common.network.debug.NpcPathDebugBroadcast;
import com.xy2407.kingdom.common.network.debug.NpcPathDebugPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcFarmService;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.common.npc.NpcTalent;
import com.xy2407.kingdom.common.path.ChunkSnapshot;
import com.xy2407.kingdom.common.path.NpcPathfinder;
import com.xy2407.kingdom.common.path.SnapshotRepository;
import com.xy2407.kingdom.common.persistence.KingdomPersistence;
import com.xy2407.kingdom.common.ranch.RanchPen;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.faction.MoneyService;
import com.xy2407.kingdom.server.mining.MiningPlotManager;
import com.xy2407.kingdom.server.employment.EmploymentService;
import com.xy2407.kingdom.server.path.UnitSnapshotStore;
import com.xy2407.kingdom.server.road.RoadStore;
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
import net.minecraft.world.level.block.state.BlockState;
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
    private static final byte BLOCKED_MOVE = 4;

    private KingdomCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("kingdom").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("npc")
                                .then(Commands.literal("spawn")
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_SPAWN))
                                                .executes(ctx -> spawn(ctx, false))
                                                .then(Commands.literal("around").executes(ctx -> spawn(ctx, false)))
                                                .then(Commands.literal("together").executes(ctx -> spawn(ctx, true)))))
                                .then(Commands.literal("why")
                                        .executes(KingdomCommands::npcWhy)))
                        .then(Commands.literal("test")
                                .then(Commands.literal("move")
                                        .then(Commands.literal("clear")
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> moveClear(ctx, Vec3Argument.getVec3(ctx, "pos")))))
                                        .then(Commands.literal("over")
                                                .executes(ctx -> moveOver(ctx, null))
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> moveOver(ctx, Vec3Argument.getVec3(ctx, "pos"))))))
                                .then(Commands.literal("trace")
                                        .executes(ctx -> moveTrace(ctx, null))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> moveTrace(ctx, Vec3Argument.getVec3(ctx, "pos")))))

                                .then(Commands.literal("highmove")
                                        .then(Commands.literal("clear")
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> highMoveClear(ctx, Vec3Argument.getVec3(ctx, "pos")))))
                                        .then(Commands.literal("over")
                                                .executes(ctx -> highMoveOver(ctx, null))
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> highMoveOver(ctx, Vec3Argument.getVec3(ctx, "pos"))))))
                                .then(Commands.literal("lumber")
                                        .executes(KingdomCommands::lumberTest))
                                .then(Commands.literal("buildrequire")
                                        .then(Commands.literal("true")
                                                .executes(ctx -> buildRequire(ctx, true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> buildRequire(ctx, false))))
                        .then(Commands.literal("place")
                                .then(Commands.literal("structures")
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .suggests(KingdomCommands::suggestStructures)
                                                .executes(KingdomCommands::placeStructure))))
                        .then(mapCommand())
                        .then(farmCommand())
                        .then(ranchCommand())
                        .then(mineCommand())
                        .then(snapshotCommand())
                        .then(fundsCommand())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mineCommand() {
        return Commands.literal("mine")
                .then(Commands.literal("create")
                        .executes(ctx -> mineCreate(ctx, 8))
                        .then(Commands.argument("half", IntegerArgumentType.integer(2, 32))
                                .executes(ctx -> mineCreate(ctx, IntegerArgumentType.getInteger(ctx, "half")))))
                .then(Commands.literal("pause").executes(ctx -> mineSetStatus(ctx, MiningPlot.STATUS_PAUSED)))
                .then(Commands.literal("resume").executes(ctx -> mineSetStatus(ctx, MiningPlot.STATUS_RUNNING)))
                .then(Commands.literal("status").executes(KingdomCommands::mineStatus));
    }

    private static int mineCreate(CommandContext<CommandSourceStack> ctx, int half)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BuildingRecord building = nearestMiningBuilding(player);
        if (building == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "附近没有采矿分类的工业建筑——先给蓝图 json 填 industry_kind=采矿 再建造"));
            return 0;
        }
        BlockPos min = new BlockPos(player.getBlockX() - half, level.getMinBuildHeight(),
                player.getBlockZ() - half);
        BlockPos max = new BlockPos(player.getBlockX() + half, player.getBlockY() + 8,
                player.getBlockZ() + half);
        int surfaceY = Integer.MIN_VALUE;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                surfaceY = Math.max(surfaceY,
                        WorldSurfaceSampler.walkableSurfaceY(level, x, z, WorldSurfaceSampler.NO_WALKABLE));
            }
        }
        if (surfaceY == Integer.MIN_VALUE) {
            ctx.getSource().sendFailure(Component.literal("框选范围内没有可站立的地表"));
            return 0;
        }
        NpcData worker = nearestCitizen(player);
        List<UUID> workers = worker == null ? List.of() : List.of(worker.getId());
        if (worker != null) {
            EmploymentService.INSTANCE.hire(ctx.getSource().getServer(), worker.getId(),
                    com.xy2407.kingdom.common.employment.EmploymentTarget.building(
                            building.id(), "", worker.getShift()));
        }
        int spanX = max.getX() - min.getX() + 1;
        int spanZ = max.getZ() - min.getZ() + 1;
        long[] rawCells = new long[Math.max(0, spanX * spanZ)];
        int cellIndex = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                rawCells[cellIndex++] = BlockPos.asLong(x, surfaceY, z);
            }
        }
        MiningPlot plot = MiningPlotManager.INSTANCE.create(building, "矿场",
                min, max, surfaceY, workers, DateSystem.totalDay(level),
                MiningPlot.normalize(rawCells));
        if (plot == null) {
            ctx.getSource().sendFailure(Component.literal("矿场创建失败"));
            return 0;
        }
        int surface = surfaceY;
        ctx.getSource().sendSuccess(() -> Component.literal("矿场已建立：范围 "
                + min.getX() + "," + min.getZ() + " ~ " + max.getX() + "," + max.getZ()
                + "　地表 y=" + surface
                + "　采矿工=" + (worker == null ? "未指派" : worker.getName())), true);
        return 1;
    }

    private static int mineSetStatus(CommandContext<CommandSourceStack> ctx, String status)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MiningPlot plot = nearestPlot(player);
        if (plot == null) {
            ctx.getSource().sendFailure(Component.literal("附近没有矿场"));
            return 0;
        }
        MiningPlotManager.INSTANCE.update(plot.withStatus(status));
        ctx.getSource().sendSuccess(() -> Component.literal(
                "矿场 " + plot.name() + " → " + status), true);
        return 1;
    }

    private static int mineStatus(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MiningPlot plot = nearestPlot(player);
        if (plot == null) {
            ctx.getSource().sendFailure(Component.literal("附近没有矿场"));
            return 0;
        }
        int total = plot.layerSize();
        ctx.getSource().sendSuccess(() -> Component.literal("—— 矿场 " + plot.name() + " ——"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("状态=" + plot.status()
                + "　当前层 y=" + plot.currentY() + "　本层进度 " + plot.cursor() + "/" + total
                + "　地表 y=" + plot.surfaceY()), false);
        ctx.getSource().sendSuccess(() -> Component.literal("采矿工="
                + (plot.workerIds().isEmpty() ? "未指派" : plot.workerIds().size() + " 名")), false);
        for (UUID workerId : plot.workerIds()) {
            NpcData worker = NpcManager.INSTANCE.getNpc(workerId);
            if (worker == null) {
                continue;
            }
            boolean mining = worker.getBrain().isMiningControlled();
            ctx.getSource().sendSuccess(() -> Component.literal("  " + worker.getName()
                    + " 控制位=" + mining
                    + " 位置=" + (int) worker.getX() + "," + (int) worker.getY() + "," + (int) worker.getZ()
                    + " 岗位=" + (worker.getWorkplaceId() == null ? "无" : worker.getWorkplaceId().substring(0, 8))), false);
        }
        return 1;
    }

    private static BuildingRecord nearestMiningBuilding(ServerPlayer player) {
        String dimension = player.level().dimension().location().toString();
        BuildingRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingRecord building : BuildingRegistry.INSTANCE.all()) {
            if (!dimension.equals(building.dimension())) {
                continue;
            }
            BuildingMeta meta = BuildingRegistry.INSTANCE.metaOf(building);
            if (meta == null || meta.industryKind() != IndustryKind.MINING) {
                continue;
            }
            double distance = building.min().distSqr(player.blockPosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = building;
            }
        }
        return best;
    }

    private static MiningPlot nearestPlot(ServerPlayer player) {
        MiningPlot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MiningPlot plot : MiningPlotManager.INSTANCE.all()) {
            if (!plot.dimension().equals(player.level().dimension().location().toString())) {
                continue;
            }
            double distance = plot.min().distSqr(player.blockPosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = plot;
            }
        }
        return best;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> fundsCommand() {
        return Commands.literal("funds")
                .then(Commands.literal("add")
                        .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                .executes(ctx -> fundsChange(ctx, FundsOp.ADD))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(ctx -> fundsChange(ctx, FundsOp.REMOVE))))
                .then(Commands.literal("set")
                        .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                .executes(ctx -> fundsChange(ctx, FundsOp.SET))));
    }

    private enum FundsOp { ADD, REMOVE, SET }

    private static int fundsChange(CommandContext<CommandSourceStack> ctx, FundsOp op)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long amount = LongArgumentType.getLong(ctx, "amount");
        java.util.UUID uuid = player.getUUID();
        MoneyService.Result result = switch (op) {
            case ADD -> MoneyService.credit(uuid, amount);
            case REMOVE -> MoneyService.debit(uuid, amount);
            case SET -> MoneyService.set(uuid, amount);
        };
        String message = result.success()
                ? "资金已更新，当前余额 " + result.balance()
                : "操作失败（余额不足）";
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return result.success() ? 1 : 0;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> snapshotCommand() {
        return Commands.literal("snapshot").executes(KingdomCommands::snapshotReport);
    }

    private static int snapshotReport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        source.sendSuccess(() -> Component.literal("—— 单元快照存储 ——"), false);
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        source.sendSuccess(() -> Component.literal("玩家：" + dimension + "  区块[" + chunkX + ","
                + chunkZ + "]  坐标 " + player.getBlockX() + "," + player.getBlockY()
                + "," + player.getBlockZ()), false);
        int[] window = UnitSnapshotStore.INSTANCE.windowOf(dimension);
        int playerLayer = player.getBlockY() >> 4;
        source.sendSuccess(() -> Component.literal("玩家所在层=" + playerLayer + "  y "
                + (playerLayer << 4) + ".." + ((playerLayer << 4) + 15)), false);
        source.sendSuccess(() -> Component.literal("领地活动窗口：" + (window == null ? "未设置"
                : ("层 " + window[0] + ".." + window[1] + "  y "
                + (window[0] << 4) + ".." + ((window[1] << 4) + 15)))
                + "（仅用于建筑按需预热，玩家窗口始终全高）"), false);
        int minLayer = window == null ? level.getMinBuildHeight() >> 4 : window[0];
        int maxLayer = window == null ? (level.getMaxBuildHeight() - 1) >> 4 : window[1];
        int built = 0;
        int total = 0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int layer = minLayer; layer <= maxLayer; layer++) {
                    total++;
                    if (UnitSnapshotStore.INSTANCE.peek(dimension, chunkX + dx, layer, chunkZ + dz) != null) {
                        built++;
                    }
                }
            }
        }
        int builtFinal = built;
        int totalFinal = total;
        source.sendSuccess(() -> Component.literal("玩家窗口（±4 区块）：单元 " + builtFinal + "/"
                + totalFinal + " 已构建"
                + (totalFinal == 0 ? "" : String.format("（%.0f%%）", builtFinal * 100.0D / totalFinal))), false);
        for (String line : UnitSnapshotStore.INSTANCE.report()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        source.sendSuccess(() -> Component.literal("道路格总数=" + RoadStore.INSTANCE.size()), false);
        try {
            Integer rows = KingdomPersistence.querySync(connection -> {
                try (var st = connection.createStatement();
                     var rs = st.executeQuery("SELECT COUNT(*) FROM snapshot")) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            });
            source.sendSuccess(() -> Component.literal("snapshot 表行数=" + rows), false);
        } catch (Exception ignored) {
            source.sendSuccess(() -> Component.literal("snapshot 表不可用（持久层未就绪）"), false);
        }
        return 1;
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

    private static int npcWhy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        source.sendSuccess(() -> Component.literal("—— 市民作息诊断 ——"), false);
        for (String line : NpcManager.INSTANCE.debugReport(player)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
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
        String conflict = EmploymentService.INSTANCE.conflictAt(nearest, building.id());
        if (conflict != null) {
            source.sendFailure(Component.literal(nearest.getName() + " " + conflict + "，无法指派"));
            return 0;
        }
        for (RanchPen pen : pens) {
            EmploymentService.INSTANCE.hire(source.getServer(), nearest.getId(),
                    com.xy2407.kingdom.common.employment.EmploymentTarget.ranch(
                            pen.buildingId(), pen.id()));
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
                int argb = snapshot.colors()[z * size + x];
                int abgr = argb == WorldSurfaceSampler.UNEXPLORED
                        ? 0xFF221A12
                        : (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
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

    private static int buildRequire(CommandContext<CommandSourceStack> ctx, boolean value) {
        com.xy2407.kingdom.common.building.BuildRequirements.setRequired(value);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "建造材料需求已" + (value ? "开启" : "关闭")
                        + (value ? "：开工与施工需要备齐方块材料" : "：恢复免料建造")), true);
        return 1;
    }

    private static int lumberTest(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("必须由玩家执行"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int radius = 20;
        List<Long> cells = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                cells.add(BlockPos.asLong(x, y, z));
            }
        }
        List<BlockPos> trees = com.xy2407.kingdom.server.lumber.LumberSurveyService
                .scanCells(level, cells);
        if (trees.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "半径 20 内没扫到可砍的树（需要：该列有原木、原木下面是空气/非原木、树干顶上一格是自然树叶）"));
            return 0;
        }
        com.xy2407.kingdom.common.entity.NpcEntity npc =
                com.xy2407.kingdom.server.lumber.NpcLumberService.nearestWorker(level, center, 64.0D);
        if (npc == null) {
            ctx.getSource().sendFailure(Component.literal("附近 64 格内没有市民"));
            return 0;
        }
        com.xy2407.kingdom.server.lumber.NpcLumberService.clearQueue(npc);
        for (BlockPos tree : trees) {
            com.xy2407.kingdom.server.lumber.NpcLumberService.appendTree(npc, tree);
        }
        com.xy2407.kingdom.server.lumber.NpcLumberService.forceActive(npc);
        String who = npc.getName().getString();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "伐木测试：扫描 " + cells.size() + " 格，找到 " + trees.size()
                        + " 棵树，已交给最近的市民 " + who), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "伐木状态：" + com.xy2407.kingdom.server.lumber.NpcLumberService.debugState(npc)), false);
        return trees.size();
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, boolean together)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int count = IntegerArgumentType.getInteger(ctx, "count");
        ServerLevel level = player.serverLevel();
        var type = ModEntities.NPC.get();
        for (int i = 0; i < count; i++) {
            double x;
            double z;
            if (together) {
                x = player.getX();
                z = player.getZ();
            } else {
                double a = level.random.nextDouble() * Math.PI * 2.0D;
                double r = 1.0D + Math.sqrt((i + 1.0D) / (double) count) * 32.0D;
                x = player.getX() + Math.cos(a) * r;
                z = player.getZ() + Math.sin(a) * r;
            }
            var entity = type.create(level);
            if (entity == null) continue;
            NpcData data = new NpcData(UUID.randomUUID());
            data.setName("市民" + (i + 1));
            data.setGender(level.random.nextBoolean() ? "MALE" : "FEMALE");
            NpcTalent.roll(data, level.random);
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

    private static int moveTrace(CommandContext<CommandSourceStack> ctx, Vec3 pos) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos requested = pos == null ? player.blockPosition() : BlockPos.containing(pos);

        SnapshotRepository repository = SnapshotRepository.get(level.getServer());
        if (repository == null) {
            source.sendFailure(Component.literal("寻路仓库尚未初始化，稍后再试"));
            return 0;
        }
        NpcEntity npc = nearestNpc(player);
        if (npc == null) {
            source.sendFailure(Component.literal("附近没有市民"));
            return 0;
        }

        long startedAt = System.nanoTime();
        NpcPathfinder.DebugTrace trace = NpcPathfinder.trace(repository, level,
                npc.blockPosition(), requested, NpcPathfinder.paddingOf(0));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        source.sendSuccess(() -> Component.literal("A* 耗时 " + elapsedMs + "ms（同步计算）"), false);

        for (String line : describeTrace(npc, trace, level)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        highlight(player, npc, trace);

        if (trace.waypoints().isEmpty()) {
            return 0;
        }
        npc.setHighRtsTarget(requested, false);
        npc.applyPath(trace.waypoints());
        source.sendSuccess(() -> Component.literal("已开始执行，共 " + trace.waypoints().size() + " 个航点"), true);
        return 1;
    }

    private static void highlight(ServerPlayer player, NpcEntity npc, NpcPathfinder.DebugTrace trace) {
        List<NpcPathDebugPacket.Entry> entries = new ArrayList<>();
        if (trace.waypoints().isEmpty()) {
            List<BlockPos> nearest = trace.nearest();
            for (int i = 0; i < nearest.size(); i++) {
                entries.add(new NpcPathDebugPacket.Entry(nearest.get(i), BLOCKED_MOVE, i));
            }
        } else {
            List<NpcPathfinder.Waypoint> waypoints = trace.waypoints();
            for (int i = 0; i < waypoints.size(); i++) {
                NpcPathfinder.Waypoint waypoint = waypoints.get(i);
                entries.add(new NpcPathDebugPacket.Entry(waypoint.pos(),
                        (byte) waypoint.move().ordinal(), i));
            }
        }
        NpcPathDebugBroadcast.sendTrace(player, npc.getUUID(), entries);
    }

    private static NpcEntity nearestNpc(ServerPlayer player) {
        NpcEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (NpcEntity npc : npcs(player.serverLevel())) {
            double distance = npc.distanceToSqr(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = npc;
            }
        }
        return best;
    }

    private static List<String> describeTrace(NpcEntity npc, NpcPathfinder.DebugTrace trace, ServerLevel level) {
        List<String> lines = new ArrayList<>();
        lines.add("—— 自定义寻路诊断（市民 " + npc.getCitizenId() + "）——");
        lines.add("请求目标=" + trace.requested().toShortString()
                + "　起点(脚下)=" + trace.start().toShortString()
                + "　余量=" + trace.padding() + "　缺失区块=" + trace.missingChunks());

        if (trace.missingChunks() > 0) {
            lines.add("警告：覆盖范围内还有 " + trace.missingChunks()
                    + " 个区块的快照没建好 —— 下面这一轮的结论不可信。已把它们入队，请过几秒重新执行一次。");
        }
        if (trace.snappedStart() == Integer.MIN_VALUE) {
            lines.add("失败：起点贴不到地 —— 那一列上下 1 格内没有落脚格。");
            lines.add("起点那一列：" + describeColumn(level, trace.start()));
            return lines;
        }
        if (trace.snappedEnd() == Integer.MIN_VALUE) {
            lines.add("终点贴地=失败（只允许在原位或上下 1 格内找落脚格）");
            lines.add("目标那一列：" + describeColumn(level, trace.requested()));
            lines.add("原因：那一列没有落脚格 —— 多半给在了方块本体、墙里或悬空处。");
            return lines;
        }

        lines.add("起点贴地 y=" + trace.snappedStart() + "　终点贴地 y=" + trace.snappedEnd()
                + "（请求 y=" + trace.requested().getY()
                + "，差 " + (trace.snappedEnd() - trace.requested().getY()) + "）");
        lines.add("搜索范围 X[" + trace.minX() + ".." + trace.maxX()
                + "] Z[" + trace.minZ() + ".." + trace.maxZ() + "]");
        lines.add("展开节点=" + trace.visited() + "／上限 " + NpcPathfinder.MAX_NODES
                + "　航点=" + trace.waypoints().size());
        lines.add("起点可达高度 y[" + trace.reachMinY() + ".." + trace.reachMaxY()
                + "]　目标 y=" + trace.snappedEnd());

        if (trace.waypoints().isEmpty()) {
            if (trace.exhausted()) {
                lines.add("失败：" + NpcPathfinder.MAX_NODES
                        + " 个节点用尽仍没搜到 —— 路可能存在，是搜索空间太大没搜完。");
            } else {
                lines.add("失败：可达范围内不存在通往目标的落脚格。");
            }
            lines.add("下列是【从起点真能走到】的点里、离目标最近的 " + trace.nearest().size()
                    + " 个（由近到远）——踩不到目标，卡住的位置就在这一圈上：");
            for (int i = 0; i < trace.nearest().size(); i++) {
                lines.add("  ~" + i + " " + describePoint(level, trace.nearest().get(i)));
            }
            if (!trace.nearest().isEmpty()) {
                BlockPos edge = trace.nearest().get(0);
                lines.add("卡住那格的四周（" + edge.toShortString() + "）：");
                lines.add("  西=" + blockName(level, edge.west())
                        + "　东=" + blockName(level, edge.east())
                        + "　北=" + blockName(level, edge.north())
                        + "　南=" + blockName(level, edge.south()));
                lines.add("  上=" + blockName(level, edge.above())
                        + "　下=" + blockName(level, edge.below())
                        + "　再下=" + blockName(level, edge.below(2)));
            }
            return lines;
        }
        for (int i = 0; i < trace.waypoints().size(); i++) {
            NpcPathfinder.Waypoint waypoint = trace.waypoints().get(i);
            lines.add("  #" + i + " " + waypoint.pos().toShortString() + " " + waypoint.move()
                    + "　" + describeBlocks(level, waypoint.pos())
                    + (waypoint.move() == NpcPathfinder.Waypoint.Move.STEP_UP
                            ? jumpClearance(level, waypoint.pos()) : ""));
        }
        return lines;
    }

    private static String describePoint(ServerLevel level, BlockPos pos) {
        return pos.toShortString() + "　" + describeBlocks(level, pos);
    }

    private static String describeBlocks(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return "（区块未加载）";
        }
        BlockState below = level.getBlockState(pos.below());
        BlockState at = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        boolean floor = ChunkSnapshot.isWalkableFloor(below);
        boolean body = ChunkSnapshot.isPassable(at);
        boolean head = ChunkSnapshot.isPassable(above);
        return "脚=" + name(at) + " 下=" + name(below) + " 头=" + name(above)
                + "　判定=" + (floor && body && head ? "可站" : "★A*不认")
                + "（下" + mark(floor) + "本" + mark(body) + "头" + mark(head) + "）";
    }

    private static String jumpClearance(ServerLevel level, BlockPos pos) {
        BlockState top = level.getBlockState(pos.above(2));
        if (ChunkSnapshot.isPassable(top)) {
            return "";
        }
        return "　⚠跳不起：再上一格是" + name(top) + "，起跳会顶住";
    }

    private static String name(BlockState state) {
        return state.getBlock().getName().getString();
    }

    private static String mark(boolean ok) {
        return ok ? "√" : "×";
    }

    private static String describeColumn(ServerLevel level, BlockPos pos) {
        return pos.toShortString()
                + "　↑头=" + blockName(level, pos.above())
                + "　●脚=" + blockName(level, pos)
                + "　↓下=" + blockName(level, pos.below())
                + "　↓↓再下=" + blockName(level, pos.below(2));
    }

    private static String blockName(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return "<未加载>";
        }
        return level.getBlockState(pos).getBlock().getName().getString();
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