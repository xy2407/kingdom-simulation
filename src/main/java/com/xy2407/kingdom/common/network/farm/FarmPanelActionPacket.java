package com.xy2407.kingdom.common.network.farm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.common.farm.FarmRegion;
import com.xy2407.kingdom.server.farm.FarmPanelService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：一次农田操作，用一个包按 action 区分，字段按对应构造方法取用
 */
public record FarmPanelActionPacket(int action, UUID plotId, String text, int value,
                                    int minX, int minZ, int maxX, int maxZ, String workers, String regions)
        implements CustomPacketPayload {

    public static final int ACTION_CREATE = 0;
    public static final int ACTION_SET_SLOTS = 1;
    public static final int ACTION_SET_CROP = 2;
    public static final int ACTION_TOGGLE_WORKER = 3;
    public static final int ACTION_SET_AREA = 4;
    public static final int ACTION_DELETE = 5;
    /** 请服务端打开这座建筑的仓库界面（面板底栏的「打开仓库」）。 */
    public static final int ACTION_OPEN_STORAGE = 6;

    public static final CustomPacketPayload.Type<FarmPanelActionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "farm_panel_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FarmPanelActionPacket> STREAM_CODEC =
            StreamCodec.of(FarmPanelActionPacket::encode, FarmPanelActionPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 一次成型：核心矩形、附加块、格数、作物、打理人一起提交，避免创建后再补三四次包。 */
    public static FarmPanelActionPacket create(int slots, String cropId, String workerIds,
                                               int minX, int minZ, int maxX, int maxZ,
                                               List<FarmRegion> extra) {
        return new FarmPanelActionPacket(ACTION_CREATE, null, cropId == null ? "" : cropId,
                slots, minX, minZ, maxX, maxZ, workerIds == null ? "" : workerIds,
                FarmRegion.encode(extra));
    }

    public static FarmPanelActionPacket create(int slots, String cropId, String workerIds,
                                               int minX, int minZ, int maxX, int maxZ) {
        return create(slots, cropId, workerIds, minX, minZ, maxX, maxZ, List.of());
    }

    public static FarmPanelActionPacket setSlots(UUID plotId, int slots) {
        return new FarmPanelActionPacket(ACTION_SET_SLOTS, plotId, "", slots, 0, 0, 0, 0, "", "");
    }

    public static FarmPanelActionPacket setCrop(UUID plotId, String cropId) {
        return new FarmPanelActionPacket(ACTION_SET_CROP, plotId, cropId == null ? "" : cropId,
                0, 0, 0, 0, 0, "", "");
    }

    public static FarmPanelActionPacket toggleWorker(UUID plotId, UUID workerId) {
        return new FarmPanelActionPacket(ACTION_TOGGLE_WORKER, plotId,
                workerId == null ? "" : workerId.toString(), 0, 0, 0, 0, 0, "", "");
    }

    public static FarmPanelActionPacket setArea(UUID plotId, int minX, int minZ, int maxX, int maxZ,
                                                List<FarmRegion> extra) {
        return new FarmPanelActionPacket(ACTION_SET_AREA, plotId, "", 0, minX, minZ, maxX, maxZ, "",
                FarmRegion.encode(extra));
    }

    public static FarmPanelActionPacket setArea(UUID plotId, int minX, int minZ, int maxX, int maxZ) {
        return setArea(plotId, minX, minZ, maxX, maxZ, List.of());
    }

    public static FarmPanelActionPacket delete(UUID plotId) {
        return new FarmPanelActionPacket(ACTION_DELETE, plotId, "", 0, 0, 0, 0, 0, "", "");
    }

    /** 请服务端把当前界面换成这座建筑的仓库。不需要任何参数——建筑由服务端按玩家位置判定。 */
    public static FarmPanelActionPacket openStorage() {
        return new FarmPanelActionPacket(ACTION_OPEN_STORAGE, null, "", 0, 0, 0, 0, 0, "", "");
    }

    public List<FarmRegion> extraRegions() {
        return FarmRegion.decode(regions);
    }

    /** 逗号分隔的打理人列表；解析失败的单条直接跳过。 */
    public List<UUID> workerIds() {
        List<UUID> ids = new ArrayList<>();
        if (workers == null || workers.isBlank()) {
            return ids;
        }
        for (String part : workers.split(",")) {
            try {
                ids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return ids;
    }

    private static void encode(RegistryFriendlyByteBuf buf, FarmPanelActionPacket packet) {
        buf.writeVarInt(packet.action());
        buf.writeUUID(packet.plotId() == null ? new UUID(0L, 0L) : packet.plotId());
        buf.writeUtf(packet.text() == null ? "" : packet.text(), 128);
        buf.writeInt(packet.value());
        buf.writeInt(packet.minX());
        buf.writeInt(packet.minZ());
        buf.writeInt(packet.maxX());
        buf.writeInt(packet.maxZ());
        buf.writeUtf(packet.workers() == null ? "" : packet.workers(), 512);
        buf.writeUtf(packet.regions() == null ? "" : packet.regions(), 4096);
    }

    private static FarmPanelActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new FarmPanelActionPacket(buf.readVarInt(), buf.readUUID(), buf.readUtf(128), buf.readInt(),
                buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readUtf(512), buf.readUtf(4096));
    }

    public UUID plotIdOrNull() {
        return plotId == null || (plotId.getMostSignificantBits() == 0L && plotId.getLeastSignificantBits() == 0L)
                ? null : plotId;
    }

    public static void handle(FarmPanelActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                FarmPanelService.apply(player, packet);
            }
        });
    }
}
