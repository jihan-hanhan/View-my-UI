package com.viewmyui.forge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 拉起独立编辑器进程。
 *
 * <p>优先直接用<b>当前游戏进程的 JVM</b> 跑编辑器，不依赖 PATH 里的 java、
 * 也不依赖 .bat 找 JAVA_HOME——这样从 PCL / 官方启动器 / 各种整合包启动都能起来。
 *
 * <p>编辑器位置按以下顺序查找：
 * <ol>
 *   <li>系统属性 {@code -Dviewmyui.editor=...}（可指 .exe、.bat 或目录）</li>
 *   <li>环境变量 {@code VIEWMYUI_EDITOR}</li>
 *   <li><b>模组 jar 内嵌的 {@code META-INF/viewmyui/editor/}</b>——首次使用时解压到
 *       {@code <gameDir>/viewmyui/editor/<版本>/}，之后的版本各自独立，不会覆盖正在运行的 jar</li>
 *   <li>{@code <gameDir>/viewmyui/editor} / {@code viewmyui-editor}（开发期手动放的目录）</li>
 * </ol>
 * 内嵌版排在手动目录之前，避免玩家升了模组却还在跑旧编辑器；调试时用前两项显式覆盖。
 * 编辑器的 stdout/stderr 写到 {@code <gameDir>/viewmyui/editor.log}，起不来时先看它。
 */
public final class EditorLauncher {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 内嵌资源的根路径，和 {@code forge:jar} 里塞进去的位置一致 */
    private static final String BUNDLE_PREFIX = "META-INF/viewmyui/editor/";

    private static Process process;

    private EditorLauncher() {
    }

