package com.xy2407.kingdom.client.structure;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.building.BuildingMeta;
import com.xy2407.kingdom.common.building.BuildingMetaLoader;
import com.xy2407.kingdom.common.building.BuildingType;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 本地建筑库目录，只扫描结构文件名与尺寸供预览卡片使用。
 */
@OnlyIn(Dist.CLIENT)
public final class KingdomSimulationStructures {

    public static final int MAX_PREVIEW_SIZE = 20;

    private static final String ROOT_NAME = "kingdom simulation";
    private static final String STRUCTURES_NAME = "structures";
    private static final String NBT_EXTENSION = ".nbt";
    private static final int SCAN_DEPTH = 2;
    private static final String BUNDLED_NAMESPACE = "xy2407_kingdom";
    private static final String BUNDLED_ROOT = "kingdom_simulation/structures";
    private static final String BUNDLED_PREFIX = BUNDLED_ROOT + "/";

    private KingdomSimulationStructures() {
    }

    public static Path rootDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(ROOT_NAME);
    }

    public static Path structuresDir() {
        return rootDir().resolve(STRUCTURES_NAME);
    }

    public static void deploy() {
        try {
            Path structures = structuresDir();
            Files.createDirectories(structures);
            for (BuildingType type : BuildingType.values()) {
                Files.createDirectories(structures.resolve(type.folder()));
            }
        } catch (IOException exception) {
            ModMain.LOGGER.error("Failed to create kingdom simulation structures folder", exception);
        }
        com.xy2407.kingdom.common.building.BlueprintDeployer.deploy();
    }

    public static List<Entry> scan() {
        deploy();
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Path dir = structuresDir();
        if (Files.isDirectory(dir)) {
            // 以本地文件为唯一真源：一个 nbt 一张卡，id 用相对路径，先收录再占名
            for (Path file : listLocal(dir)) {
                String id = stripExtension(dir.relativize(file).toString().replace('\\', '/'));
                Summary summary = readSummary(file);
                if (summary == null) {
                    StructureDebugChat.chat("剔除无法解析的结构文件：%s", dir.relativize(file));
                    continue;
                }
                BuildingMeta meta = BuildingMetaLoader.load(file, id);
                entries.add(new Entry(id, meta, summary.size(), summary.beds(),
                        folderOf(id, meta), summary.iconItem()));
                seen.add(id);
                if (meta.buildName() != null) {
                    seen.add(meta.buildName());
                }
                if (meta.file() != null && !meta.file().isBlank()) {
                    seen.add(meta.file());
                }
            }
            // 补充：json 的 file 指向别处、本地扫描没收录到的（不覆盖已有 id）
            for (String buildName : BuildingMetaLoader.allBuildNames()) {
                BuildingMeta meta = BuildingMetaLoader.byBuildName(buildName);
                if (meta == null) {
                    continue;
                }
                String fileId = meta.file();
                if (fileId == null || fileId.isBlank() || seen.contains(fileId)) {
                    continue;
                }
                Path file = BuildingBlueprints.fileOf(fileId);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                Summary summary = readSummary(file);
                if (summary == null) {
                    continue;
                }
                entries.add(new Entry(fileId, meta, summary.size(), summary.beds(),
                        folderOf(fileId, meta), summary.iconItem()));
                seen.add(fileId);
            }
        }
        scanBundled(entries, seen);
        entries.sort(Comparator.comparing(Entry::id));
        return entries;
    }

    /**
     * 分类归属：蓝图声明优先，缺失时按所在目录推断，未知目录统一归到「其它」
     */
    private static String folderOf(String id, BuildingMeta meta) {
        if (meta != null && meta.type() != null) {
            return meta.type().folder();
        }
        int slash = id.lastIndexOf('/');
        if (slash <= 0) {
            return BuildingType.OTHER.folder();
        }
        BuildingType byFolder = BuildingType.parse(id.substring(0, slash));
        return byFolder == null ? BuildingType.OTHER.folder() : byFolder.folder();
    }

    private static List<Path> listLocal(Path dir) {
        try (Stream<Path> stream = Files.walk(dir, SCAN_DEPTH)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(NBT_EXTENSION))
                    .sorted(Comparator.comparing(path -> dir.relativize(path).toString()))
                    .toList();
        } catch (IOException exception) {
            ModMain.LOGGER.error("Failed to list kingdom simulation structures folder {}", dir, exception);
            return List.of();
        }
    }

    private static void scanBundled(List<Entry> entries, Set<String> seen) {
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        if (manager == null) {
            return;
        }
        Map<ResourceLocation, Resource> found;
        try {
            found = manager.listResources(BUNDLED_ROOT, location ->
                    BUNDLED_NAMESPACE.equals(location.getNamespace())
                            && location.getPath().startsWith(BUNDLED_PREFIX)
                            && location.getPath().endsWith(NBT_EXTENSION));
        } catch (RuntimeException exception) {
            ModMain.LOGGER.error("Failed to list bundled kingdom structures", exception);
            return;
        }
        for (Map.Entry<ResourceLocation, Resource> item : found.entrySet()) {
            String path = item.getKey().getPath().substring(BUNDLED_PREFIX.length());
            if (path.chars().filter(character -> character == '/').count() > 1L) {
                continue;
            }
            String id = stripExtension(path);
            if (!seen.add(id)) {
                continue;
            }
            Summary summary;
            try (InputStream stream = item.getValue().open()) {
                summary = readSummary(stream);
            } catch (IOException exception) {
                continue;
            }
            if (summary == null) {
                continue;
            }
            BuildingMeta meta = BuildingMeta.fallback(id, null);
            entries.add(new Entry(id, meta, summary.size(), summary.beds(),
                    folderOf(id, meta), summary.iconItem()));
        }
    }

    private static String stripExtension(String relativePath) {
        int dot = relativePath.lastIndexOf('.');
        return dot < 0 ? relativePath : relativePath.substring(0, dot);
    }

    private record Summary(Vec3i size, int beds, String iconItem) {
    }

    private static Summary readSummary(Path file) {
        try (InputStream stream = Files.newInputStream(file)) {
            return readSummary(stream);
        } catch (IOException exception) {
            ModMain.LOGGER.error("Failed to read structure file {}", file, exception);
            return null;
        }
    }

    private static Summary readSummary(InputStream stream) {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException exception) {
            return null;
        }
        ListTag sizeTag = root.getList("size", Tag.TAG_INT);
        if (sizeTag.size() < 3) {
            return null;
        }
        int x = sizeTag.getInt(0);
        int y = sizeTag.getInt(1);
        int z = sizeTag.getInt(2);
        ListTag blockTags = root.getList("blocks", Tag.TAG_COMPOUND);
        if (x <= 0 || y <= 0 || z <= 0 || blockTags.isEmpty()) {
            return null;
        }
        return new Summary(new Vec3i(x, y, z), countBeds(root, blockTags),
                dominantItem(root, blockTags));
    }

    /**
     * 取使用次数最多的非空气方块当卡片图标，读一次缓存进 Entry，避免渲染期再碰 palette
     */
    private static String dominantItem(CompoundTag root, ListTag blockTags) {
        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        if (paletteTag.isEmpty()) {
            return "";
        }
        int[] counts = new int[paletteTag.size()];
        for (int index = 0; index < blockTags.size(); index++) {
            int stateIndex = blockTags.getCompound(index).getInt("state");
            if (stateIndex >= 0 && stateIndex < counts.length) {
                counts[stateIndex]++;
            }
        }
        HolderGetter<Block> blockLookup = BuiltInRegistries.BLOCK.asLookup();
        int best = -1;
        int bestCount = 0;
        for (int index = 0; index < counts.length; index++) {
            if (counts[index] <= bestCount) {
                continue;
            }
            BlockState state = NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(index));
            if (state.isAir() || state.getBlock().asItem() == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            best = index;
            bestCount = counts[index];
        }
        if (best < 0) {
            return "";
        }
        BlockState state = NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(best));
        return BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString();
    }

    private static int countBeds(CompoundTag root, ListTag blockTags) {
        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        if (paletteTag.isEmpty()) {
            return 0;
        }
        HolderGetter<Block> blockLookup = BuiltInRegistries.BLOCK.asLookup();
        boolean[] bedFoot = new boolean[paletteTag.size()];
        for (int index = 0; index < paletteTag.size(); index++) {
            BlockState state = NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(index));
            bedFoot[index] = state.hasProperty(BlockStateProperties.BED_PART)
                    && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT;
        }
        int beds = 0;
        for (int index = 0; index < blockTags.size(); index++) {
            int stateIndex = blockTags.getCompound(index).getInt("state");
            if (stateIndex >= 0 && stateIndex < bedFoot.length && bedFoot[stateIndex]) {
                beds++;
            }
        }
        return beds;
    }

    @OnlyIn(Dist.CLIENT)
    public record Entry(String id, BuildingMeta meta, Vec3i size, int beds, String folder,
                        String iconItem) {

        public String displayName() {
            return meta.name();
        }

        public String typeLabel() {
            return meta.typeLabel();
        }

        public boolean previewable() {
            return size.getX() < MAX_PREVIEW_SIZE
                    && size.getY() < MAX_PREVIEW_SIZE
                    && size.getZ() < MAX_PREVIEW_SIZE;
        }
    }
}
