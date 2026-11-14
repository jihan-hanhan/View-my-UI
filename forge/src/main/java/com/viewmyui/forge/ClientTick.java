package com.viewmyui.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.viewmyui.forge.capture.BlitRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 只在客户端跑。F9 把当前这一帧写成 manifest，F10 拉起独立编辑器进程。
 *
 * <p>热键不能靠 {@link net.minecraft.client.KeyMapping#consumeClick()}：只要屏幕上开着 GUI，
 * 按键会被 {@code Screen} 吃掉，KeyMapping 的状态根本不会更新——而我们要抓的恰恰是 GUI。
 * 所以额外监听 Forge 的原始按键事件（对所有按键都会触发，与界面无关）。
 * 键位仍来自 KeyMapping，玩家在「选项 → 控制」里改绑即可生效。
 */
@Mod.EventBusSubscriber(modid = ViewMyUI.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientTick {

    /** GLFW_PRESS：忽略按住重复与抬起 */
    private static final int ACTION_PRESS = 1;

    private ClientTick() {
    }

    private static int autoTicks = -1;
    private static boolean captureRequested;
    private static boolean editorRequested;

    /**
     * 帧边界由渲染帧事件给出，而不是 {@code new GuiGraphics}——
     * 某些模组会在渲染中途再 new 一个 GuiGraphics，用构造器当帧信号会把之前画的全清掉。
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            BlitRecorder.INSTANCE.frameStart();
        }
    }

    /**
     * 原始按键：无论有没有打开 GUI 都会触发，这样在界面里也能按 F9 / F10。
     *
     * <p>只记一个标志、推迟到下一个 client tick 再执行——保证写出的是完整的一帧，
     * 也不会在 GLFW 回调里做写文件这种慢操作。
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != ACTION_PRESS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // 「控制 → 按键绑定」里正在改键时不要误触发
        if (minecraft.screen instanceof KeyBindsScreen) {
            return;
        }
        InputConstants.Key key = InputConstants.getKey(event.getKey(), event.getScanCode());
        if (ClientSetup.captureKey != null && ClientSetup.captureKey.getKey().equals(key)) {
            captureRequested = true;
        } else if (ClientSetup.editorKey != null && ClientSetup.editorKey.getKey().equals(key)) {
            editorRequested = true;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();

        // 开发期自动采集：进入任意界面后录 2 秒再落盘，用于验证整条链路
        if (Boolean.getBoolean("viewmyui.autocapture")) {
            if (autoTicks == -1 && minecraft.screen != null) {
                autoTicks = 0;
            } else if (autoTicks >= 0 && ++autoTicks > 40) {
                BlitRecorder.INSTANCE.flush();
                autoTicks = -2;
            }
        }

        if (captureRequested) {
            captureRequested = false;
            message(minecraft, BlitRecorder.INSTANCE.flush());
        }

        if (editorRequested) {
            editorRequested = false;
            if (EditorLauncher.isRunning()) {
                message(minecraft, "编辑器已在运行");
            } else {
                // 启动要 1~4 秒，先给反馈，避免玩家以为没按到而连点
                message(minecraft, "正在打开编辑器…（首次启动 1~4 秒）");
                if (!EditorLauncher.launch()) {
                    message(minecraft, "启动失败：没找到编辑器，详见 editor.log");
                }
            }
        }
    }

    /**
     * 用聊天栏而不是 player 消息：主菜单/无存档时 player 为 null，也要能看到反馈。
     */
    private static void message(Minecraft minecraft, String text) {
        minecraft.gui.getChat().addMessage(Component.literal("[ViewMyUI] " + text));
    }
}
