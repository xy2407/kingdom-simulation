package com.xy2407.kingdom.client.structure;

import com.mojang.blaze3d.platform.InputConstants;
import com.xy2407.kingdom.ModMain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;

/**
 * HUD 覆盖层的文字输入代理：AUI 字符事件只挂 ScreenEvent，游戏内文档收不到，这里包装 GLFW char 回调喂给搜索框。
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class StructureSearchInput {

    private static GLFWCharCallback previousCallback;
    private static boolean installed;
    private static boolean active;

    private StructureSearchInput() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }
        long handle = minecraft.getWindow().getWindow();
        if (handle == 0L) {
            return;
        }
        previousCallback = GLFW.glfwSetCharCallback(handle, (window, codePoint) -> {
            if (active && StructureDebugKeybind.acceptSearchChar(codePoint)) {
                return;
            }
            if (previousCallback != null) {
                previousCallback.invoke(window, codePoint);
            }
        });
        installed = true;
    }

    public static void uninstall() {
        active = false;
        if (!installed) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getWindow() != null) {
            long handle = minecraft.getWindow().getWindow();
            if (handle != 0L) {
                GLFW.glfwSetCharCallback(handle, previousCallback);
            }
        }
        previousCallback = null;
        installed = false;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (!active || event.getAction() != InputConstants.PRESS) {
            return;
        }
        int key = event.getKey();
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            StructureDebugKeybind.backspaceSearch();
        } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER
                || key == GLFW.GLFW_KEY_ESCAPE) {
            StructureDebugKeybind.cancelSearch();
        }
    }
}
