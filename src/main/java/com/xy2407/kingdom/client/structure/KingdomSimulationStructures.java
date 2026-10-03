package com.xy2407.kingdom.client.structure;

import com.xy2407.kingdom.ModMain;
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
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 本地建筑库目录，只扫描结构文件名与尺寸供预览卡片使用
 */
@OnlyIn(Dist.CLIENT)
public final class KingdomSimulationStructures {

    /** 三边都小于该值的结构才允许 3D 预览，超出只在卡片上提示。 */
    public static final int MAX_PREVIEW_SIZE = 20;

    private static final String ROOT_NAME = "kingdom simulation";
    private static final String STRUCTURES_NAME = "structures";
    private static final String NBT_EXTENSION = ".nbt";
    /** 扫描深度：根目录 + 一层类型子目录。 */
    private static final int SCAN_DEPTH = 2;

    private KingdomSimulationStructures() {
    }

    /** 游戏根目录下部署的根文件夹。 */
    public static Path rootDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(ROOT_NAME);
    }

    /** 放结构 nbt 的文件夹。 */
    public static Path structuresDir() {
        return rootDir().resolve(STRUCTURES_NAME);
    }

    /** 启动时部署根目录与六个类型子目录，已存在则沿用。 */
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

    /**
     * 扫描 structures 文件夹下所有 {@code .nbt}，按文件名字典序排序。
     *
     * <p>只解析根标签的尺寸与方块表是否为空，不构建任何建筑模型——列表卡片自己按需加载。</p>
     */
    public static List<Entry> scan() {
        deploy();
        List<Entry> entries = new ArrayList<>();
        Path dir = structuresDir();
        if (!Files.isDirectory(dir)) {
            return entries;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir, SCAN_DEPTH)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(NBT_EXTENSION))
                    .sorted(Comparator.comparing(path -> dir.relativize(path).toString()))
                    .toList();
        } catch (IOException exception) {
            ModMain.LOGGER.error("Failed to list kingdom simulation structures folder {}", dir, exception);
            return entries;
        }

        for (Path file : files) {
            Summary summary = readSummary(file);
            if (summary == null) {
                StructureDebugChat.chat("剔除无法解析的结构文件：%s", dir.relativize(file));
                continue;
            }
            String id = stripExtension(dir.relativize(file).toString().replace('\\', '/'));
            BuildingMeta meta = BuildingMetaLoader.load(file, id);
            entries.add(new Entry(id, meta, summary.size(), summary.beds()));
        }
        return entries;
    }

    private static String stripExtension(String relativePath) {
        int dot = relativePath.lastIndexOf('.');
        return dot < 0 ? relativePath : relativePath.substring(0, dot);
    }

    /** 一趟读取得到的摘要：尺寸 + 床数。 */
    private record Summary(Vec3i size, int beds) {
    }

    /** 读取结构根标签；无尺寸、尺寸非法或无方块表时返回 null。 */
    private static Summary readSummary(Path file) {
        CompoundTag root;
        try (InputStream stream = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException exception) {
            ModMain.LOGGER.error("Failed to read structure file {}", file, exception);
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
        // 尺寸与床数同一趟 IO 一起算出来，不必二次读取文件
        return new Summary(new Vec3i(x, y, z), countBeds(root, blockTags));
    }

    /**
     * 数床位。
     *
     * <p>一张床由 {@code foot} 与 {@code head} 两个方块组成，只数 {@code foot}，数出来就是床的张数。
     * 判定走 {@link BlockStateProperties#BED_PART} 属性而不是具体的 {@code BedBlock} 类，
     * 这样原版 16 色床以及带该属性的模组床都能被认出来。</p>
     */
    private static int countBeds(CompoundTag root, ListTag blockTags) {
        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        if (paletteTag.isEmpty()) {
            return 0;
        }
        // 先把 palette 里"哪些条目算床脚"预计算一遍，再线性扫方块，避免每个方块重复解状态
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

    /**
     * 扫描结果：蓝图标识 + 元数据 + 结构尺寸 + 床位。
     *
     * @param beds 蓝图里画出来的床位数（只数床脚，一张床两个方块）；仅住宅类建筑使用
     */
    @OnlyIn(Dist.CLIENT)
    public record Entry(String id, BuildingMeta meta, Vec3i size, int beds) {

        /** 卡片上显示的名字，来自元数据（未配置 json 时退回文件名）。 */
        public String displayName() {
            return meta.name();
        }

        /** 类型中文名，未归类时为"未分类"。 */
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
