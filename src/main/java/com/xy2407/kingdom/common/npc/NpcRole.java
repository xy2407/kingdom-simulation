package com.xy2407.kingdom.common.npc;

import java.util.Locale;

/**
 * NPC 身份：市民参与城市玩法，游客只进城消费与游荡
 */
public enum NpcRole {

    CITIZEN("市民"),
    TOURIST("游客");

    private final String label;

    NpcRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isTourist() {
        return this == TOURIST;
    }

    public static NpcRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CITIZEN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return CITIZEN;
        }
    }
}
