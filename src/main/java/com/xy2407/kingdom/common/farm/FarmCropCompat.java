package com.xy2407.kingdom.common.farm;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 第三方作物适配：水稻灌水立三格、葡萄按框选形状立柱搭藤架、茶树改旱地，其余直接落地。
 */
public final class FarmCropCompat {

    private static final String RICE = "kaleidoscope_cookery:rice_crop";
    private static final String TEA = "kaleidoscope_cookery:tea_tree";
    private static final String PILLAR = "kaleidoscope_tavern:trellis";

    private static final Map<UUID, Supports> SUPPORTS = new ConcurrentHashMap<>();

    private record Supports(FarmPlot plot, Set<Long> cells) {
    }

    private static final Method SET_VALUE = setValueMethod();

    private static final Map<String, String> TRELLIS = Map.of(
            "kaleidoscope_tavern:grape_crop", "kaleidoscope_tavern:grapevine_trellis",
            "kaleidoscope_tavern:ice_grape_crop", "kaleidoscope_tavern:ice_grapevine_trellis",
            "kaleidoscope_tavern:gold_grape_crop", "kaleidoscope_tavern:gold_grapevine_trellis");

    private FarmCropCompat() {
    }

    public static boolean needsWater(String cropId) {
        return RICE.equals(resolve(cropId));
    }

    public static boolean keepsDirt(String cropId) {
        return TEA.equals(resolve(cropId));
    }

    public static boolean skipsTill(String cropId) {
        return TRELLIS.containsKey(resolve(cropId));
    }

    public static boolean isPlanted(ServerLevel level, BlockPos soil, String cropId) {
        String id = resolve(cropId);
        Block trellis = trellisOf(id);
        if (trellis != null) {
            return level.getBlockState(soil.above(3)).is(trellis);
        }
        BlockState state = level.getBlockState(soil.above());
        return !state.isAir() && !state.is(Blocks.WATER);
    }

    public static BlockPos cropPos(BlockPos soil, String cropId) {
        return TRELLIS.containsKey(resolve(cropId)) ? soil.above(2) : soil.above();
    }

    public static boolean plant(ServerLevel level, BlockPos soil, Block crop, FarmPlot plot) {
        if (level == null || soil == null || crop == null) {
            return false;
        }
        String id = idOf(crop);
        BlockPos pos = soil.above();
        if (RICE.equals(id)) {
            return plantRice(level, pos, crop);
        }
        if (TRELLIS.containsKey(id)) {
            return plantGrape(level, soil, crop, plot);
        }
        if (TEA.equals(id)) {
            return plantTea(level, pos, crop);
        }
        return plantPlain(level, pos, crop);
    }

