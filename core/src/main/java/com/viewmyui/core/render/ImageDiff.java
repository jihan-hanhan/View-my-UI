package com.viewmyui.core.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * 两张合成图的差异比较与并排拼接。
 *
 * <p>用途：同一个 manifest 分别用「原版资源包」和「补丁包」合成，一眼看出补丁改了哪些像素。
 * 差异按 ARGB 全量比较（含 alpha），因此"把透明像素画脏"这种问题也能标出来。
 */
public final class ImageDiff {

    /** 差异像素的高亮色（洋红，和画布上的红/黄/橙选中框区分得开） */
    private static final int HIGHLIGHT = 0xFFFF00FF;
    private static final int BACKGROUND = 0xFF202024;

    /**
     * @param overlay   与两张图同尺寸的差异遮罩：差异像素为高亮色，其余透明
     * @param differing 差异像素数
     * @param compared  参与比较的像素总数（取两图尺寸的较大值）
     */
    public record Result(BufferedImage overlay, long differing, long compared) {
        public double ratio() {
            return compared == 0 ? 0 : (double) differing / compared;
        }
    }

    private ImageDiff() {
    }

    public static Result compare(BufferedImage left, BufferedImage right) {
        int width = Math.max(left.getWidth(), right.getWidth());
        int height = Math.max(left.getHeight(), right.getHeight());
        BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        long differing = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (argb(left, x, y) != argb(right, x, y)) {
                    overlay.setRGB(x, y, HIGHLIGHT);
                    differing++;
                }
            }
        }
        return new Result(overlay, differing, (long) width * height);
    }

    /**
     * 左右并排拼成一张图，中间留 {@code gap} 像素的深色分隔。
     * 两图顶部对齐，高度取较大值。
     *
     * @param overlay 差异遮罩，可为 {@code null}；给了就同时叠在左右两张图上
     */
    public static BufferedImage sideBySide(BufferedImage left, BufferedImage right,
                                           BufferedImage overlay, int gap) {
        int width = left.getWidth() + gap + right.getWidth();
        int height = Math.max(left.getHeight(), right.getHeight());
        BufferedImage out = new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = out.createGraphics();
        try {
            g.setColor(new Color(BACKGROUND, true));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());

            g.drawImage(left, 0, 0, null);
            if (overlay != null) {
                g.drawImage(overlay, 0, 0, null);
            }

            int rightX = left.getWidth() + gap;
            g.drawImage(right, rightX, 0, null);
            if (overlay != null) {
                g.drawImage(overlay, rightX, 0, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 越界按完全透明处理，这样尺寸不一致时多出来的区域也会被算作差异。 */
    private static int argb(BufferedImage image, int x, int y) {
        if (x >= image.getWidth() || y >= image.getHeight()) {
            return 0;
        }
        return image.getRGB(x, y);
    }
}