    /**
     * @return 是否成功拉起新进程；已在运行、找不到编辑器或启动失败都返回 {@code false}
     */
    public static synchronized boolean launch() {
        if (isRunning()) {
            LOGGER.info("[viewmyui] 编辑器已在运行，忽略重复启动");
            return false;
        }

        List<String> searched = new ArrayList<>();
        Path located = locateOverride(searched);
        if (located == null) {
            located = extractBundled(searched);
        }
        if (located == null) {
            located = locateGameDir(searched);
        }
        if (located == null) {
            LOGGER.warn("[viewmyui] 找不到编辑器，已尝试：{}", searched);
            return false;
        }

        Path gameDir = FMLPaths.GAMEDIR.get();
        Path logDir = gameDir.resolve("viewmyui");
        Path logFile = logDir.resolve("editor.log");

        List<String> command = new ArrayList<>();
        Path workingDirectory;
        try {
            Files.createDirectories(logDir);
            if (Files.isDirectory(located)) {
                workingDirectory = located;
                Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                        isWindows() ? "java.exe" : "java");
                if (!Files.isRegularFile(javaExecutable)) {
                    javaExecutable = Path.of(isWindows() ? "java.exe" : "java");
                }
                command.add(javaExecutable.toString());
                command.add("-cp");
                command.add(classpath(located.resolve("lib")));
                command.add("com.viewmyui.editor.EditorLauncher");
            } else {
                workingDirectory = located.getParent();
                String name = located.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".bat") || name.endsWith(".cmd")) {
                    command.add("cmd.exe");
                    command.add("/c");
                }
                command.add(located.toString());
            }

            // 有采集结果就顺手打开，省一次手动选文件
            Path manifest = logDir.resolve("manifest.json");
            if (Files.isRegularFile(manifest)) {
                command.add(manifest.toString());
            }

            ProcessBuilder builder = new ProcessBuilder(command)
                    // 不继承游戏进程的 cwd，否则相对路径会指向错误目录
                    .directory(workingDirectory.toFile())
                    // 输出必须落到文件：既不会因管道写满卡死，起不来时也有据可查
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            process = builder.start();
            LOGGER.info("[viewmyui] 编辑器已启动 pid={} path={} 日志={}",
                    process.pid(), located, logFile);
            return true;
        } catch (IOException e) {
            LOGGER.error("[viewmyui] 启动编辑器失败，详见 {}", logFile, e);
            return false;
        }
    }

    /**
     * 编辑器进程可能已被用户关掉，所以每次都看进程状态而不是只看引用。
     */
    public static boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * 显式覆盖：系统属性 / 环境变量，方便开发时指向本地构建。
     */
    private static Path locateOverride(List<String> searched) {
        String property = System.getProperty("viewmyui.editor", "");
        if (!property.isBlank()) {
            Path path = Path.of(property).toAbsolutePath().normalize();
            searched.add(path + " (-Dviewmyui.editor)");
            if (isUsable(path)) {
                return path;
            }
        }

        String environment = System.getenv("VIEWMYUI_EDITOR");
        if (environment != null && !environment.isBlank()) {
            Path path = Path.of(environment).toAbsolutePath().normalize();
            searched.add(path + " (VIEWMYUI_EDITOR)");
            if (isUsable(path)) {
                return path;
            }
        }
        return null;
    }

    /**
     * 开发期手动放在游戏目录里的编辑器（模组 jar 没有内嵌时走这里）。
     */
    private static Path locateGameDir(List<String> searched) {
        Path base = FMLPaths.GAMEDIR.get().resolve("viewmyui");
        for (String candidate : new String[]{"editor", "viewmyui-editor"}) {
            Path path = base.resolve(candidate);
            searched.add(path.toString());
            if (isUsable(path)) {
                return path;
            }
        }
        return null;
    }

    /**
     * 把模组 jar 里内嵌的编辑器解压到 {@code <gameDir>/viewmyui/editor/<版本>/}。
     *
     * <p>只通过 classpath 资源读取，不关心模组 jar 在磁盘上的真实路径（Forge 可能把模组
     * 装进 union filesystem）。先解压到临时目录再整体改名，游戏崩溃或两个实例同时启动都不会
     * 留下半个 {@code lib/}；版本号带内容哈希，升级模组后自然换到新目录。
     */
    private static Path extractBundled(List<String> searched) {
        ClassLoader loader = EditorLauncher.class.getClassLoader();
        try (InputStream indexStream = loader.getResourceAsStream(BUNDLE_PREFIX + "index.txt")) {
            if (indexStream == null) {
                searched.add("内嵌编辑器（jar 内没有 " + BUNDLE_PREFIX + "）");
                return null;
            }
            List<String> files = new String(indexStream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
            if (files.isEmpty()) {
                return null;
            }

            String version = readText(loader, BUNDLE_PREFIX + "version.txt");
            String dirName = version.replaceAll("[^0-9A-Za-z._-]", "_");
            Path root = FMLPaths.GAMEDIR.get().resolve("viewmyui").resolve("editor");
            Path target = root.resolve(dirName);
            searched.add(target + " (内嵌)");
            if (Files.isRegularFile(target.resolve(".complete"))) {
                return target;
            }

            Path staging = root.resolve(dirName + ".tmp" + ProcessHandle.current().pid());
            deleteRecursively(staging);
            Files.createDirectories(staging);
            for (String relative : files) {
                Path out = staging.resolve(relative).normalize();
                if (!out.startsWith(staging)) {
                    continue;
                }
                try (InputStream in = loader.getResourceAsStream(BUNDLE_PREFIX + relative)) {
                    if (in == null) {
                        LOGGER.warn("[viewmyui] 内嵌编辑器缺少 {}", relative);
                        continue;
                    }
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.writeString(staging.resolve(".complete"), version, StandardCharsets.UTF_8);
            if (Files.exists(target) && !Files.isRegularFile(target.resolve(".complete"))) {
                // 上次解压到一半就崩了，清掉重来
                deleteRecursively(target);
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                if (!Files.isRegularFile(target.resolve(".complete"))) {
                    throw e;
                }
                // 另一个游戏实例已经解压好了，直接用它的
                deleteRecursively(staging);
            }
            LOGGER.info("[viewmyui] 已解压内嵌编辑器 {} -> {}", version, target);
            return target;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[viewmyui] 解压内嵌编辑器失败", e);
            return null;
        }
    }

    private static String readText(ClassLoader loader, String name) {
        try (InputStream in = loader.getResourceAsStream(name)) {
            return in == null ? "unknown" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * 全平台 jar 里每个 JavaFX 模块都有五份 native，只把当前系统的放进 classpath，
     * 避免重复类和加载错 native；同时也过滤掉 installDist 可能留下的 0 字节占位 jar。
     */
    private static String classpath(Path lib) throws IOException {
        String platform = platformSuffix();
        List<String> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(lib)) {
            List<Path> jars = stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path jar : jars) {
                String name = jar.getFileName().toString();
                if (name.startsWith("javafx-") && !name.contains("-" + platform + ".jar")) {
                    continue;
                }
                if (Files.size(jar) < 1024) {
                    continue;
                }
                entries.add(jar.toString());
            }
        }
        // 目录里没有可用的 jar 时退回通配符，至少让日志里能看见真实的启动失败原因
        return entries.isEmpty() ? lib + File.separator + "*" : String.join(File.pathSeparator, entries);
    }

    private static String platformSuffix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return "win";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arm ? "mac-aarch64" : "mac";
        }
        // JavaFX 不发布 linux-aarch64；Linux ARM 上会因缺 native 起不来
        return "linux";
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 目录要求有 {@code lib/}（java -cp 起得来）；文件要求是可执行脚本或 .exe。
     */
    private static boolean isUsable(Path path) {
        if (Files.isDirectory(path)) {
            return Files.isDirectory(path.resolve("lib"));
        }
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") || name.endsWith(".bat") || name.endsWith(".cmd");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
