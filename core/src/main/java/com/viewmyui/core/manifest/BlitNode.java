package com.viewmyui.core.manifest;

/**
 * 一次 blit 绘制调用。
 *
 * <p>源矩形用<b>纹理像素</b>，目标矩形用<b>界面像素</b>，两者刻意分离——
 * 这是 Minecraft {@code GuiGraphics.blit} 的真实语义，也是还原缩放关系的关键。
 */
public record BlitNode(
        String texture,
        int srcX, int srcY, int srcW, int srcH,
        int dstX, int dstY, int dstW, int dstH,
        int z) {
}
