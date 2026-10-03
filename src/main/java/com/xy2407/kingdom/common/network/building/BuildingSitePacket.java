package com.xy2407.kingdom.common.network.building;

import java.util.UUID;

import com.xy2407.kingdom.ModMain;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：施工点新增 / 移除。
 *
 * <p>客户端收到新增后在整栋建筑的施工期间持续渲染半透明虚影，收到移除（完工或中止）时清理。
 * 与玩家自己那份"放置投影"不同：放置投影在右键暂存后就交给服务端了，这里管的是任务期间那一段。</p>
 */
public record BuildingSitePacket(UUID taskId, boolean remove, String blueprintName,
                                 BlockPos origin, int rotation) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingSitePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_site"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingSitePacket> STREAM_CODEC =
            StreamCodec.of(BuildingSitePacket::encode, BuildingSitePacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingSitePacket packet) {
        buf.writeUUID(packet.taskId() == null ? new UUID(0L, 0L) : packet.taskId());
        buf.writeBoolean(packet.remove());
        buf.writeUtf(packet.blueprintName() == null ? "" : packet.blueprintName(), 256);
        BlockPos origin = packet.origin();
        buf.writeInt(origin == null ? 0 : origin.getX());
        buf.writeInt(origin == null ? 0 : origin.getY());
        buf.writeInt(origin == null ? 0 : origin.getZ());
        buf.writeInt(packet.rotation());
    }

    private static BuildingSitePacket decode(RegistryFriendlyByteBuf buf) {
        UUID taskId = buf.readUUID();
        boolean remove = buf.readBoolean();
        String name = buf.readUtf(256);
        BlockPos origin = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        return new BuildingSitePacket(taskId, remove, name, origin, buf.readInt());
    }

    public static void handle(BuildingSitePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuildingSiteHooks.siteSink.accept(packet));
    }
}
