package com.xy2407.kingdom.common.path;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * 16³ 单元快照：按列分组条目，空单元共享 EMPTY，序列化交由 SnapshotCodec 处理。
 */
public final class UnitSnapshot {

    public static final byte KIND_WALK = 0;
    public static final byte KIND_CLIMB = 1;
    public static final byte KIND_HALF = 2;

    public static final byte HAZARD_NONE = 0;
    public static final byte HAZARD_WATER = 1;
    public static final byte HAZARD_LAVA = 2;
    public static final byte HAZARD_FIRE = 3;
    public static final byte HAZARD_POWDER = 4;

    private static final byte FLAG_CACTUS = 1;
    private static final byte FLAG_ROAD = 1;
    private static final int COLUMNS = 256;
    private static final int SCHEMA = 2;

    public static final UnitSnapshot EMPTY = new UnitSnapshot();

    private final short[] standStarts;
    private final byte[] standY;
    private final byte[] standKind;
    private final byte[] standHazard;
    private final byte[] standRoad;
    private final short[] blockStarts;
    private final byte[] blockY;
    private final byte[] blockFlags;

    private UnitSnapshot() {
        this.standStarts = new short[COLUMNS + 1];
        this.standY = new byte[0];
        this.standKind = new byte[0];
        this.standHazard = new byte[0];
        this.standRoad = new byte[0];
        this.blockStarts = new short[COLUMNS + 1];
        this.blockY = new byte[0];
        this.blockFlags = new byte[0];
    }

    private UnitSnapshot(short[] standStarts, byte[] standY, byte[] standKind, byte[] standHazard,
                         byte[] standRoad, short[] blockStarts, byte[] blockY, byte[] blockFlags) {
        this.standStarts = standStarts;
        this.standY = standY;
        this.standKind = standKind;
        this.standHazard = standHazard;
        this.standRoad = standRoad;
        this.blockStarts = blockStarts;
        this.blockY = blockY;
        this.blockFlags = blockFlags;
    }

    public boolean isAir() {
        return this == EMPTY || (standY.length == 0 && blockY.length == 0);
    }

    public boolean hasStand() {
        return standY.length > 0;
    }

    public int standCount() {
        return standY.length;
    }

    public int blockCount() {
        return blockY.length;
    }

    public boolean standable(int lx, int ly, int lz) {
        int column = lx * 16 + lz;
        for (int i = standStarts[column]; i < standStarts[column + 1]; i++) {
            if ((standY[i] & 0xFF) == ly) {
                return true;
            }
        }
        return false;
    }

    public boolean blocked(int lx, int ly, int lz) {
        int column = lx * 16 + lz;
        for (int i = blockStarts[column]; i < blockStarts[column + 1]; i++) {
            if ((blockY[i] & 0xFF) == ly) {
                return true;
            }
        }
        return false;
    }

    public boolean climbable(int lx, int ly, int lz) {
        int column = lx * 16 + lz;
        for (int i = standStarts[column]; i < standStarts[column + 1]; i++) {
            if ((standY[i] & 0xFF) == ly) {
                return standKind[i] == KIND_CLIMB;
            }
        }
        return false;
    }

    public boolean halfFloor(int lx, int ly, int lz) {
        int column = lx * 16 + lz;
        for (int i = standStarts[column]; i < standStarts[column + 1]; i++) {
            if ((standY[i] & 0xFF) == ly) {
                return standKind[i] == KIND_HALF;
            }
        }
        return false;
    }

    public byte standHazard(int lx, int ly, int lz) {
        int column = lx * 16 + lz;
        for (int i = standStarts[column]; i < standStarts[column + 1]; i++) {
            if ((standY[i] & 0xFF) == ly) {
                return standHazard[i];
            }
        }
        return HAZARD_NONE;
    }

    public boolean blockedCactus(int lx, int ly, int lz) {
        int column = lx * 16 + lz;
        for (int i = blockStarts[column]; i < blockStarts[column + 1]; i++) {
            if ((blockY[i] & 0xFF) == ly) {
                return (blockFlags[i] & FLAG_CACTUS) != 0;
            }
        }
        return false;
    }

    public boolean onRoad(int lx, int ly, int lz) {
        int column = lx * 16 + lz;
        for (int i = standStarts[column]; i < standStarts[column + 1]; i++) {
            if ((standY[i] & 0xFF) == ly) {
                return (standRoad[i] & FLAG_ROAD) != 0;
            }
        }
        return false;
    }

    public byte[] serialize() {
        return SnapshotCodec.compress(toByteArray());
    }

