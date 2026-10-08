package com.xy2407.kingdom.common.farm;

import java.util.ArrayList;
import java.util.List;

/**
 * 农田附加地块：核心矩形之外、由玩家多次框选补出来的散落部分
 */
public record FarmRegion(int minX, int minZ, int maxX, int maxZ) {

    public static final int MAX_REGIONS = 32;

    public FarmRegion {
        int lowX = Math.min(minX, maxX);
        int highX = Math.max(minX, maxX);
        int lowZ = Math.min(minZ, maxZ);
        int highZ = Math.max(minZ, maxZ);
        minX = lowX;
        maxX = highX;
        minZ = lowZ;
        maxZ = highZ;
    }

    public int cells() {
        return (maxX - minX + 1) * (maxZ - minZ + 1);
    }

    public static String encode(List<FarmRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (FarmRegion region : regions) {
            if (region == null || builder.length() >= 4000) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(region.minX()).append(',').append(region.minZ()).append(',')
                    .append(region.maxX()).append(',').append(region.maxZ());
        }
        return builder.toString();
    }

    public static List<FarmRegion> decode(String raw) {
        List<FarmRegion> regions = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return regions;
        }
        for (String part : raw.split(";")) {
            if (regions.size() >= MAX_REGIONS) {
                break;
            }
            String[] fields = part.split(",");
            if (fields.length != 4) {
                continue;
            }
            try {
                regions.add(new FarmRegion(Integer.parseInt(fields[0].trim()),
                        Integer.parseInt(fields[1].trim()),
                        Integer.parseInt(fields[2].trim()),
                        Integer.parseInt(fields[3].trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return regions;
    }
}
