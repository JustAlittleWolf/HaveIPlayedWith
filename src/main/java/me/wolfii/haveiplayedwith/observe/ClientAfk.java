package me.wolfii.haveiplayedwith.observe;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side idle detection. Movement keys, toggles, mouse buttons, cursor
 * motion, look changes, and the hotbar all count as activity. The first sample
 * after a world change only records a baseline, so joining does not look like input.
 */
final class ClientAfk {
    private final AfkTracker idle = new AfkTracker();
    private boolean mouseSampled;
    private double lastMouseX;
    private double lastMouseY;
    private boolean lookSampled;
    private float lastYaw;
    private float lastPitch;
    private boolean slotSampled;
    private int lastSlot;

    boolean tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return idle.afk();
        }
        return idle.tick(active(client, player));
    }

    void resetBaseline() {
        mouseSampled = false;
        lookSampled = false;
        slotSampled = false;
    }

    private boolean active(Minecraft client, LocalPlayer player) {
        return movement(player.input)
            || hardwareInput(client)
            || mouseMoved(client)
            || lookMoved(player)
            || slotChanged(player)
            || player.swinging;
    }

    private static boolean movement(ClientInput input) {
        if (input == null || input.keyPresses == null) {
            return false;
        }
        Input keys = input.keyPresses;
        return keys.forward() || keys.backward() || keys.left() || keys.right()
            || keys.jump() || keys.shift() || keys.sprint();
    }

    private static boolean hardwareInput(Minecraft client) {
        if (mouseButtons(client.mouseHandler) || hotbarKeys(client.options)) {
            return true;
        }
        Window window = client.getWindow();
        return window != null && anyKey(window.handle());
    }

    private static boolean mouseButtons(MouseHandler mouse) {
        return mouse != null && (mouse.isLeftPressed() || mouse.isMiddlePressed() || mouse.isRightPressed());
    }

    private static boolean hotbarKeys(Options options) {
        if (options == null || options.keyHotbarSlots == null) {
            return false;
        }
        for (KeyMapping slot : options.keyHotbarSlots) {
            if (slot != null && slot.isDown()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyKey(long window) {
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS) {
                return true;
            }
        }
        return false;
    }

    private boolean mouseMoved(Minecraft client) {
        double x = client.mouseHandler.xpos();
        double y = client.mouseHandler.ypos();
        if (!mouseSampled) {
            mouseSampled = true;
            lastMouseX = x;
            lastMouseY = y;
            return false;
        }
        boolean moved = Double.compare(x, lastMouseX) != 0 || Double.compare(y, lastMouseY) != 0;
        lastMouseX = x;
        lastMouseY = y;
        return moved;
    }

    private boolean lookMoved(LocalPlayer player) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (!lookSampled) {
            lookSampled = true;
            lastYaw = yaw;
            lastPitch = pitch;
            return false;
        }
        boolean moved = Float.compare(yaw, lastYaw) != 0 || Float.compare(pitch, lastPitch) != 0;
        lastYaw = yaw;
        lastPitch = pitch;
        return moved;
    }

    private boolean slotChanged(LocalPlayer player) {
        int slot = player.getInventory().getSelectedSlot();
        if (!slotSampled) {
            slotSampled = true;
            lastSlot = slot;
            return false;
        }
        boolean changed = slot != lastSlot;
        lastSlot = slot;
        return changed;
    }
}