    private byte[] toByteArray() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, SCHEMA);
        for (short value : standStarts) {
            writeShort(out, value);
        }
        writeInt(out, standY.length);
        for (int i = 0; i < standY.length; i++) {
            out.write(standY[i]);
            out.write(standKind[i]);
            out.write(standHazard[i]);
            out.write(standRoad[i]);
        }
        for (short value : blockStarts) {
            writeShort(out, value);
        }
        writeInt(out, blockY.length);
        for (int i = 0; i < blockY.length; i++) {
            out.write(blockY[i]);
            out.write(blockFlags[i]);
        }
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, short value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    public static UnitSnapshot deserialize(byte[] data, int format) {
        byte[] raw = SnapshotCodec.decompress(data, format);
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            int cursor = 0;
            if (readInt(raw, cursor) != SCHEMA) {
                return null;
            }
            cursor += 4;
            short[] standStarts = readShorts(raw, cursor, COLUMNS + 1);
            cursor += (COLUMNS + 1) * 2;
            int standCount = readInt(raw, cursor);
            cursor += 4;
            if (standCount < 0 || cursor + standCount * 4 + (COLUMNS + 1) * 2 + 4 > raw.length) {
                return null;
            }
            byte[] standY = new byte[standCount];
            byte[] standKind = new byte[standCount];
            byte[] standHazard = new byte[standCount];
            byte[] standRoad = new byte[standCount];
            for (int i = 0; i < standCount; i++) {
                standY[i] = raw[cursor++];
                standKind[i] = raw[cursor++];
                standHazard[i] = raw[cursor++];
                standRoad[i] = raw[cursor++];
            }
            short[] blockStarts = readShorts(raw, cursor, COLUMNS + 1);
            cursor += (COLUMNS + 1) * 2;
            int blockCount = readInt(raw, cursor);
            cursor += 4;
            if (blockCount < 0 || cursor + blockCount * 2 > raw.length) {
                return null;
            }
            byte[] blockY = new byte[blockCount];
            byte[] blockFlags = new byte[blockCount];
            for (int i = 0; i < blockCount; i++) {
                blockY[i] = raw[cursor++];
                blockFlags[i] = raw[cursor++];
            }
            if (standCount == 0 && blockCount == 0) {
                return EMPTY;
            }
            return new UnitSnapshot(standStarts, standY, standKind, standHazard, standRoad,
                    blockStarts, blockY, blockFlags);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static short[] readShorts(byte[] raw, int offset, int count) {
        short[] out = new short[count];
        for (int i = 0; i < count; i++) {
            out[i] = (short) (((raw[offset + i * 2] & 0xFF) << 8) | (raw[offset + i * 2 + 1] & 0xFF));
        }
        return out;
    }

    private static int readInt(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 24) | ((raw[offset + 1] & 0xFF) << 16)
                | ((raw[offset + 2] & 0xFF) << 8) | (raw[offset + 3] & 0xFF);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final short[] standStarts = new short[COLUMNS + 1];
        private final short[] blockStarts = new short[COLUMNS + 1];
        private byte[] standY = new byte[64];
        private byte[] standKind = new byte[64];
        private byte[] standHazard = new byte[64];
        private byte[] standRoad = new byte[64];
        private byte[] blockY = new byte[64];
        private byte[] blockFlags = new byte[64];
        private int standCount;
        private int blockCount;

        public Builder() {
            Arrays.fill(standStarts, (short) -1);
            Arrays.fill(blockStarts, (short) -1);
        }

        public void beginColumn(int column) {
            if (standStarts[column] < 0) {
                standStarts[column] = (short) standCount;
            }
            if (blockStarts[column] < 0) {
                blockStarts[column] = (short) blockCount;
            }
        }

        public void addStand(int y, byte kind, byte hazard, boolean road) {
            if (standCount >= standY.length) {
                standY = grow(standY);
                standKind = grow(standKind);
                standHazard = grow(standHazard);
                standRoad = grow(standRoad);
            }
            standY[standCount] = (byte) y;
            standKind[standCount] = kind;
            standHazard[standCount] = hazard;
            standRoad[standCount] = road ? FLAG_ROAD : 0;
            standCount++;
        }

        public void addBlock(int y, boolean cactus) {
            if (blockCount >= blockY.length) {
                blockY = grow(blockY);
                blockFlags = grow(blockFlags);
            }
            blockY[blockCount] = (byte) y;
            blockFlags[blockCount] = cactus ? FLAG_CACTUS : 0;
            blockCount++;
        }

        public UnitSnapshot build() {
            backfill(standStarts, standCount);
            backfill(blockStarts, blockCount);
            if (standCount == 0 && blockCount == 0) {
                return EMPTY;
            }
            return new UnitSnapshot(Arrays.copyOf(standStarts, COLUMNS + 1),
                    Arrays.copyOf(standY, standCount), Arrays.copyOf(standKind, standCount),
                    Arrays.copyOf(standHazard, standCount), Arrays.copyOf(standRoad, standCount),
                    Arrays.copyOf(blockStarts, COLUMNS + 1), Arrays.copyOf(blockY, blockCount),
                    Arrays.copyOf(blockFlags, blockCount));
        }

        private static void backfill(short[] starts, int count) {
            int running = count;
            for (int c = COLUMNS; c >= 0; c--) {
                if (starts[c] < 0) {
                    starts[c] = (short) running;
                } else {
                    running = starts[c];
                }
            }
        }

        private static byte[] grow(byte[] source) {
            byte[] out = new byte[source.length * 2];
            System.arraycopy(source, 0, out, 0, source.length);
            return out;
        }
    }
}
