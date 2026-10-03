package com.xy2407.kingdom.common.npc;

import java.math.BigDecimal;

/**
 * 身份卡片展示用的市民资料值对象
 */
public record NpcIdentifyInfo(
        String name,
        String nation,
        Integer age,
        String occupation,
        String gender,
        String liege,
        Stat power,
        Stat wisdom,
        Stat agility,
        String status,
        Stat health,
        Stat hunger) {

    public static final String UNKNOWN = "无";

    public static final NpcIdentifyInfo EMPTY = new NpcIdentifyInfo(
            null, null, null, null, null, null,
            Stat.ABSENT, Stat.ABSENT, Stat.ABSENT,
            null, Stat.ABSENT, Stat.ABSENT);

    public String ageLine() {
        StringBuilder line = new StringBuilder();
        if (age != null) {
            line.append(age);
        }
        append(line, occupation);
        append(line, gender);
        return line.isEmpty() ? null : line.toString();
    }

    private static void append(StringBuilder line, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!line.isEmpty()) {
            line.append(" · ");
        }
        line.append(part.trim());
    }

    public record Stat(Double current, Double max) {

        public static final Stat ABSENT = new Stat(null, null);

        public boolean present() {
            return current != null && max != null && max > 0.0D;
        }

        public double ratio() {
            if (!present()) {
                return 0.0D;
            }
            return Math.max(0.0D, Math.min(1.0D, current / max));
        }

        public String currentText() {
            return format(current);
        }

        public String maxText() {
            return format(max);
        }

        public String currentAttribute() {
            String text = format(current);
            return text == null ? "" : text;
        }

        public String maxAttribute() {
            String text = format(max);
            return text == null ? "" : text;
        }

        private static String format(Double value) {
            if (value == null || value.isNaN() || value.isInfinite()) {
                return null;
            }
            if (value == Math.rint(value) && Math.abs(value) < 1.0E9D) {
                return Long.toString(value.longValue());
            }
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
    }
}
