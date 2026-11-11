package com.xy2407.kingdom.client.area;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.area.PlotAreaKind;
import com.xy2407.kingdom.common.network.area.AreaOverviewPacket;
import com.xy2407.kingdom.common.network.area.AreaOverviewRequestPacket;

import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 已圈地块的客户端总览缓存，圈地或放置投影时向服务端拉取并按类型高亮
 */
@OnlyIn(Dist.CLIENT)
public final class AreaOverviewCache {

    private static final long REQUEST_COOLDOWN_TICKS = 100;

    private static final List<Block> AREAS = new ArrayList<>();

    private static long requestedAt;

    private AreaOverviewCache() {
    }

    public static void request() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - requestedAt < REQUEST_COOLDOWN_TICKS * 50L) {
            return;
        }
        requestedAt = now;
        PacketDistributor.sendToServer(new AreaOverviewRequestPacket());
    }

    public static void accept(AreaOverviewPacket packet) {
        AREAS.clear();
        if (packet == null || packet.kinds() == null) {
            return;
        }
        UUID[] ids = packet.buildingIds();
        long[][] cells = packet.cells();
        for (int index = 0; index < packet.kinds().length; index++) {
            PlotAreaKind kind = packet.kinds()[index];
            if (kind == null || cells == null || index >= cells.length || cells[index] == null
                    || cells[index].length == 0) {
                continue;
            }
            UUID id = ids != null && index < ids.length ? ids[index] : null;
            AREAS.add(new Block(kind, id, cells[index]));
        }
    }

    public static List<Block> areas() {
        return AREAS;
    }

    public record Block(PlotAreaKind kind, UUID buildingId, long[] cells) {
    }
}
