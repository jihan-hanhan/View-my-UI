package com.viewmyui.forge.capture;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 采集：把渲染线程上的 blit 调用攒起来，按 F9 时落盘成 manifest.json。
 *
 * <p>采用「始终记录、随时落盘」——每帧开始时清空，按 F9 写出的永远是<b>你按下 F9 时屏幕上的那一帧</b>。
 * 不需要"先开始采集再打开界面"，也就不存在忘记结束、录到别的界面的问题。
 *
 * <p>输出路径固定为 {@code <gameDir>/viewmyui/manifest.json}，用绝对路径避免 cwd 不一致。
 */
public final class BlitRecorder {

    public static final BlitRecorder INSTANCE = new BlitRecorder();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 防止某些界面无限刷 blit 把内存吃光 */
    private static final int MAX_ENTRIES = 8192;

    /** 纹理像素尺寸缓存：uuid -> [w, h]，取不到记 [-1, -1] */
    private final Map<ResourceLocation, int[]> sizes = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    private boolean firstBlitLogged;

    private BlitRecorder() {
    }

    /**
     * 由 Forge 的 {@code RenderFrameEvent.Pre} 调用——每帧一次，且不受嵌套
     * {@code new GuiGraphics} 影响（这正是之前改用构造器注入后丢图的原因）。
     */
    public void frameStart() {
        entries.clear();
    }

    /**
     * 由 Mixin 在 {@code GuiGraphics.innerBlit} 的 HEAD 调用。
     *
     * @param u  归一化左边界 0~1
     * @param v  归一化上边界 0~1
     * @param uw 归一化宽度
     * @param vh 归一化高度
     */
    public void record(ResourceLocation texture, int dstX, int dstY, int dstW, int dstH,
                       float u, float v, float uw, float vh) {
        if (entries.size() >= MAX_ENTRIES) {
            return;
        }
        if (!firstBlitLogged) {
            firstBlitLogged = true;
            LOGGER.info("[viewmyui] 捕获到首个 blit: {} dst=({},{},{},{})", texture, dstX, dstY, dstW, dstH);
        }
        if (dstW <= 0 || dstH <= 0) {
            return;
        }
        int[] size = sizeOf(texture);
        if (size[0] <= 0 || size[1] <= 0) {
            return;
        }
        // 归一化 UV 反算回纹理像素，manifest 里统一用像素坐标
        int srcX = Math.round(u * size[0]);
        int srcY = Math.round(v * size[1]);
        int srcW = Math.round(uw * size[0]);
        int srcH = Math.round(vh * size[1]);
        entries.add(new Entry(texture.toString(), srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH));
    }

