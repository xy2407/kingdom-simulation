package com.xy2407.kingdom.common.path;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

import com.github.luben.zstd.Zstd;

/**
 * 单元快照落盘编码：写入 zstd，兼容读取旧 GZIP 数据。
 */
public final class SnapshotCodec {

    public static final int FORMAT_GZIP = 1;
    public static final int FORMAT_ZSTD = 2;

    private SnapshotCodec() {
    }

    public static boolean zstdAvailable() {
        return true;
    }

    public static int format() {
        return FORMAT_ZSTD;
    }

    public static byte[] compress(byte[] raw) {
        if (raw == null) {
            return null;
        }
        try {
            byte[] payload = Zstd.compress(raw);
            byte[] framed = new byte[payload.length + 4];
            framed[0] = (byte) (raw.length >>> 24);
            framed[1] = (byte) (raw.length >>> 16);
            framed[2] = (byte) (raw.length >>> 8);
            framed[3] = (byte) raw.length;
            System.arraycopy(payload, 0, framed, 4, payload.length);
            return framed;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    public static byte[] decompress(byte[] data, int format) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (format == FORMAT_ZSTD) {
            if (data.length <= 4) {
                return null;
            }
            int rawLength = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                    | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            if (rawLength <= 0) {
                return null;
            }
            try {
                byte[] payload = new byte[data.length - 4];
                System.arraycopy(data, 4, payload, 0, payload.length);
                return Zstd.decompress(payload, rawLength);
            } catch (RuntimeException failure) {
                return null;
            }
        }
        return gunzip(data);
    }

    private static byte[] gunzip(byte[] data) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gzip.readAllBytes();
        } catch (IOException failure) {
            return null;
        }
    }
}
