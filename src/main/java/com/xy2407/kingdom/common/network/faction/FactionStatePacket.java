package com.xy2407.kingdom.common.network.faction;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.ModMain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：玩家的阵营状态，以及接下来该弹哪张界面（首登、自建国列表、建城）。
 */
public record FactionStatePacket(
        boolean onboarded,
        String nationId,
        String territoryId,
        String territoryName,
        boolean openCityCreate,
        int hallX,
        int hallY,
        int hallZ,
        List<NationEntry> nations
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FactionStatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModMain.MOD_ID, "faction_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionStatePacket> STREAM_CODEC =
            StreamCodec.of(FactionStatePacket::encode, FactionStatePacket::decode);

    public FactionStatePacket {
        nationId = nationId == null ? "" : nationId;
        territoryId = territoryId == null ? "" : territoryId;
        territoryName = territoryName == null ? "" : territoryName;
        nations = nations == null ? List.of() : List.copyOf(nations);
    }

    public static FactionStatePacket state(boolean onboarded, String nationId,
                                           String territoryId, String territoryName,
                                           List<NationEntry> nations) {
        return new FactionStatePacket(onboarded, nationId, territoryId, territoryName,
                false, 0, 0, 0, nations);
    }

    public static FactionStatePacket openCityCreate(boolean onboarded, String nationId,
                                                    int hallX, int hallY, int hallZ,
                                                    List<NationEntry> nations) {
        return new FactionStatePacket(onboarded, nationId, "", "",
                true, hallX, hallY, hallZ, nations);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record NationEntry(String id, String name, String ruler, String motto,
                              String colorLeft, String colorRight,
                              String emblem, String emblemColor) {
    }

    private static void encode(RegistryFriendlyByteBuf buf, FactionStatePacket packet) {
        buf.writeNbt(packet.toTag());
    }

    private static FactionStatePacket decode(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        return tag == null ? new FactionStatePacket(false, "", "", "", false, 0, 0, 0, List.of())
                : fromTag(tag);
    }

    private CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        root.putBoolean("onboarded", onboarded);
        root.putString("nationId", nationId);
        root.putString("territoryId", territoryId);
        root.putString("territoryName", territoryName);
        root.putBoolean("openCityCreate", openCityCreate);
        root.putInt("hallX", hallX);
        root.putInt("hallY", hallY);
        root.putInt("hallZ", hallZ);

        ListTag list = new ListTag();
        for (NationEntry nation : nations) {
            CompoundTag item = new CompoundTag();
            item.putString("id", nation.id());
            item.putString("name", nation.name());
            item.putString("ruler", nation.ruler());
            item.putString("motto", nation.motto());
            item.putString("colorLeft", nation.colorLeft());
            item.putString("colorRight", nation.colorRight());
            item.putString("emblem", nation.emblem());
            item.putString("emblemColor", nation.emblemColor());
            list.add(item);
        }
        root.put("nations", list);
        return root;
    }

    private static FactionStatePacket fromTag(CompoundTag root) {
        List<NationEntry> nations = new ArrayList<>();
        ListTag list = root.getList("nations", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag item = list.getCompound(index);
            nations.add(new NationEntry(
                    item.getString("id"), item.getString("name"),
                    item.getString("ruler"), item.getString("motto"),
                    item.getString("colorLeft"), item.getString("colorRight"),
                    item.getString("emblem"), item.getString("emblemColor")));
        }
        return new FactionStatePacket(
                root.getBoolean("onboarded"),
                root.getString("nationId"),
                root.getString("territoryId"),
                root.getString("territoryName"),
                root.getBoolean("openCityCreate"),
                root.getInt("hallX"),
                root.getInt("hallY"),
                root.getInt("hallZ"),
                nations);
    }

    public static void handle(FactionStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> FactionHooks.stateSink.accept(packet));
    }
}
