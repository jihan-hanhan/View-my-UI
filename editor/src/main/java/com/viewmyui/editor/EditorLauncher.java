package com.viewmyui.editor;

import com.viewmyui.core.manifest.ManifestReader;
import com.viewmyui.core.manifest.ScreenScene;
import com.viewmyui.core.manifest.UiManifest;
import com.viewmyui.core.pack.PackResolver;
import com.viewmyui.core.render.Compositor;
import com.viewmyui.core.render.ImageDiff;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 入口。三种模式：
 * <ul>
 *   <li>不带参数：打开 JavaFX 编辑器</li>
 *   <li>{@code --export <manifest> --packs <dir;dir> --out <png>}：无界面合成出一张 PNG</li>
 *   <li>{@code --compare <manifest> --packs-a <dirs> --packs-b <dirs> --out <png>}：
 *       两套资源包各合成一张，拼成左右并排 + 差异高亮的 PNG</li>
 * </ul>
 * 后两者既是给没有 GUI 的场景用的，也是开发期验证合成结果最省事的手段。
 */
public final class EditorLauncher {

    private EditorLauncher() {
    }

    public static void main(String[] args) throws Exception {
        List<String> options = Arrays.asList(args);
        if (options.contains("--compare")) {
            compare(args);
            return;
        }
        if (options.contains("--references")) {
            references(args);
            return;
        }
        if (options.contains("--export")) {
            export(args);
            return;
        }
        EditorApp.main(args);
    }

    private static void export(String[] args) throws Exception {
        Path manifestPath = null;
        Path out = null;
        List<Path> packs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--export" -> manifestPath = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--packs" -> addPacks(packs, args[++i]);
                default -> {
                }
            }
        }
        if (manifestPath == null || out == null) {
            System.err.println("用法: --export <manifest.json> --packs <dir;dir> --out <out.png>");
            System.exit(2);
        }

        UiManifest manifest = ManifestReader.read(manifestPath);
        ScreenScene scene = Scenes.withComputedBounds(manifest.screens().get(0));
        BufferedImage image = new Compositor(new PackResolver(packs)).render(scene);

        ImageIO.write(image, "png", out.toFile());
        System.out.printf("已合成 %dx%d -> %s%n", image.getWidth(), image.getHeight(), out.toAbsolutePath());
    }

    /**
     * 无界面对比：左右并排导出，同时把差异像素数打到 stdout，便于脚本/CI 判断。
     */
    private static void compare(String[] args) throws Exception {
        Path manifestPath = null;
        Path out = null;
        List<Path> leftPacks = new ArrayList<>();
        List<Path> rightPacks = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--compare" -> manifestPath = Path.of(args[++i]);
                case "--packs-a" -> addPacks(leftPacks, args[++i]);
                case "--packs-b" -> addPacks(rightPacks, args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                }
            }
        }
        if (manifestPath == null || out == null) {
            System.err.println("用法: --compare <manifest.json> --packs-a <dir;dir> "
                    + "--packs-b <dir;dir> --out <out.png>");
            System.exit(2);
        }

        UiManifest manifest = ManifestReader.read(manifestPath);
        ScreenScene scene = Scenes.withComputedBounds(manifest.screens().get(0));
        BufferedImage left = new Compositor(new PackResolver(leftPacks)).render(scene);
        BufferedImage right = new Compositor(new PackResolver(rightPacks)).render(scene);

        ImageDiff.Result diff = ImageDiff.compare(left, right);
        ImageIO.write(ImageDiff.sideBySide(left, right, diff.overlay(), 8), "png", out.toFile());
        System.out.printf("差异 %,d / %,d 像素 (%.3f%%) -> %s%n",
                diff.differing(), diff.compared(), diff.ratio() * 100, out.toAbsolutePath());
    }

    /**
     * 无界面导出切图参考图：每张精灵图一张 PNG，标出所有 {@code src} 矩形。
     */
    private static void references(String[] args) throws Exception {
        Path manifestPath = null;
        Path out = null;
        List<Path> packs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--references" -> manifestPath = Path.of(args[++i]);
                case "--packs" -> addPacks(packs, args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                }
            }
        }
        if (manifestPath == null || out == null) {
            System.err.println("用法: --references <manifest.json> --packs <dir;dir> --out <dir>");
            System.exit(2);
        }

        UiManifest manifest = ManifestReader.read(manifestPath);
        ScreenScene scene = Scenes.withComputedBounds(manifest.screens().get(0));
        int count = ReferenceExport.export(out, scene, new PackResolver(packs));
        System.out.printf("已导出 %d 张参考图 -> %s%n", count, out.toAbsolutePath());
    }

    private static void addPacks(List<Path> target, String joined) {        for (String dir : joined.split(";")) {
            if (!dir.isBlank()) {
                target.add(Path.of(dir));
            }
        }
    }
}
