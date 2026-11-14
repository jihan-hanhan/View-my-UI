package com.viewmyui.editor;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.awt.image.BufferedImage;

/**
 * AWT 图像 → JavaFX 图像。
 *
 * <p>刻意不用 {@code javafx.embed.swing.SwingFXUtils}：那会把 {@code javafx.swing} 模块拖进来，
 * 而这里只需要手转一次像素数组。
 */
final class FxImages {

    private FxImages() {
    }

    static WritableImage toFx(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        WritableImage fx = new WritableImage(width, height);
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        fx.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return fx;
    }
}
