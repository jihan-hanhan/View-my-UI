package com.viewmyui.forge;

import net.minecraftforge.fml.common.Mod;

/**
 * 开发版采集器入口。刻意保持极薄——工具的主体（合成、编辑器）都在 {@code core} 与独立进程中，
 * 这样换 Minecraft 版本时只需要改这一个模块。
 */
@Mod(ViewMyUI.MOD_ID)
public final class ViewMyUI {

    public static final String MOD_ID = "viewmyui";

    public ViewMyUI() {
        // 本阶段只注册按键与事件，UI 逻辑全部在独立编辑器进程里
    }
}
