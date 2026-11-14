package com.viewmyui.editor;

import com.viewmyui.core.manifest.BlitNode;
import com.viewmyui.core.manifest.ScreenScene;
import com.viewmyui.core.pack.PackResolver;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 切图参考图导出：给每张被引用的精灵图叠加一张标注图，画出所有 {@code src} 矩形。
 *
 * <p>画师可以直接把它当画布参考线：哪个像素块属于哪个控件一眼可见，改图时不会串色。
 * 输出保持原图尺寸（1:1），不做任何缩放。
 */
final class ReferenceExport {

    private static final Color[] COLORS = {
            new Color(0x4F, 0xC3, 0xF7), new Color(0xFF, 0xB7, 0x4D),
            new Color(0xE5, 0x73, 0x73), new Color(0x81, 0xC7, 0x84),
            new Color(0xBA, 0x68, 0xC8), new Color(0xFF, 0xF1, 0x76),
            new Color(0x4D, 0xB6, 0xAC), new Color(0xFF, 0x8A, 0x65)
    };

    private ReferenceExport() {
    }

    /**
     * @return 实际写出的图片数量
     */
    static int export(Path directory, ScreenScene scene, PackResolver resolver) throws IOException {
        Files.createDirectories(directory);

        Map<String, List<BlitNode>> grouped = new LinkedHashMap<>();
        for (BlitNode node : scene.nodes()) {
            grouped.computeIfAbsent(node.texture(), key -> new ArrayList<>()).add(node);
        }

        int written = 0;
        int color = 0;
        for (Map.Entry<String, List<BlitNode>> entry : grouped.entrySet()) {
            Path file = resolver.resolve(entry.getKey());
            if (file == null) {
                continue;
            }
            BufferedImage source = ImageIO.read(file.toFile());
            if (source == null) {
                continue;
            }

            BufferedImage canvas = new BufferedImage(source.getWidth(), source.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.setComposite(AlphaComposite.Src);
                g.drawImage(source, 0, 0, null);
                g.setComposite(AlphaComposite.SrcOver);
                g.setStroke(new BasicStroke(1f));

                for (BlitNode node : entry.getValue()) {
                    Color base = COLORS[color++ % COLORS.length];
                    g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 40));
                    g.fillRect(node.srcX(), node.srcY(), node.srcW(), node.srcH());
                    g.setColor(base);
                    // 框画在矩形最外圈像素上，不越界
                    g.drawRect(node.srcX(), node.srcY(),
                            Math.max(1, node.srcW() - 1), Math.max(1, node.srcH() - 1));
                }
            } finally {
                g.dispose();
            }

            ImageIO.write(canvas, "png", directory.resolve(fileName(entry.getKey())).toFile());
            written++;
        }
        return written;
    }

    /**
     * {@code modid:textures/gui/foo.png} → {@code modid__textures_gui_foo.png}
     */
    private static String fileName(String texture) {
        int colon = texture.indexOf(':');
        String namespace = colon >= 0 ? texture.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? texture.substring(colon + 1) : texture;
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        return namespace + "__" + path.replace('/', '_') + ".png";
    }
}
