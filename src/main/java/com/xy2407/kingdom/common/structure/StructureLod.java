package com.xy2407.kingdom.common.structure;

/**
 * 建筑渲染的细节档位：按包围盒的非空气方块数在细、粗、极粗三档之间选择。
 */
public enum StructureLod {

    FINE(1),
    COARSE(4),
    ROUGH(16);

    private static final int FINE_SOLID_LIMIT = 40 * 40 * 40;
    private static final int COARSE_SOLID_LIMIT = 100 * 100 * 100;

    private final int mergeFactor;

    StructureLod(int mergeFactor) {
        this.mergeFactor = mergeFactor;
    }

    public int mergeFactor() {
        return mergeFactor;
    }

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
