package com.xy2407.kingdom.common.network.building;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.ModMain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：餐厅的菜单卡片与在后厨上班的人数。
 * 食材描述在服务端就拼成一句话，客户端拿到直接显示，不用再解析条目（含 {@code #标签}）。
 */
public record RestaurantMenuPacket(int chefCount, List<Dish> dishes) implements CustomPacketPayload {

    public record Dish(String id, String name, String outputItem, int outputCount,
                       int price, String inputText, boolean ready) {
    }

    private static final int MAX_DISHES = 64;

    public static final CustomPacketPayload.Type<RestaurantMenuPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "restaurant_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestaurantMenuPacket> STREAM_CODEC =
            StreamCodec.of(RestaurantMenuPacket::encode, RestaurantMenuPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, RestaurantMenuPacket packet) {
        buf.writeInt(packet.chefCount());
        buf.writeInt(packet.dishes().size());
        for (Dish dish : packet.dishes()) {
            buf.writeUtf(dish.id() == null ? "" : dish.id(), 64);
            buf.writeUtf(dish.name() == null ? "" : dish.name(), 128);
            buf.writeUtf(dish.outputItem() == null ? "" : dish.outputItem(), 128);
            buf.writeInt(dish.outputCount());
            buf.writeInt(dish.price());
            buf.writeUtf(dish.inputText() == null ? "" : dish.inputText(), 256);
            buf.writeBoolean(dish.ready());
        }
    }

    private static RestaurantMenuPacket decode(RegistryFriendlyByteBuf buf) {
        int chefCount = buf.readInt();
        List<Dish> dishes = new ArrayList<>();
        int count = Math.min(Math.max(buf.readInt(), 0), MAX_DISHES);
        for (int i = 0; i < count; i++) {
            dishes.add(new Dish(buf.readUtf(64), buf.readUtf(128), buf.readUtf(128),
                    buf.readInt(), buf.readInt(), buf.readUtf(256), buf.readBoolean()));
        }
        return new RestaurantMenuPacket(chefCount, dishes);
    }

    public static void handle(RestaurantMenuPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> RestaurantHooks.menuSink.accept(packet));
    }
}
