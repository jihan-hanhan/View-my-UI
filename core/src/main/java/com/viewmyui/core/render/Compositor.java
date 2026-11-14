package com.viewmyui.core.render;

import com.viewmyui.core.manifest.BlitNode;
import com.viewmyui.core.manifest.ScreenScene;
import com.viewmyui.core.pack.PackResolver;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 manifest 合成成一张图。纯 AWT，零 Minecraft 依赖——
 * 这正是"编辑器可以脱离游戏版本演进"的前提。
 *
 * <h2>「无」为什么按 guiScale 放大画布</h2>
 * 群峦（TFC）砧界面的"局部缩放"其实没有特殊算法：
 * {@code AnvilStepButton} 只是 {@code graphics.blit(..., 16, 16, iconX, iconY, 32, 32, 256, 256)}，
 * 把 32×32 的精灵图直接画到 16×16 的按钮上，采样方式是 GL 的最近邻。
 *
 * <p>它看起来依旧清晰，是因为 Minecraft 的 GUI 不是按逻辑像素光栅化的：投影矩阵把 1 个逻辑像素
 * 映射成 {@code guiScale} 个屏幕像素。guiScale = 2 时那块 16×16 的按钮实际占 32×32 个物理像素，
 * 正好和 32×32 的源区域 1:1，一个 texel 都不丢。
 *
 * <p>编辑器若只在逻辑尺寸上合成，就会真的把 32×32 压成 16×16、丢掉一半像素——怎么调采样方式都
 * 不可能和游戏一致。所以「无」档按 {@code guiScale} 放大画布，让缩放节点自己回到 1:1 或接近 1:1，
 * 剩下的非整数倍缩放再按 GL 的规则直接丢像素；另外两档故意保留逻辑尺寸，用来对照"被压缩后"的结果。
 */
public final class Compositor {

    /**
     * 缩放采样方式。
     *
     * <ul>
     *   <li>{@link #NONE}（默认）：不做额外的压缩——画布按 {@code guiScale} 的物理分辨率合成，
     *       群峦砧界面那种 32×32→16 逻辑像素的精灵图会自然回到 1:1，和游戏里一模一样。</li>
     *   <li>{@link #NEAREST}：按逻辑像素合成，用 GL 最近邻直接丢像素（等价于游戏在 GUI 1x 下看到的）。</li>
     *   <li>{@link #SMOOTH}：按逻辑像素合成，双线性插值，留给确实想要抗锯齿的模组做对照。</li>
     * </ul>
     */
    public enum ScaleMode {
        NONE,
        NEAREST,
        SMOOTH
    }

    private final PackResolver resolver;
    private final ScaleMode scaleMode;
    private final Map<String, BufferedImage> cache = new HashMap<>();

    public Compositor(PackResolver resolver) {
        this(resolver, ScaleMode.NONE);
    }

    public Compositor(PackResolver resolver, ScaleMode scaleMode) {
        this.resolver = resolver;
        this.scaleMode = scaleMode;
    }

    public BufferedImage render(ScreenScene scene) throws IOException {
        // 「无」才回到游戏的物理分辨率；其余两种是"逻辑像素"对照视图，画布不放大
        int scale = scaleMode == ScaleMode.NONE ? Math.max(1, scene.guiScale()) : 1;
        BufferedImage canvas = new BufferedImage(
                Math.max(1, scene.width() * scale),
                Math.max(1, scene.height() * scale),
                BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            g.setComposite(AlphaComposite.SrcOver);

            for (BlitNode node : scene.nodes()) {
                BufferedImage texture = texture(node.texture());
                if (texture != null) {
                    draw(g, texture, node, scale);
                }
            }
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /**
     * 单次 blit：裁出源矩形，再按目标尺寸缩放。
     *
     * <p>目标矩形先乘 {@code guiScale} 换到物理像素。放大后仍然 1:1 的节点直接整块贴上，
     * 不走任何缩放；其余按 {@link ScaleMode} 采样。
     */
    private void draw(Graphics2D g, BufferedImage texture, BlitNode node, int scale) {
        int textureWidth = texture.getWidth();
        int textureHeight = texture.getHeight();

        int srcX = Math.max(0, node.srcX());
        int srcY = Math.max(0, node.srcY());
        int srcW = Math.min(node.srcW() - (srcX - node.srcX()), textureWidth - srcX);
        int srcH = Math.min(node.srcH() - (srcY - node.srcY()), textureHeight - srcY);
        int dstW = node.dstW() * scale;
        int dstH = node.dstH() * scale;
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return;
        }

        int dstX = node.dstX() * scale;
        int dstY = node.dstY() * scale;
        BufferedImage source = texture.getSubimage(srcX, srcY, srcW, srcH);
        if (srcW == dstW && srcH == dstH) {
            g.drawImage(source, dstX, dstY, null);
            return;
        }

        BufferedImage scaled = scaleMode == ScaleMode.SMOOTH
                ? smooth(source, dstW, dstH)
                // 「无」也不代表魔法：物理上仍非 1:1 的节点，游戏怎么丢像素就怎么丢
                : nearest(source, dstW, dstH);
        g.drawImage(scaled, dstX, dstY, null);
    }

    /**
     * GL 的最近邻：目标像素中心反算到纹理坐标，再取所在 texel。
     *
     * <p>{@code sx = floor((dx + 0.5) * srcW / dstW)}——注意这个 +0.5。Java2D 的
     * {@code AffineTransformOp(TYPE_NEAREST_NEIGHBOR)} 在 32→10 这类非整数倍下会在边界处
     * 差一个 texel（例如第 3 个目标像素取 7 而 GL 取 8），所以这里手算，避免"看起来差不多"。
     */
    private static BufferedImage nearest(BufferedImage source, int dstW, int dstH) {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        int[] pixels = source.getRGB(0, 0, srcW, srcH, null, 0, srcW);

        int[] columns = new int[dstW];
        for (int x = 0; x < dstW; x++) {
            columns[x] = Math.min(srcW - 1, (int) ((x + 0.5) * srcW / dstW));
        }
        int[] rows = new int[dstH];
        for (int y = 0; y < dstH; y++) {
            rows[y] = Math.min(srcH - 1, (int) ((y + 0.5) * srcH / dstH));
        }

        int[] out = new int[dstW * dstH];
        for (int y = 0; y < dstH; y++) {
            int sourceRow = rows[y] * srcW;
            int outRow = y * dstW;
            for (int x = 0; x < dstW; x++) {
                out[outRow + x] = pixels[sourceRow + columns[x]];
            }
        }

        BufferedImage image = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, dstW, dstH, out, 0, dstW);
        return image;
    }

    private static BufferedImage smooth(BufferedImage source, int dstW, int dstH) {
        AffineTransformOp op = new AffineTransformOp(
                AffineTransform.getScaleInstance(
                        (double) dstW / source.getWidth(), (double) dstH / source.getHeight()),
                AffineTransformOp.TYPE_BILINEAR);
        BufferedImage image = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB);
        op.filter(source, image);
        return image;
    }

    private BufferedImage texture(String location) throws IOException {
        if (cache.containsKey(location)) {
            return cache.get(location);
        }
        Path file = resolver.resolve(location);
        BufferedImage image = null;
        if (file != null) {
            try (InputStream in = Files.newInputStream(file)) {
                image = ImageIO.read(in);
            }
        }
        cache.put(location, image);
        return image;
    }
}
