package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;
import com.xy2407.kingdom.server.building.BuildingStorageMenu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请打开当前所处建筑的仓库（无参）。
 *
 * <p>任何建筑面板底栏的「打开仓库」都发这一个包，农业 / 工业 / 养殖 / 商店共用。</p>
 *
 * <p><b>建筑由服务端按玩家实际位置判定</b>，不采信客户端传来的坐标或建筑 id——
 * 这是权限边界（官方容器文档「安全」一节：距离与 capability 检查都在服务端，
 * 业务权限也要自己在打开前查）。客户端能做的只是"请求打开"。</p>
 *
 * <p>空包用 {@link StreamCodec#unit}，不占字节。</p>
 */
public record BuildingStorageRequestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingStorageRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_storage_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingStorageRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new BuildingStorageRequestPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuildingStorageRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                BuildingStorageMenu.openFor(player);
            }
        });
    }
}
