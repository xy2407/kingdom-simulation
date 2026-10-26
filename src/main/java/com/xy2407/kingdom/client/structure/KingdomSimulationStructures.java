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
    }

    public static List<Entry> scan() {
        deploy();
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Path dir = structuresDir();
        if (Files.isDirectory(dir)) {
            for (String buildName : BuildingMetaLoader.allBuildNames()) {
                BuildingMeta meta = BuildingMetaLoader.byBuildName(buildName);
                if (meta == null || !seen.add(buildName)) {
                    continue;
                }
                Path file = BuildingBlueprints.fileOf(meta.file());
                Summary summary = Files.isRegularFile(file) ? readSummary(file) : null;
                if (summary == null) {
                    continue;
                }
                entries.add(new Entry(buildName, meta, summary.size(), summary.beds(),
                        meta.type() == null ? "" : meta.type().folder()));
            }
            for (Path file : listLocal(dir)) {
                String id = stripExtension(dir.relativize(file).toString().replace('\\', '/'));
                if (!seen.add(id)) {
                    continue;
                }
                Summary summary = readSummary(file);
                if (summary == null) {
                    StructureDebugChat.chat("剔除无法解析的结构文件：%s", dir.relativize(file));
                    continue;
                }
                BuildingMeta meta = BuildingMetaLoader.load(file, id);
                entries.add(new Entry(id, meta, summary.size(), summary.beds(),
                        meta.type() == null ? "" : meta.type().folder()));
            }
        }
        scanBundled(entries, seen);
        entries.sort(Comparator.comparing(Entry::id));
        return entries;
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
            int slash = id.lastIndexOf('/');
            String folder = slash < 0 ? "" : id.substring(0, slash);
            BuildingMeta meta = BuildingMeta.fallback(id, null);
            entries.add(new Entry(id, meta, summary.size(), summary.beds(), folder));
        }
    }

    private static String stripExtension(String relativePath) {
        int dot = relativePath.lastIndexOf('.');
        return dot < 0 ? relativePath : relativePath.substring(0, dot);
    }

    private record Summary(Vec3i size, int beds) {
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
        return new Summary(new Vec3i(x, y, z), countBeds(root, blockTags));
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
    public record Entry(String id, BuildingMeta meta, Vec3i size, int beds, String folder) {

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
