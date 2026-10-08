package com.xy2407.kingdom.common.faction;

/**
 * 一个国家（阵营）：预置国由客户端硬编码，自建国落在 nation 表里且永久保留。
 */
public record Nation(String id, String name, String ruler, String motto,
                     String colorLeft, String colorRight,
                     String emblem, String emblemColor,
                     String founderUuid, boolean permanent, int createdDay) {

    public Nation {
        colorLeft = colorLeft == null ? "" : colorLeft;
        colorRight = colorRight == null ? "" : colorRight;
        emblem = emblem == null ? "" : emblem;
        emblemColor = emblemColor == null ? "" : emblemColor;
        founderUuid = founderUuid == null ? "" : founderUuid;
    }

    public boolean isPlayerFounded() {
        return permanent;
    }
}
