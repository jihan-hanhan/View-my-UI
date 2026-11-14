package com.viewmyui.core.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 按优先级在多个资源包根目录里查找纹理。
 *
 * <p>刻意绕开 Minecraft 的 {@code ResourceManager}：后者只扫 {@code <gameDir>/resourcepacks}，
 * 看不到实例之外的工作目录。这里直接用 {@code java.nio.file} 读任意绝对路径。
 */
public final class PackResolver {

    /**
     * 一次命中的来源。
     *
     * @param root 提供该文件的资源包根目录（用来告诉用户"这张图来自哪个包"）
     * @param file 实际被读到的文件
     */
    public record Hit(Path root, Path file) {
    }

    private final List<Path> roots;

    /**
     * @param roots 资源包根目录，<b>顺序即优先级</b>（前面的覆盖后面的）
     */
    public PackResolver(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    /**
     * @param location 资源位置，如 {@code examplemod:textures/gui/main}（可省略 {@code .png}）
     * @return 命中的文件；全部未命中返回 {@code null}
     */
    public Path resolve(String location) {
        Hit hit = resolveHit(location);
        return hit == null ? null : hit.file();
    }

    /**
     * 同 {@link #resolve(String)}，但额外返回命中的资源包根目录。
     */
    public Hit resolveHit(String location) {
        String namespace = "minecraft";
        String path = location;
        int colon = location.indexOf(':');
        if (colon >= 0) {
            namespace = location.substring(0, colon);
            path = location.substring(colon + 1);
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        String relative = "assets/" + namespace + "/" + path;

        for (Path root : roots) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new Hit(root, candidate);
            }
        }
        return null;
    }
}
