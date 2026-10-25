package com.xy2407.kingdom.common.employment;

import java.util.UUID;

/**
 * 一条雇佣关系：哪位市民在哪个岗位上
 */
public record Employment(UUID npcId, EmploymentTarget target) {

    public static Employment of(UUID npcId, EmploymentTarget target) {
        return new Employment(npcId, target);
    }
}
