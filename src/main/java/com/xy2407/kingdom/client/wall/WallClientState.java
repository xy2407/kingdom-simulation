package com.xy2407.kingdom.client.wall;

import java.util.ArrayList;
import java.util.List;

import com.xy2407.kingdom.client.building.BuildingPlacementManager;
import com.xy2407.kingdom.client.rts.RtsModeManager;
import com.xy2407.kingdom.client.rts.RtsPicker;
import com.xy2407.kingdom.common.network.wall.WallDeletePacket;
import com.xy2407.kingdom.common.network.wall.WallPickPacket;
import com.xy2407.kingdom.common.network.wall.WallPickResultPacket;
import com.xy2407.kingdom.common.network.wall.WallStatePacket;
import com.xy2407.kingdom.common.network.wall.WallStateRequestPacket;
import com.xy2407.kingdom.common.network.wall.WallSubmitPacket;
import com.xy2407.kingdom.common.structure.StructureModel;
import com.xy2407.kingdom.common.wall.WallBlock;
import com.xy2407.kingdom.common.wall.WallDefaults;
import com.xy2407.kingdom.common.wall.WallKind;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 城墙客户端状态：服务端快照缓存、当前放置类型、选中部件与提交/删除动作
 */
@OnlyIn(Dist.CLIENT)
public final class WallClientState {

    private static final List<WallStatePacket.NodeView> NODES = new ArrayList<>();
    private static final List<WallStatePacket.SegmentView> SEGMENTS = new ArrayList<>();
    /** 当前选中任务的全量方块（逐方块选中/高亮），空 = 无选中 */
    private static final List<Long> selectedBlocks = new ArrayList<>();
    private static WallKind kind = WallKind.TOWER;
    private static String selectedKey;
    private static boolean selectedNode;

    private WallClientState() {
    }

    public static void apply(WallStatePacket packet) {
        NODES.clear();
        NODES.addAll(packet.nodes());
        SEGMENTS.clear();
        SEGMENTS.addAll(packet.segments());
        if (selectedKey != null && boundsOf(selectedKey, selectedNode) == null) {
            clearSelection();
        }
    }

    /**
     * 服务端逐方块反查结果：key 为 null 表示点空，清选中；否则记下任务与全量方块
     */
    public static void applyPick(WallPickResultPacket packet) {
        selectedBlocks.clear();
        if (packet.key() == null) {
            selectedKey = null;
            selectedNode = false;
            return;
        }
        selectedKey = packet.key();
        selectedNode = packet.node();
        selectedBlocks.addAll(packet.blocks());
        if (boundsOf(selectedKey, selectedNode) == null) {
            // 快照里还没有这个任务（状态过期），拉一次新快照保证删除按钮可用
            PacketDistributor.sendToServer(new WallStateRequestPacket());
        }
    }

    public static void clearSelection() {
        selectedKey = null;
        selectedNode = false;
        selectedBlocks.clear();
    }

    public static void reset() {
        clearSelection();
        NODES.clear();
        SEGMENTS.clear();
    }

    public static WallKind kind() {
        return kind;
    }

    public static void select(WallKind value) {
        kind = value == null ? WallKind.TOWER : value;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        PacketDistributor.sendToServer(new WallStateRequestPacket());
        beginGhost();
    }

    public static void rearm() {
        beginGhost();
    }