    private int[] sizeOf(ResourceLocation id) {
        return sizes.computeIfAbsent(id, key -> {
            try {
                Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(key);
                if (resource.isPresent()) {
                    // 只读 PNG 头里的 IHDR 就能拿到宽高，不必把整张图解码进内存
                    try (InputStream in = resource.get().open()) {
                        int[] size = readPngSize(in);
                        if (size != null) {
                            return size;
                        }
                    }
                    // 极少数不是标准 PNG 的资源，退回完整解码
                    try (InputStream in = resource.get().open()) {
                        BufferedImage image = ImageIO.read(in);
                        if (image != null) {
                            return new int[]{image.getWidth(), image.getHeight()};
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("[viewmyui] 读取纹理尺寸失败: {}", key, e);
            }
            return new int[]{-1, -1};
        });
    }

    /**
     * PNG 前 24 字节含签名与 IHDR（宽高为大端 int32）。
     *
     * @return {@code null} 表示不是可识别的 PNG
     */
    private static int[] readPngSize(InputStream in) throws IOException {
        byte[] header = in.readNBytes(24);
        if (header.length < 24
                || (header[0] & 0xFF) != 0x89 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G'
                || header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
            return null;
        }
        int width = readInt(header, 16);
        int height = readInt(header, 20);
        return width > 0 && height > 0 ? new int[]{width, height} : null;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    /**
     * 把当前这一帧的 blit 写成 manifest。
     *
     * @return 给玩家看的一句话摘要（含捕获到的纹理名，方便确认目标贴图有没有进来）
     */
    public String flush() {
        if (entries.isEmpty()) {
            LOGGER.info("[viewmyui] 这一帧没有记录到任何 blit");
            return "没捕获到任何绘制调用，确认界面已经渲染出来后再按 F9";
        }
        LinkedHashSet<String> textures = new LinkedHashSet<>();
        for (Entry entry : entries) {
            textures.add(entry.texture());
        }
        Path dir = FMLPaths.GAMEDIR.get().resolve("viewmyui");
        try {
            Files.createDirectories(dir);
            Path out = dir.resolve("manifest.json");
            Files.writeString(out, toJson(), StandardCharsets.UTF_8);
            LOGGER.info("[viewmyui] 已写出 {} 条节点 / {} 张纹理 -> {}",
                    entries.size(), textures.size(), out.toAbsolutePath());
            LOGGER.info("[viewmyui] 纹理列表: {}", textures);
        } catch (IOException e) {
            LOGGER.error("[viewmyui] 写出 manifest 失败", e);
            return "写出 manifest 失败：" + e.getMessage();
        }

        // 把这一帧用到的纹理解析结果快照下来，编辑器无需再导入资源包
        snapshotTextures(dir.resolve("default"), textures);

        String list = textures.stream().limit(4).collect(Collectors.joining(", "));
        if (textures.size() > 4) {
            list = list + " 等 " + textures.size() + " 张";
        }
        return "已写入 " + entries.size() + " 个节点：" + list;
    }

    /**
     * 手写 JSON，避免为一个开发工具引入序列化依赖。
     */
    private String toJson() {
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int guiScale = (int) minecraft.getWindow().getGuiScale();

        StringBuilder sb = new StringBuilder(entries.size() * 160 + 256);
        sb.append("{\n  \"schemaVersion\": 1,\n");
        sb.append("  \"meta\": {\"minecraft\": \"1.20.1\", \"loader\": \"forge\", \"guiScale\": ")
                .append(guiScale).append(", \"capturedFrom\": \"runtime\"},\n");
        sb.append("  \"screens\": [{\n");
        sb.append("    \"id\": \"capture\",\n");
        sb.append("    \"title\": \"capture\",\n");
        sb.append("    \"width\": ").append(width).append(",\n");
        sb.append("    \"height\": ").append(height).append(",\n");
        sb.append("    \"nodes\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            sb.append("      {\"type\":\"blit\",\"texture\":\"").append(e.texture())
                    .append("\",\"src\":[").append(e.srcX()).append(',').append(e.srcY()).append(',')
                    .append(e.srcW()).append(',').append(e.srcH())
                    .append("],\"dst\":[").append(e.dstX()).append(',').append(e.dstY()).append(',')
                    .append(e.dstW()).append(',').append(e.dstH()).append("],\"z\":").append(i).append('}');
            if (i < entries.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("    ]\n  }]\n}\n");
        return sb.toString();
    }

    /**
     * 把 manifest 引用到的纹理按游戏内实际解析结果复制成一份普通资源包
     * （{@code <gameDir>/viewmyui/default/assets/...}）。
     *
     * <p>只复制这一帧真正用到的图，且按字节流复制、不解码，内存开销与资源总量无关。
     * 编辑器把它当最低优先级资源包，于是"打开就能看"，不必手动导入原版/模组材质。
     */
    private void snapshotTextures(Path root, Set<String> textures) {
        int copied = 0;
        for (String id : textures) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                continue;
            }
            Path target = root.resolve("assets")
                    .resolve(location.getNamespace())
                    .resolve(location.getPath())
                    .normalize();
            if (!target.startsWith(root.normalize())) {
                continue;
            }
            try {
                Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
                if (resource.isEmpty()) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = resource.get().open()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                copied++;
            } catch (IOException e) {
                LOGGER.warn("[viewmyui] 快照纹理失败: {}", id, e);
            }
        }
        LOGGER.info("[viewmyui] 已快照 {} 张纹理 -> {}", copied, root);
    }

    private record Entry(String texture,
                         int srcX, int srcY, int srcW, int srcH,
                         int dstX, int dstY, int dstW, int dstH) {
    }
}
