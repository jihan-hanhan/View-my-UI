package com.viewmyui.core.manifest;

/**
 * 精灵图的缩放语义，对应 Minecraft GUI 纹理 {@code .png.mcmeta} 里的 {@code gui.scaling}。
 *
 * <p>1.20.1 的普通 {@code blit} 没有这个概念，一律按 {@link Type#STRETCH} 处理。
 * 1.21.1 的 {@code blitSprite} 会带 stretch / tile / nine_slice 三种，编辑器必须照着实现在哪块拉伸。
 *
 * @param left  nine_slice 左边框（源图像素）
 * @param top   nine_slice 上边框
 * @param right nine_slice 右边框
 * @param bottom nine_slice 下边框
 * @param stretchInner 九宫格中心是否拉伸；{@code false} 表示平铺
 */
public record GuiScaling(
        Type type,
        int left,
        int top,
        int right,
        int bottom,
        boolean stretchInner) {

    public enum Type {
        STRETCH,
        TILE,
        NINE_SLICE
    }

    public static final GuiScaling STRETCH = new GuiScaling(Type.STRETCH, 0, 0, 0, 0, true);

    public GuiScaling {
        if (type == null) {
            type = Type.STRETCH;
        }
    }
}