    private static void beginGhost() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        BlockPos origin = BlockPos.containing(minecraft.player.getX(), minecraft.player.getY(),
                minecraft.player.getZ());
        BuildingPlacementManager.beginRtsFollow(kind.blueprint(), origin, fallbackModel(kind));
        if (!BuildingPlacementManager.isActive()) {
            hint("城墙投影加载失败，请检查游戏目录 kingdom simulation/structures/"
                    + kind.blueprint() + ".nbt");
        }
    }

    private static StructureModel fallbackModel(WallKind value) {
        List<StructureModel.BlockEntry> entries = new ArrayList<>();
        for (WallBlock block : WallDefaults.blocks(value)) {
            entries.add(new StructureModel.BlockEntry(new BlockPos(block.x(), block.y(), block.z()),
                    Block.stateById(block.stateId())));
        }
        return new StructureModel(new Vec3i(WallDefaults.SIZE_X, WallDefaults.SIZE_Y,
                WallDefaults.SIZE_Z), entries);
    }

    public static void onKeyPress(int key) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
        } else if (key == GLFW.GLFW_KEY_C) {
            BuildingPlacementManager.undoLastPlacement();
        } else if (key == GLFW.GLFW_KEY_R) {
            BuildingPlacementManager.rotateTarget();
        } else if (key == GLFW.GLFW_KEY_ESCAPE) {
            RtsModeManager.exitSubMode();
        }
    }

    public static void submit() {
        List<BuildingPlacementManager.Pending> pendings = BuildingPlacementManager.pendings();
        List<WallSubmitPacket.Entry> entries = new ArrayList<>();
        for (BuildingPlacementManager.Pending pending : pendings) {
            if (!pending.name().startsWith("wall_")) {
                continue;
            }
            WallKind pendingKind = pending.name().equals(WallKind.GATE.blueprint())
                    ? WallKind.GATE : WallKind.TOWER;
            BlockPos origin = pending.origin();
            entries.add(new WallSubmitPacket.Entry(pendingKind,
                    origin.getX(), origin.getY(), origin.getZ(), pending.rotation()));
        }
        if (entries.isEmpty()) {
            hint("还没有待提交的节点：右键先固定投影");
            return;
        }
        PacketDistributor.sendToServer(new WallSubmitPacket(entries));
        BuildingPlacementManager.discardPendingsWithName(name -> name.startsWith("wall_"));
        hint("已提交 " + entries.size() + " 个节点");
    }

    /**
     * RTS 点击选中：把点击的方块坐标发给服务端，由 wall_blocks 逐方块精确反查归属任务
     */
    public static void selectAtCursor(double mouseX, double mouseY) {
        RtsPicker.BlockHit hit = RtsPicker.pickBlockHit(mouseX, mouseY);
        if (hit == null) {
            clearSelection();
            return;
        }
        selectAt(hit.blockPos());
    }

    /** 直接按方块坐标发起选中（RTS 普通模式与城墙模式共用） */
    public static void selectAt(BlockPos pos) {
        PacketDistributor.sendToServer(new WallPickPacket(pos.getX(), pos.getY(), pos.getZ()));
    }

    private static final int WALL_TOP = 10;

    public static void deleteSelected() {
        if (selectedKey == null) {
            return;
        }
        PacketDistributor.sendToServer(new WallDeletePacket(selectedKey, selectedNode));
        clearSelection();
    }

    public static String selectedKey() {
        return selectedKey;
    }

    public static boolean hasSelection() {
        return selectedKey != null;
    }

    /** 选中任务的全量方块（逐方块高亮用），无选中为空列表 */
    public static List<Long> selectedBlocks() {
        return selectedBlocks;
    }

    public static AABB selectedBounds() {
        return boundsOf(selectedKey, selectedNode);
    }

    private static AABB boundsOf(String key, boolean node) {
        if (key == null) {
            return null;
        }
        if (node) {
            for (WallStatePacket.NodeView view : NODES) {
                if (view.key().equals(key)) {
                    return nodeBounds(view);
                }
            }
            return null;
        }
        for (WallStatePacket.SegmentView view : SEGMENTS) {
            if (view.key().equals(key)) {
                return segmentBounds(view);
            }
        }
        return null;
    }

    private static AABB nodeBounds(WallStatePacket.NodeView node) {
        return new AABB(node.x(), node.y(), node.z(),
                node.x() + 5.0D, node.y() + 12.0D, node.z() + 5.0D);
    }

    private static AABB segmentBounds(WallStatePacket.SegmentView segment) {
        double minX = Math.min(segment.ax(), segment.bx()) - 1.1D;
        double maxX = Math.max(segment.ax(), segment.bx()) + 2.1D;
        double minZ = Math.min(segment.az(), segment.bz()) - 1.1D;
        double maxZ = Math.max(segment.az(), segment.bz()) + 2.1D;
        double minY = Math.min(segment.ay(), segment.by());
        double maxY = Math.max(segment.ay(), segment.by()) + WALL_TOP;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void hint(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }
}
