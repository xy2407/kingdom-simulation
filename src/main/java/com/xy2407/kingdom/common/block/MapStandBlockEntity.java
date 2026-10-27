package com.xy2407.kingdom.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** 仓库地图架方块实体：只存该仓库的管辖建筑清单。 */
public class MapStandBlockEntity extends BlockEntity {

    private static final String KEY_GOVERNED = "Governed";

    private final Set<UUID> governed = new LinkedHashSet<>();

    public MapStandBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MAP_STAND.get(), pos, state);
    }

    public Set<UUID> governed() {
        return governed;
    }

    public boolean isGoverned(UUID buildingId) {
        return buildingId != null && governed.contains(buildingId);
    }

    public void setGoverned(UUID buildingId, boolean value) {
        if (buildingId == null) {
            return;
        }
        boolean changed = value ? governed.add(buildingId) : governed.remove(buildingId);
        if (changed) {
            setChanged();
        }
    }

    public void clearGoverned() {
        governed.clear();
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (UUID id : governed) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            list.add(entry);
        }
        tag.put(KEY_GOVERNED, list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        governed.clear();
        ListTag list = tag.getList(KEY_GOVERNED, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("id")) {
                governed.add(entry.getUUID("id"));
            }
        }
    }
}
