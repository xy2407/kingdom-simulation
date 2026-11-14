package com.xy2407.kingdom.server.wall;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.building.BuildingMetaLoader;
import com.xy2407.kingdom.common.wall.WallBlock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 服务端读取城墙蓝图 NBT，转成相对方块表（坐标为蓝图内相对坐标）
 */
public final class WallStructureReader {

    private WallStructureReader() {
    }

    public static List<WallBlock> read(String blueprint) {
        Path file = BuildingMetaLoader.blueprintFile(blueprint);
        if (file == null || !Files.isRegularFile(file)) {
            ModMain.LOGGER.warn("城墙蓝图不存在: {}", blueprint);
            return List.of();
        }
        try (InputStream stream = Files.newInputStream(file)) {
            CompoundTag root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
            ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int index = 0; index < paletteTag.size(); index++) {
                CompoundTag entry = paletteTag.getCompound(index);
                String name = entry.getString("Name");
                Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name));
                palette[index] = block.defaultBlockState();
            }
            ListTag blocksTag = root.getList("blocks", Tag.TAG_COMPOUND);
            List<WallBlock> result = new ArrayList<>(blocksTag.size());
            for (int index = 0; index < blocksTag.size(); index++) {
                CompoundTag entry = blocksTag.getCompound(index);
                ListTag pos = entry.getList("pos", Tag.TAG_INT);
                int state = entry.getInt("state");
                if (pos.size() < 3 || state < 0 || state >= palette.length) {
                    continue;
                }
                BlockState blockState = palette[state];
                if (blockState.isAir()) {
                    continue;
                }
                result.add(new WallBlock(pos.getInt(0), pos.getInt(1), pos.getInt(2),
                        Block.getId(blockState)));
            }
            return result;
        } catch (Exception exception) {
            ModMain.LOGGER.error("读取城墙蓝图失败: {}", blueprint, exception);
            return List.of();
        }
    }
}
