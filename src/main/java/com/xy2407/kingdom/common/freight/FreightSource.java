package com.xy2407.kingdom.common.freight;

import com.xy2407.kingdom.common.building.ItemCount;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/** 长期缺料来源：由物流系统低频轮询缺料，返回空表示当前不需要补货。 */
public interface FreightSource {

    UUID sourceId();

    String dimension();

    boolean valid();

    default UUID buildingId() {
        return null;
    }

    default BlockPos containerPos() {
        return null;
    }

    List<ItemCount> missing();
}
