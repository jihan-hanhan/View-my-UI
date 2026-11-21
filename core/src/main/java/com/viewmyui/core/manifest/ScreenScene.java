package com.viewmyui.core.manifest;

import java.util.List;

/**
 * 一次界面采集的结果（打开一个 Screen 对应一个场景）。
 *
 * @param width    界面逻辑宽（GUI scale 之前）
 * @param height   界面逻辑高
 * @param guiScale 采集时的 GUI 缩放倍率；游戏是在「逻辑像素 × guiScale」的物理分辨率上光栅化的，
 *                 所以 1 个逻辑像素对应 {@code guiScale × guiScale} 个真实像素。
 *                 1.20.1 是整数，1.21.1 起可能是 2.5 这类小数；0 表示未知，按 1 处理。
 */
public record ScreenScene(
        String id,
        String title,
        int width,
        int height,
        double guiScale,
        List<BlitNode> nodes) {

    public ScreenScene {
        nodes = List.copyOf(nodes);
    }
}
