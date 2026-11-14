package com.viewmyui.editor;

import com.viewmyui.core.manifest.BlitNode;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 源纹理视图：把整张精灵图按整数倍放大显示，并标出当前节点用的那一块。
 *
 * <p>这是给画师看的部分——"我该改这张图的哪个像素块"直接画出来，
 * 相邻的蓝色框是同一张图上其它被引用的精灵，方便顺手检查有没有改到别人的区域。
 */
final class TextureSheet extends Region {

    private final Canvas canvas = new Canvas(1, 1);

    private WritableImage image;
    private BlitNode active;
    private List<BlitNode> references = List.of();
    private String message = "悬停画布上的元素，这里会显示它来自哪张精灵图";

    TextureSheet() {
        getChildren().add(canvas);
        setMinSize(0, 0);
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        redraw();
    }

    void show(BufferedImage source, BlitNode active, List<BlitNode> references) {
        this.image = source == null ? null : FxImages.toFx(source);
        this.active = active;
        this.references = references;
        this.message = "找不到该纹理文件，检查资源包是否包含它";
        redraw();
    }

    void clear(String message) {
        this.image = null;
        this.active = null;
        this.references = List.of();
        this.message = message;
        redraw();
    }

    private void redraw() {
        double width = Math.max(1, canvas.getWidth());
        double height = Math.max(1, canvas.getHeight());
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);
        g.setFill(Color.rgb(24, 24, 27));
        g.fillRect(0, 0, width, height);

        if (image == null) {
            g.setFill(Color.rgb(150, 150, 156));
            g.setFont(Font.font(12));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(message, width / 2, height / 2, width - 24);
            g.setTextAlign(TextAlignment.LEFT);
            return;
        }

        int textureWidth = (int) image.getWidth();
        int textureHeight = (int) image.getHeight();
        // 放得下就整数倍放大（像素图必须整数倍），放不下就按比例缩小，
        // 保证整张精灵图始终完整可见——画师要的是"整张图在哪块"。
        double fit = Math.min((width - 16) / textureWidth, (height - 16) / textureHeight);
        double scale = fit >= 1 ? Math.floor(fit) : Math.max(0.05, fit);
        double drawWidth = textureWidth * scale;
        double drawHeight = textureHeight * scale;
        double originX = Math.floor((width - drawWidth) / 2);
        double originY = Math.floor((height - drawHeight) / 2);

        drawChecker(g, originX, originY, drawWidth, drawHeight);
        g.setImageSmoothing(false);
        g.drawImage(image, originX, originY, drawWidth, drawHeight);

        g.setLineWidth(1);
        g.setStroke(Color.color(0.25, 0.65, 1.0, 0.85));
        for (BlitNode node : references) {
            if (node == active) {
                continue;
            }
            g.strokeRect(originX + node.srcX() * scale + 0.5, originY + node.srcY() * scale + 0.5,
                    Math.max(1, node.srcW() * scale) - 1, Math.max(1, node.srcH() * scale) - 1);
        }

        if (active != null) {
            g.setLineWidth(2);
            g.setStroke(Color.rgb(255, 64, 64));
            g.strokeRect(originX + active.srcX() * scale, originY + active.srcY() * scale,
                    Math.max(2, active.srcW() * scale), Math.max(2, active.srcH() * scale));
        }
    }

    private static void drawChecker(GraphicsContext g, double x, double y,
                                    double width, double height) {
        double cell = 8;
        g.save();
        g.beginPath();
        g.rect(x, y, width, height);
        g.closePath();
        g.clip();
        for (double row = 0; row * cell < height; row++) {
            for (double col = 0; col * cell < width; col++) {
                boolean dark = ((int) row + (int) col) % 2 == 0;
                g.setFill(dark ? Color.rgb(40, 40, 44) : Color.rgb(52, 52, 58));
                g.fillRect(x + col * cell, y + row * cell, cell, cell);
            }
        }
        g.restore();
    }
}
