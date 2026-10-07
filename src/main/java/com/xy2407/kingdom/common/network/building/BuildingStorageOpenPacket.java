package com.xy2407.kingdom.common.network.building;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：仓库界面的展示参数。
 *
 * <p>界面本身由 AUI 的容器菜单打开（槽位、拖拽、shift-click 全归它管），
 * 这个包只负责告诉客户端"这是哪座建筑的仓库、能用几格、整排有多少格"。客户端据此
 * 填标题，并把用不到的那些槽位隐藏掉——<b>格子是页面里静态写死的 54 个</b>，
 * 而容量由蓝图决定，两边对不上的部分只能靠隐藏来解决。</p>
 *
 * @param buildingName 建筑显示名，填在标题上
 * @param capacity     可用格数（蓝图 {@code storage_slots}，已夹在 1..maxSlots）
 * @param maxSlots     页面里实际画了多少格
 */
public record BuildingStorageOpenPacket(String buildingName, int capacity, int maxSlots)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingStorageOpenPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "building_storage_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingStorageOpenPacket> STREAM_CODEC =
            StreamCodec.of(BuildingStorageOpenPacket::encode, BuildingStorageOpenPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, BuildingStorageOpenPacket packet) {
        buf.writeUtf(packet.buildingName() == null ? "" : packet.buildingName());
        buf.writeVarInt(packet.capacity());
        buf.writeVarInt(packet.maxSlots());
    }

    private static BuildingStorageOpenPacket decode(RegistryFriendlyByteBuf buf) {
        return new BuildingStorageOpenPacket(buf.readUtf(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(BuildingStorageOpenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BuildingStorageClientHooks.openSink.accept(packet));
    }
}
