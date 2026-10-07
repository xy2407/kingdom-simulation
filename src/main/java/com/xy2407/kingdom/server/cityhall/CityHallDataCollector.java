package com.xy2407.kingdom.server.cityhall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.faction.Nation;
import com.xy2407.kingdom.common.faction.PlayerProfile;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.faction.TerritoryVitals;
import com.xy2407.kingdom.common.network.cityhall.CityHallDataPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.faction.NationRegistry;
import com.xy2407.kingdom.server.faction.PlayerProfileStore;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.faction.TerritoryVitalsStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 把服务端的城镇数据打包成 {@link CityHallDataPacket}。
 *
 * <p><b>哪些是真数据、哪些是空的，这里必须说清楚</b>，免得以后有人看到界面上的 "—" 以为是 bug：</p>
 * <ul>
 *   <li><b>真取</b>：人口（{@code NpcManager} 的市民总数）、建筑（{@code BuildingRegistry} 的已完工
 *       建筑数）、天数（{@code DateSystem}）、市民名录、建筑名录、幸福度
 *       （市民 {@code mood} 的平均值——这是唯一一个"现成能算且语义正确"的指标：市民平均心情即城镇幸福度）。</li>
 *   <li><b>取不到，给中性值</b>：城镇名、人口上限、建筑上限、附属地、金库及其上限、凝聚力、忠诚度、
 *       隶属 / 盟友 / 敌人 / 政策，以及世界事件 / 政务处理 / 资金流水三个列表。
 *       这些在服务端**没有对应的存储**（全项目没有 Town / Kingdom / 金库 / 事件这些概念），
 *       所以打包成 0 / 空串 / 空列表，页面呈现为 "—" 与空态提示。
 *       等城镇数据落库后，只要改本类的取值即可，包结构和页面都不用动。</li>
 * </ul>
 */
public final class CityHallDataCollector {

    /** 市民名录条数上限。界面靠滚动看，这里只是防止一口气塞太多。 */
    private static final int CITIZEN_LIMIT = 200;
    /** 建筑名录条数上限，同上。 */
    private static final int STRUCTURE_LIMIT = 200;

    private CityHallDataCollector() {
    }

