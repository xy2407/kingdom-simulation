package com.xy2407.kingdom.server.cityhall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.xy2407.kingdom.common.building.BuildingRecord;
import com.xy2407.kingdom.common.date.DateSystem;
import com.xy2407.kingdom.common.faction.Nation;
import com.xy2407.kingdom.common.faction.Territory;
import com.xy2407.kingdom.common.faction.TerritoryVitals;
import com.xy2407.kingdom.common.network.cityhall.CityHallDataPacket;
import com.xy2407.kingdom.common.npc.NpcData;
import com.xy2407.kingdom.common.npc.NpcManager;
import com.xy2407.kingdom.server.building.BuildingRegistry;
import com.xy2407.kingdom.server.faction.NationRegistry;
import com.xy2407.kingdom.server.faction.TerritoryRegistry;
import com.xy2407.kingdom.server.faction.TerritoryVitalsStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 把服务端的城镇数据打包成 CityHallDataPacket：能取的取真值，取不到的给中性值。
 */
public final class CityHallDataCollector {

    private static final int CITIZEN_LIMIT = 200;
    private static final int STRUCTURE_LIMIT = 200;

    private CityHallDataCollector() {
    }

    public static CityHallDataPacket collect(ServerLevel level, BlockPos hallPos) {
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


        Territory territory = TerritoryRegistry.INSTANCE.byChunk(
                level.dimension().location().toString(),
                hallPos.getX() >> 4, hallPos.getZ() >> 4);
        String townName = territory == null ? "" : territory.name();
        String nationId = territory == null ? "" : territory.nationId();
        Nation nation = NationRegistry.INSTANCE.byId(nationId);
        String nationLabel = nation != null ? nation.name() : nationId;

        int vassals = 0;
        if (territory != null && !nationId.isBlank()) {
            for (Territory other : TerritoryRegistry.INSTANCE.all()) {
                if (!other.id().equals(territory.id()) && nationId.equals(other.nationId())) {
                    vassals++;
                }
            }
        }

        TerritoryVitals vitals = territory == null ? null
                : TerritoryVitalsStore.INSTANCE.of(territory.id());
        int unity = vitals == null ? TerritoryVitals.UNITY_DEFAULT : vitals.unity();
        int loyalty = vitals == null ? TerritoryVitals.LOYALTY_DEFAULT : vitals.loyalty();
        int happiness = vitals == null ? TerritoryVitals.HAPPINESS_DEFAULT : vitals.happiness();

        return new CityHallDataPacket(
                townName,
                DateSystem.totalDay(level),
                npcs.size(), 0,
                records.size(), 0,
                vassals, 0,
                0L, 0L,
                unity, loyalty, happiness,
                nationLabel, "", "", "",
                citizens, structures,
                List.of(), List.of(), List.of());
    }

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
