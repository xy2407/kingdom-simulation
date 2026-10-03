package com.xy2407.kingdom.common.network.db;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 数据库表快照分片，行数过大时按块多次下发
 */
public record DbViewerDataPacket(String table, int chunkIndex, int chunkTotal,
                                 List<String> columns, List<List<String>> rows) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DbViewerDataPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "db_viewer_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DbViewerDataPacket> STREAM_CODEC =
            StreamCodec.of(DbViewerDataPacket::encode, DbViewerDataPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, DbViewerDataPacket packet) {
        buf.writeUtf(packet.table() == null ? "" : packet.table(), 64);
        buf.writeVarInt(packet.chunkIndex());
        buf.writeVarInt(Math.max(1, packet.chunkTotal()));
        List<String> columns = packet.columns();
        buf.writeVarInt(columns == null ? 0 : columns.size());
        for (String column : columns) {
            buf.writeUtf(column == null ? "" : column, 128);
        }
        List<List<String>> rows = packet.rows();
        buf.writeVarInt(rows == null ? 0 : rows.size());
        for (List<String> row : rows) {
            buf.writeVarInt(row == null ? 0 : row.size());
            for (String cell : row) {
                buf.writeUtf(cell == null ? "" : cell, 256);
            }
        }
    }

    private static DbViewerDataPacket decode(RegistryFriendlyByteBuf buf) {
        String table = buf.readUtf(64);
        int chunkIndex = buf.readVarInt();
        int chunkTotal = buf.readVarInt();
        int columnCount = buf.readVarInt();
        List<String> columns = new ArrayList<>(Math.min(columnCount, 512));
        for (int i = 0; i < columnCount; i++) {
            columns.add(buf.readUtf(128));
        }
        int rowCount = buf.readVarInt();
        List<List<String>> rows = new ArrayList<>(Math.min(rowCount, 4096));
        for (int i = 0; i < rowCount; i++) {
            int cellCount = buf.readVarInt();
            List<String> row = new ArrayList<>(Math.min(cellCount, 512));
            for (int j = 0; j < cellCount; j++) {
                row.add(buf.readUtf(256));
            }
            rows.add(row);
        }
        return new DbViewerDataPacket(table, chunkIndex, chunkTotal, columns, rows);
    }

    public static void handle(DbViewerDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> DbViewerHooks.snapshotSink.accept(packet));
    }
}
