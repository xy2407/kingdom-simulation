package com.xy2407.kingdom.common.building;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.structure.StructureModel;
import com.xy2407.kingdom.common.structure.StructureRotation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 建筑蓝图库，读取游戏目录本地结构 NBT 并生成施工计划
 */
public final class BuildingBlueprints {
    private static final String ROOT_NAME = "kingdom simulation";
    private static final String STRUCTURES_NAME = "structures";
    private static final String EXTENSION = ".nbt";
    /** 扫描深度：{@code structures} 根目录 + 一层类型子目录。 */
    private static final int SCAN_DEPTH = 2;

    private BuildingBlueprints() {
    }

    public static Path structuresDir() {
        return FMLPaths.GAMEDIR.get().resolve(ROOT_NAME).resolve(STRUCTURES_NAME);
    }

    public static Path fileOf(String name) {
        return structuresDir().resolve(sanitize(name) + EXTENSION);
    }

    /**
     * 列出所有蓝图标识，递归一层子目录（六个类型目录）。
     *
     * <p>标识是相对 {@code structures} 的路径去掉扩展名，统一用 {@code /} 分隔，
     * 例如 {@code residential/house_small}；它既能直接喂给 {@link #fileOf(String)} 定位文件，
     * 也是网络传输与入库用的键。</p>
     */
    public static List<String> listNames() {
        Path dir = structuresDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir, SCAN_DEPTH)) {
            files.filter(BuildingBlueprints::isBlueprintFile)
                    .map(path -> stripExtension(relativeId(dir, path)))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException exception) {
            ModMain.LOGGER.error("Failed to list building blueprints in {}", dir, exception);
        }
        return names;
    }

    /** 相对 structures 的路径，统一成 {@code /} 分隔。 */
    public static String relativeId(Path structuresDir, Path file) {
        return structuresDir.relativize(file).toString().replace('\\', '/');
    }

    public static BuildPlan load(String name) {
        return load(name, 0);
    }

    /** 按旋转角度载入蓝图，返回已经旋转过的施工计划。 */
    public static BuildPlan load(String name, int rotationDegrees) {
        StructureModel model = loadModel(name);
        return model == null ? null : BuildPlan.of(StructureRotation.rotate(model, rotationDegrees));
    }

    private static StructureModel loadModel(String name) {
        if (!isValidName(name)) {
            return null;
        }
        Path file = fileOf(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        CompoundTag root;
        try (InputStream stream = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException exception) {
            ModMain.LOGGER.error("Failed to read building blueprint {}", file, exception);
            return null;
        }
        return parse(root);
    }

    private static StructureModel parse(CompoundTag root) {
        ListTag sizeTag = root.getList("size", Tag.TAG_INT);
        if (sizeTag.size() < 3) {
            return null;
        }
        Vec3i size = new Vec3i(sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2));
        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        ListTag blockTags = root.getList("blocks", Tag.TAG_COMPOUND);
        HolderGetter<Block> blockLookup = BuiltInRegistries.BLOCK.asLookup();
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int index = 0; index < paletteTag.size(); index++) {
            palette[index] = NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(index));
        }
        List<StructureModel.BlockEntry> blocks = new ArrayList<>(blockTags.size());
        for (int index = 0; index < blockTags.size(); index++) {
            CompoundTag blockTag = blockTags.getCompound(index);
            ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
            if (posTag.size() < 3) {
                continue;
            }
            int stateIndex = blockTag.getInt("state");
            if (stateIndex < 0 || stateIndex >= palette.length) {
                continue;
            }
            BlockState state = palette[stateIndex];
            if (state == null || state.isAir()) {
                continue;
            }
            blocks.add(new StructureModel.BlockEntry(
                    new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)),
                    state));
        }
        if (blocks.isEmpty()) {
            return null;
        }
        return new StructureModel(size, blocks);
    }

    private static boolean isBlueprintFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(EXTENSION);
    }

    /**
     * 允许 {@code residential/house} 这样的子目录标识，但仍挡住跳出 structures 目录的写法。
     *
     * <p>标识来自客户端与网络包，必须当作不可信输入处理。</p>
     */
    private static boolean isValidName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            return false;
        }
        return !normalized.isBlank();
    }

    private static String sanitize(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.toLowerCase().endsWith(EXTENSION)) {
            trimmed = trimmed.substring(0, trimmed.length() - EXTENSION.length());
        }
        return trimmed;
    }

    private static String stripExtension(String fileName) {
        return fileName.substring(0, fileName.length() - EXTENSION.length());
    }
}