    public static boolean isMature(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(property.getName())) {
                return state.getValue(age) >= Collections.max(age.getPossibleValues());
            }
        }
        return false;
    }

    private static boolean plantRice(ServerLevel level, BlockPos pos, Block crop) {
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.is(Blocks.FARMLAND) && !ground.is(BlockTags.DIRT)) {
            return false;
        }
        if (level.getBlockState(pos).is(crop)) {
            return true;
        }
        if (!replaceable(level, pos, crop) || !replaceable(level, pos.above(), crop)
                || !replaceable(level, pos.above(2), crop)) {
            return false;
        }
        BlockState base = with(with(crop.defaultBlockState(), "waterlogged", Boolean.TRUE), "location", 0);
        if (!base.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, base, Block.UPDATE_ALL);
        level.setBlock(pos.above(), with(crop.defaultBlockState(), "location", 1), Block.UPDATE_ALL);
        level.setBlock(pos.above(2), with(crop.defaultBlockState(), "location", 2), Block.UPDATE_ALL);
        return true;
    }

    private static boolean plantGrape(ServerLevel level, BlockPos soil, Block crop, FarmPlot plot) {
        Block trellis = trellisOf(idOf(crop));
        if (trellis == null) {
            return false;
        }
        if (isSupport(soil, plot)) {
            Block pillar = pillarBlock();
            if (pillar != null) {
                placePillar(level, soil.above(), pillar);
                placePillar(level, soil.above(2), pillar);
            }
        }
        BlockPos trellisPos = soil.above(3);
        BlockState state = level.getBlockState(trellisPos);
        if (state.is(trellis)) {
            return true;
        }
        if (!state.isAir() && !state.canBeReplaced()) {
            return false;
        }
        level.setBlock(trellisPos, trellis.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    private static boolean isSupport(BlockPos soil, FarmPlot plot) {
        if (plot == null) {
            return false;
        }
        Supports supports = SUPPORTS.compute(plot.id(),
                (id, old) -> old != null && old.plot().equals(plot) ? old : build(plot));
        return supports.cells().contains(soil.asLong());
    }

    private static Supports build(FarmPlot plot) {
        Set<Long> cells = cellsOf(plot);
        Set<Long> corners = new HashSet<>();
        for (long cell : cells) {
            if (isCorner(cells, cell)) {
                corners.add(cell);
            }
        }
        return new Supports(plot, corners);
    }

    private static Set<Long> cellsOf(FarmPlot plot) {
        Set<Long> cells = new HashSet<>();
        int y = plot.min().getY();
        if (plot.hasCore()) {
            for (int x = plot.min().getX(); x <= plot.max().getX(); x++) {
                for (int z = plot.min().getZ(); z <= plot.max().getZ(); z++) {
                    cells.add(BlockPos.asLong(x, y, z));
                }
            }
        }
        for (FarmRegion region : plot.extra()) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    cells.add(BlockPos.asLong(x, y, z));
                }
            }
        }
        return cells;
    }

    private static boolean isCorner(Set<Long> cells, long cell) {
        int x = BlockPos.getX(cell);
        int y = BlockPos.getY(cell);
        int z = BlockPos.getZ(cell);
        boolean north = cells.contains(BlockPos.asLong(x, y, z - 1));
        boolean south = cells.contains(BlockPos.asLong(x, y, z + 1));
        boolean west = cells.contains(BlockPos.asLong(x - 1, y, z));
        boolean east = cells.contains(BlockPos.asLong(x + 1, y, z));
        int around = (north ? 1 : 0) + (south ? 1 : 0) + (west ? 1 : 0) + (east ? 1 : 0);
        if (around <= 1) {
            return true;
        }
        return around == 2 && !(north && south) && !(west && east);
    }

    private static void placePillar(ServerLevel level, BlockPos pos, Block pillar) {
        BlockState state = level.getBlockState(pos);
        if (state.is(pillar)) {
            return;
        }
        if (!state.isAir() && !state.canBeReplaced()) {
            return;
        }
        level.setBlock(pos, pillar.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static Block pillarBlock() {
        ResourceLocation key = ResourceLocation.tryParse(PILLAR);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(key);
        return block == null || block == Blocks.AIR ? null : block;
    }

    private static boolean plantTea(ServerLevel level, BlockPos pos, Block crop) {
        if (level.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
            level.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        BlockState planted = crop.defaultBlockState();
        if (!planted.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, planted, Block.UPDATE_ALL);
        return true;
    }

    private static boolean plantPlain(ServerLevel level, BlockPos pos, Block crop) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        BlockState planted = crop.defaultBlockState();
        if (!planted.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, planted, Block.UPDATE_ALL);
        return true;
    }

    private static boolean replaceable(ServerLevel level, BlockPos pos, Block crop) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(Blocks.WATER) || state.is(crop) || state.canBeReplaced();
    }

    private static String resolve(String cropId) {
        if (cropId == null || cropId.isBlank()) {
            return "";
        }
        String plantId = CropCatalog.plantBlockId(cropId);
        return plantId == null || plantId.isBlank() ? cropId : plantId;
    }

    private static String idOf(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? "" : key.toString();
    }

    private static Block trellisOf(String cropId) {
        String id = cropId == null ? null : TRELLIS.get(cropId);
        if (id == null) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(key);
        return block == null || block == Blocks.AIR ? null : block;
    }

    private static BlockState with(BlockState state, String name, Comparable<?> value) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return apply(state, property, value);
            }
        }
        return state;
    }

    private static Method setValueMethod() {
        try {
            return BlockState.class.getMethod("setValue", Property.class, Comparable.class);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static BlockState apply(BlockState state, Property<?> property, Comparable<?> value) {
        if (SET_VALUE == null) {
            return state;
        }
        try {
            return (BlockState) SET_VALUE.invoke(state, property, value);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return state;
        }
    }
}
