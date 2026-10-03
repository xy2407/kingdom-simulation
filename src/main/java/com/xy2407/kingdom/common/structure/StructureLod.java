package com.xy2407.kingdom.common.structure;

/**
 * 建筑渲染的细节档位，按包围盒体积选择。
 *
 * <p>合并倍率表示每 N×N×N 个方块合并成一个体素参与渲染。合并后同一张方块纹理铺满
 * 整个体素，因此档位越粗，一张纹理覆盖的实际范围越大，顶点数按 N³ 下降。</p>
 *
 * <p>精细档走逐方块的完整方块模型，楼梯、台阶、栅栏这些非满方块形状都正确；
 * 粗档只保留主导方块的外观，用来换取大建筑的可渲染性。</p>
 */
public enum StructureLod {

    /** 逐方块，完整方块模型。 */
    FINE(1),
    /** 每 4×4×4 合并成一个体素。 */
    COARSE(4),
    /** 每 16×16×16 合并成一个体素。 */
    ROUGH(16);

    /** 非空气方块上限：40³ 以内精细逐方块。 */
    private static final int FINE_SOLID_LIMIT = 40 * 40 * 40;
    /** 非空气方块上限：100³ 以内粗；超过则更粗。 */
    private static final int COARSE_SOLID_LIMIT = 100 * 100 * 100;

    private final int mergeFactor;

    StructureLod(int mergeFactor) {
        this.mergeFactor = mergeFactor;
    }

    public int mergeFactor() {
        return mergeFactor;
    }

    /** 按非空气方块数选档。 */
    public static StructureLod forVolume(int solidBlockCount) {
        if (solidBlockCount <= FINE_SOLID_LIMIT) {
            return FINE;
        }
        if (solidBlockCount <= COARSE_SOLID_LIMIT) {
            return COARSE;
        }
        return ROUGH;
    }
}