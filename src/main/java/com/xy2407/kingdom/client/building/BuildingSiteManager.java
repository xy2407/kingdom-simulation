package com.xy2407.kingdom.client.building;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xy2407.kingdom.client.structure.StructureLoader;
import com.xy2407.kingdom.client.structure.StructureMesh;
import com.xy2407.kingdom.client.structure.StructureMesher;
import com.xy2407.kingdom.common.building.BuildingBlueprints;
import com.xy2407.kingdom.common.network.building.BuildingSitePacket;
import com.xy2407.kingdom.common.structure.StructureModel;
import com.xy2407.kingdom.common.structure.StructureRotation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 施工中建筑的客户端虚影缓存，随服务端开工与完工消息增删。
 */
@OnlyIn(Dist.CLIENT)
public final class BuildingSiteManager {

    public static final float SITE_ALPHA = 0.4F;

    public record Site(UUID taskId, String blueprintName, BlockPos origin, int rotation,
                       Vec3i size, StructureMesh mesh) {
    }

    private static final Map<UUID, Site> SITES = new LinkedHashMap<>();

    private BuildingSiteManager() {
    }

    public static void accept(BuildingSitePacket packet) {
        if (packet == null) {
            return;
        }
        if (packet.remove()) {
            release(SITES.remove(packet.taskId()));
            return;
        }
        String name = packet.blueprintName();
        if (name == null || name.isBlank() || packet.origin() == null) {
            return;
        }
        release(SITES.remove(packet.taskId()));
        StructureModel model = StructureLoader.loadFile(BuildingBlueprints.fileOf(name));
        if (model == null || model.blocks().isEmpty()) {
            return;
        }
        StructureModel rotated = StructureRotation.rotate(model, packet.rotation());
        StructureMesh mesh = StructureMesher.build(rotated);
        if (mesh == null || mesh.isEmpty()) {
            return;
        }
        SITES.put(packet.taskId(), new Site(packet.taskId(), name, packet.origin().immutable(),
                packet.rotation(), rotated.size(), mesh));
    }

    public static List<Site> sites() {
        return new ArrayList<>(SITES.values());
    }

    public static void clear() {
        for (Site site : SITES.values()) {
            release(site);
        }
        SITES.clear();
    }

    private static void release(Site site) {
        if (site != null && site.mesh() != null) {
            site.mesh().close();
        }
    }
}
