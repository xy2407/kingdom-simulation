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
 * 施工中建筑的客户端虚影缓存。
 *
 * <p>生命周期完全跟着服务端任务：{@code start} 时收到新增、{@code finish}/{@code abort} 时收到移除。
 * 与 {@link BuildingPlacementManager} 那份"放置投影"是两回事——那份在右键暂存时就交给服务端了，
 * 这份负责从开工一直到完工/中止的整段过程。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuildingSiteManager {

    /** 80% 透明：只留下淡淡的轮廓，不挡已经建好的实体方块。 */
    public static final float SITE_ALPHA = 0.2F;

    /** 一个施工中的建筑：位置、旋转与烘好的完整模型网格。 */
    public record Site(UUID taskId, String blueprintName, BlockPos origin, int rotation,
                       Vec3i size, StructureMesh mesh) {
    }

    private static final Map<UUID, Site> SITES = new LinkedHashMap<>();

    private BuildingSiteManager() {
    }

    /** 服务端施工点增删回包的落地点。 */
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
        // 同名任务重推（例如中途重进世界）时先释放旧的网格，避免 GPU 资源泄漏
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

    /** 断开连接时清空，避免跨世界残留。 */
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
