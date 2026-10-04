package com.xy2407.kingdom.server.cityhall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.faction.Nation;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.network.cityhall.CityHallDataPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.faction.NationRegistry;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;

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
     * @param hallPos 市政厅方块位置。当前只用于将来"按城镇/领地过滤建筑"，
     *                现在没有归属概念，所以计入全部已完工建筑
     */
    public static CityHallDataPacket collect(ServerLevel level, BlockPos hallPos) {
        Collection<NpcData> npcs = NpcManager.INSTANCE.allNpcs();
        List<BuildingRecord> records = BuildingRegistry.INSTANCE.all();

        List<CityHallDataPacket.Citizen> citizens = new ArrayList<>();
        double moodSum = 0.0;
        int moodCount = 0;
        for (NpcData npc : npcs) {
            /* 心情要按全量平均，不受名录条数上限影响 */
            moodSum += npc.getMood();
            moodCount++;
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

        int happiness = moodCount == 0 ? 0 : (int) Math.round(moodSum / moodCount);

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

        return new CityHallDataPacket(
                townName,
                DateSystem.totalDay(level),
                npcs.size(), 0,             // 人口 / 上限（上限无存储）
                records.size(), 0,          // 建筑 / 上限（同上）
                vassals, 0,                 // 附属地 / 上限
                0L, 0L,                     // 金库 / 上限
                0, 0, happiness,            // 凝聚力 / 忠诚度 / 幸福度
                nationLabel, "", "", "",    // 隶属 / 盟友 / 敌人 / 政策
                citizens, structures,
                List.of(), List.of(), List.of());
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
