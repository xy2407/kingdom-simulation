package com.xy2407.kingdom.common.faction;

import java.util.UUID;

/**
 * 一块领地。
 *
 * <p><b>领地是国家下面的子集</b>：{@code nationId} 指向玩家首登时选定的国家；
 * 玩家自己建国的话，指向的就是自己建的那个。领地本身<b>没有独立的外交</b>——
 * 隶属、盟友、敌人、政策这些全部读它所属的国家。</p>
 *
 * <p><b>范围按区块记</b>：以市政厅所在区块为中心、向四个方向各扩 {@link #CHUNK_RADIUS}
 * 格，即 5×5 共 {@link #CHUNK_COUNT} 个水平区块。四个角点存成闭区间，
 * 判定"某区块是否属于这块领地"就是一次整数比较，不必扫方块。</p>
 *
 * @param id          唯一标识
 * @param name        领地名称（玩家在 city-create 界面输入）
 * @param nationId    所属国家 id
 * @param owner       领地持有人
 * @param dimension   所在维度
 * @param centerX     市政厅方块的世界 X（同时决定中心区块）
 * @param centerZ     市政厅方块的世界 Z
 * @param minChunkX   领地最小区块 X（含）
 * @param minChunkZ   领地最小区块 Z（含）
 * @param maxChunkX   领地最大区块 X（含）
 * @param maxChunkZ   领地最大区块 Z（含）
 * @param createdDay  创建时的世界天数
 */
public record Territory(String id, String name, String nationId, UUID owner,
                        String dimension, int centerX, int centerZ,
                        int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                        int createdDay) {

    /**
     * 领地半径，单位是区块。
     *
     * <p>2 表示"市政厅所在区块向四周各扩 2 格"，即边长 5 的方阵——
     * 这是需求里定死的 5×5 = 25 区块，改它等于改领地的地界规则，别随手动。</p>
     */
    public static final int CHUNK_RADIUS = 2;

    /** 领地覆盖的水平区块数：5×5 = 25。 */
    public static final int CHUNK_COUNT = (CHUNK_RADIUS * 2 + 1) * (CHUNK_RADIUS * 2 + 1);

    /** 某水平区块是否落在这块领地里（闭区间）。 */
    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX
                && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    /** 世界坐标某点是否落在领地里。 */
    public boolean containsBlock(double x, double z) {
        return containsChunk((int) Math.floor(x / 16.0), (int) Math.floor(z / 16.0));
    }

    /** 边长，区块数。 */
    public int chunkSpan() {
        return maxChunkX - minChunkX + 1;
    }
}
