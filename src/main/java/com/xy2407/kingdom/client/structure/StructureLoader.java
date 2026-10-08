package com.xy2407.kingdom.client.structure;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.structure.StructureModel;
import net.minecraft.client.Minecraft;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 读取原版结构 NBT：从资源管理器或本地文件解析出建筑模型。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureLoader {

    private static final String STRUCTURE_DIRECTORY = "structure/";
    private static final String NBT_EXTENSION = ".nbt";

    private StructureLoader() {
    }

    public static StructureModel load(ResourceLocation structureId) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            StructureDebugChat.chat("加载失败：单人服务端为 null，客户端读不到 data/ 结构文件：%s", structureId);
            return null;
        }
        ResourceManager resources = server.getResourceManager();
        ResourceLocation fileLocation = ResourceLocation.fromNamespaceAndPath(
                structureId.getNamespace(),
                STRUCTURE_DIRECTORY + structureId.getPath() + NBT_EXTENSION
        );

        Optional<Resource> resource = resources.getResource(fileLocation);
        if (resource.isEmpty()) {
            StructureDebugChat.chat("加载失败：资源管理器里找不到结构文件 %s", fileLocation);
            return null;
        }

        CompoundTag root;
        try (InputStream stream = resource.get().open()) {
            root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException exception) {
            ModMain.LOGGER.error("Failed to read structure {}", fileLocation, exception);
            StructureDebugChat.chat("加载失败：读取 %s 抛出 %s: %s",
                    fileLocation, exception.getClass().getSimpleName(), exception.getMessage());
            return null;
        }

        StructureModel model = parse(root);
        if (model == null) {
            StructureDebugChat.chat("加载失败：%s 结构布局异常（size/palette/blocks 缺失），size=%d palette=%d blocks=%d",
                    fileLocation,
                    root.getList("size", Tag.TAG_INT).size(),
                    root.getList("palette", Tag.TAG_COMPOUND).size(),
                    root.getList("blocks", Tag.TAG_COMPOUND).size());
            return null;
        }
        StructureDebugChat.chat("加载成功：%s 尺寸=%dx%dx%d 非空方块=%d",
                structureId, model.size().getX(), model.size().getY(), model.size().getZ(),
                model.blocks().size());
        return model;
    }

    public static StructureModel loadFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        CompoundTag root;
        try (InputStream stream = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException exception) {
            ModMain.LOGGER.error("Failed to read structure file {}", file, exception);
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
        int airPaletteEntries = 0;
        for (int index = 0; index < paletteTag.size(); index++) {
            palette[index] = NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(index));
            if (palette[index] == null || palette[index].isAir()) {
                airPaletteEntries++;
            }
        }
        StructureDebugChat.chat("结构解析：size=%dx%dx%d palette=%d（其中空气/无效=%d）blocks=%d",
                size.getX(), size.getY(), size.getZ(), palette.length, airPaletteEntries, blockTags.size());

        List<StructureModel.BlockEntry> blocks = new ArrayList<>(blockTags.size());
        int invalidState = 0;
        int skippedAir = 0;
        int invalidPos = 0;
        for (int index = 0; index < blockTags.size(); index++) {
            CompoundTag blockTag = blockTags.getCompound(index);
            ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
            if (posTag.size() < 3) {
                invalidPos++;
                continue;
            }
            int stateIndex = blockTag.getInt("state");
            if (stateIndex < 0 || stateIndex >= palette.length) {
                invalidState++;
                continue;
            }
            BlockState state = palette[stateIndex];
            if (state == null || state.isAir()) {
                skippedAir++;
                continue;
            }
            blocks.add(new StructureModel.BlockEntry(
                    new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)),
                    state
            ));
        }
        if (invalidState > 0 || invalidPos > 0) {
            StructureDebugChat.chat("结构解析：丢弃非法条目 pos=%d state=%d（空气条目=%d）",
                    invalidPos, invalidState, skippedAir);
        }
        if (blocks.isEmpty()) {
            StructureDebugChat.chat("结构解析：没有任何非空气方块，预览必然空白");
        }
        return new StructureModel(size, blocks);
    }
}
