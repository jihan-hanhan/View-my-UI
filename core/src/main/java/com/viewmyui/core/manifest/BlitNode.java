package com.viewmyui.core.manifest;

/**
 * 一次纹理绘制调用。
 *
 * <p>源矩形用<b>纹理像素</b>，目标矩形用<b>界面像素</b>，两者刻意分离——
 * 这是 Minecraft {@code GuiGraphics.blit} 的真实语义，也是还原缩放关系的关键。
 *
 * <p>1.21.1 的精灵图（{@code blitSprite}）在采集时已按约定路径解析成普通纹理，
 * 源矩形就是整张图；缩放语义放在 {@link #scaling()}。
 * 1.20.1 的 manifest 没有这个字段，读出来是 {@link GuiScaling#STRETCH}，
 * 因此同一份编辑器代码可以同时吃两代采集结果。
 */
public record BlitNode(
        String texture,
        int srcX, int srcY, int srcW, int srcH,
        int dstX, int dstY, int dstW, int dstH,
        int z,
        GuiScaling scaling) {

    public BlitNode {
        if (scaling == null) {
            scaling = GuiScaling.STRETCH;
        }
    }

    /**
     * 1.20.1 / schema v1 的形态：无缩放元数据。
     */
    public BlitNode(String texture,
                    int srcX, int srcY, int srcW, int srcH,
                    int dstX, int dstY, int dstW, int dstH,
                    int z) {
        this(texture, srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH, z,
                GuiScaling.STRETCH);
    }
}