    /**
     * @param level   服务端世界，用于取天数
     * @param hallPos 市政厅方块位置。既用来反查它所在的领地，也作为地图的默认中心
     * @param viewer  看这份数据的玩家。地图中心优先用<b>他自己</b>上次看的位置，
     *                没记过才退回市政厅——每人一份，不会互相干扰
     */
    public static CityHallDataPacket collect(ServerLevel level, BlockPos hallPos, UUID viewer) {
        /* 两张表都是懒加载的，冷启动 / 刚重进世界时可能还没读出来。
           这里一律容忍 null——本方法宁可给出空数据，也不能抛异常连累界面打开
           （调用点 CityHallBlock 也另有一层 try/catch 兜底）。 */
        Collection<NpcData> npcs = NpcManager.INSTANCE.allNpcs();
        if (npcs == null) {
            npcs = List.of();
        }
        List<BuildingRecord> records = BuildingRegistry.INSTANCE.all();
        if (records == null) {
            records = List.of();
        }

        List<CityHallDataPacket.Citizen> citizens = new ArrayList<>();
        for (NpcData npc : npcs) {
            if (citizens.size() < CITIZEN_LIMIT) {
                citizens.add(new CityHallDataPacket.Citizen(
                        npc.getName(), npc.getSkin(), npc.getJob(), describe(npc)));
            }
        }

        List<CityHallDataPacket.Structure> structures = new ArrayList<>();
        for (BuildingRecord record : records) {
            if (structures.size() >= STRUCTURE_LIMIT) {
                break;
            }
            BlockPos min = record.min();
            structures.add(new CityHallDataPacket.Structure(
                    record.name(), record.dimension(), min.getX(), min.getY(), min.getZ()));
        }

        /* 市民平均心情（mood）这一项**目前不做**：界面上的"幸福度"取自下面
           territory_vitals 的持久值（与凝聚力/忠诚度成套），市民心情暂未纳入设计。
           要恢复时：在上述循环里累加 npc.getMood() 求平均即可。 */

        /* 地图中心：优先这名玩家自己上次看的位置（WorldMapServer 在收到采样请求时记的），
           没记过就让客户端退回市政厅位置（0,0 当"没记过"，见 PlayerProfile.hasMapCenter）。 */
        int mapCenterX = 0;
        int mapCenterZ = 0;
        PlayerProfile viewerProfile = PlayerProfileStore.INSTANCE.byUuid(viewer);
        if (viewerProfile != null && viewerProfile.hasMapCenter()) {
            mapCenterX = viewerProfile.mapCenterX();
            mapCenterZ = viewerProfile.mapCenterZ();
        }

        /* 城名与隶属来自"市政厅所在的这块领地"。
           领地是按区块存的，所以把方块坐标换算成区块再查，正好命中它自己那一块；
           没有领地（或市政厅不在任何领地里）时留空，界面显示"未命名城镇"。 */
        Territory territory = TerritoryRegistry.INSTANCE.byChunk(
                level.dimension().location().toString(),
                hallPos.getX() >> 4, hallPos.getZ() >> 4);
        String townName = territory == null ? "" : territory.name();
        String nationId = territory == null ? "" : territory.nationId();
        Nation nation = NationRegistry.INSTANCE.byId(nationId);
        /* 预置阵营不进 nation 表，byId 会是 null——那就把 id 直接交出去，客户端认得它 */
        String nationLabel = nation != null ? nation.name() : nationId;

        /* 附属地 = 同一面旗下的其它领地数（不含自己）。
           目前一人一地，这个数基本是 0，等放开多领地才有意义。 */
        int vassals = 0;
        if (territory != null && !nationId.isBlank()) {
            for (Territory other : TerritoryRegistry.INSTANCE.all()) {
                if (!other.id().equals(territory.id()) && nationId.equals(other.nationId())) {
                    vassals++;
                }
            }
        }

        /* 民心三值取自 territory_vitals 表（TerritoryVitalsStore）。
           市政厅不在任何领地里时没有对应行，就退回"创建领地时"的默认档：
           凝聚力 100 / 忠诚度 80 / 幸福度 80，与新建领地一致。 */
        TerritoryVitals vitals = territory == null ? null
                : TerritoryVitalsStore.INSTANCE.of(territory.id());
        int unity = vitals == null ? TerritoryVitals.UNITY_DEFAULT : vitals.unity();
        int loyalty = vitals == null ? TerritoryVitals.LOYALTY_DEFAULT : vitals.loyalty();
        int happiness = vitals == null ? TerritoryVitals.HAPPINESS_DEFAULT : vitals.happiness();

        return new CityHallDataPacket(
                townName,
                DateSystem.totalDay(level),
                npcs.size(), 0,             // 人口 / 上限（上限无存储）
                records.size(), 0,          // 建筑 / 上限（同上）
                vassals, 0,                 // 附属地 / 上限
                0L, 0L,                     // 金库 / 上限
                unity, loyalty, happiness,  // 凝聚力 / 忠诚度 / 幸福度（实取）
                nationLabel, "", "", "",    // 隶属 / 盟友 / 敌人 / 政策
                citizens, structures,
                List.of(), List.of(), List.of(),
                mapCenterX, mapCenterZ);
    }

    /** 名录的第二行：职业与年龄，缺哪个就只显示另一个。 */
    private static String describe(NpcData npc) {
        String job = npc.getJob();
        int age = npc.getAge();
        boolean hasJob = job != null && !job.isBlank();
        if (!hasJob) {
            return age > 0 ? age + " 岁" : "";
        }
        return age > 0 ? job + " · " + age + " 岁" : job;
    }
}
