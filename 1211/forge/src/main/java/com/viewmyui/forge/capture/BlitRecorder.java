package com.viewmyui.forge.capture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.fml.loading.FMLPaths;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 采集：把渲染线程上的绘制调用攒起来，按 F9 时落盘成 manifest.json。
 *
 * <p>采用「始终记录、随时落盘」——每帧开始时清空，按 F9 写出的永远是<b>你按下 F9 时屏幕上的那一帧</b>。
 * 不需要"先开始采集再打开界面"，也就不存在忘记结束、录到别的界面的问题。
 *
 * <h2>两种节点（schema v2）</h2>
 * <ul>
 *   <li>{@code blit}：普通纹理绘制，记录纹理 + 源矩形（1.20.1 的形态，编辑器依旧兼容）。</li>
 *   <li>{@code sprite}：1.21.1 的 {@code blitSprite}。按<b>约定路径</b>
 *       {@code assets/<ns>/textures/gui/sprites/<path>.png} 解析成普通纹理，
 *       缩放信息（stretch / tile / nine_slice）直接取同目录 {@code .png.mcmeta}。</li>
 * </ul>
 *
 * <p>输出路径固定为 {@code <gameDir>/viewmyui/manifest.json}，用绝对路径避免 cwd 不一致。
 */
public final class BlitRecorder {

    public static final BlitRecorder INSTANCE = new BlitRecorder();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 防止某些界面无限刷 blit 把内存吃光 */
    private static final int MAX_ENTRIES = 8192;

    private static final String GUI_SPRITE_ROOT = "textures/gui/sprites/";
    /** GUI 图集：精灵名到文件名的换算只对这一张图集生效 */
    private static final ResourceLocation GUI_ATLAS =
            ResourceLocation.withDefaultNamespace("textures/atlas/gui.png");
    private static final String STRETCH_JSON = "{\"type\":\"stretch\"}";

    /** 纹理像素尺寸缓存：uuid -> [w, h]，取不到记 [-1, -1] */
    private final Map<ResourceLocation, int[]> sizes = new HashMap<>();
    /** sprite 的缩放元数据缓存：spriteId -> 规范化 JSON，没有元数据记 null */
    private final Map<String, String> scalings = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    private boolean firstBlitLogged;
    /**
     * {@code blitSprite} 内部会调用 {@code innerBlit}。这里用计数屏蔽嵌套的原始 blit，
     * 避免同一块像素既记成 sprite 又记成 blit、在编辑器里画两遍。
     */
    private int spriteDepth;

    private BlitRecorder() {
    }

    /**
     * 由 {@code RenderFrameEvent.Pre} 调用——每帧一次，且不受嵌套
     * {@code new GuiGraphics} 影响（这正是之前改用构造器注入后丢图的原因）。
     */
    public void frameStart() {
        entries.clear();
        spriteDepth = 0;
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
        // sprite 内部同样会走 innerBlit，此时只保留 sprite 节点
        if (spriteDepth > 0) {
            return;
        }
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
        entries.add(Entry.blit(texture.toString(), srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH));
    }

    /**
     * 由 Mixin 在 {@code GuiGraphics.blitSprite} 的 HEAD 调用。
     *
     * <p>精灵图按约定路径解析：{@code <ns>:textures/gui/sprites/<path>}。
     * 源矩形就是整张图，目标矩形是游戏给出的宽高，缩放语义写在 {@code scaling} 里。
     */
    public void beginSprite(ResourceLocation sprite, int dstX, int dstY, int dstW, int dstH) {
        spriteDepth++;
        if (spriteDepth > 1 || entries.size() >= MAX_ENTRIES) {
            return;
        }
        if (dstW <= 0 || dstH <= 0) {
            return;
        }
        String id = sprite.toString();
        ResourceLocation texture = spriteTexture(sprite);
        if (texture == null) {
            return;
        }
        int[] size = sizeOf(texture);
        if (size[0] <= 0 || size[1] <= 0) {
            // 约定路径下没有这张图（极少见）。不记录，避免画出一个错误的矩形。
            LOGGER.debug("[viewmyui] 精灵图不在约定路径，跳过: {}", texture);
            return;
        }
        if (!firstBlitLogged) {
            firstBlitLogged = true;
            LOGGER.info("[viewmyui] 捕获到首个 sprite: {} dst=({},{},{},{})",
                    sprite, dstX, dstY, dstW, dstH);
        }
        entries.add(Entry.sprite(id, texture.toString(),
                0, 0, size[0], size[1], dstX, dstY, dstW, dstH, scalingOf(sprite)));
    }

    /**
     * 由 Mixin 在九/十参 {@code blitSprite} 的 HEAD 调用。
     *
     * <p>这类调用只取精灵图的一块：{@code (u,v)} 是精灵图像素坐标，画的尺寸就是取的尺寸
     * （原版不做缩放）。熔炉/酿造台/商人的进度条、马鞍箱格等都是这条路径。
     *
     * <p>{@code sourceWidth/sourceHeight} 只是原版给 UV 做归一化用的整图尺寸，
     * 这里直接用约定路径 PNG 的真实尺寸，避免两者不一致时算出错误的源矩形。
     */
    public void beginSprite(ResourceLocation sprite, int u, int v,
                            int dstX, int dstY, int dstW, int dstH) {
        spriteDepth++;
        if (spriteDepth > 1 || entries.size() >= MAX_ENTRIES) {
            return;
        }
        if (dstW <= 0 || dstH <= 0) {
            return;
        }
        ResourceLocation texture = spriteTexture(sprite);
        if (texture == null) {
            return;
        }
        int[] size = sizeOf(texture);
        if (size[0] <= 0 || size[1] <= 0) {
            LOGGER.debug("[viewmyui] 精灵图不在约定路径，跳过: {}", texture);
            return;
        }
        int srcX = clamp(u, 0, size[0]);
        int srcY = clamp(v, 0, size[1]);
        int srcW = Math.min(dstW, size[0] - srcX);
        int srcH = Math.min(dstH, size[1] - srcY);
        if (srcW <= 0 || srcH <= 0) {
            return;
        }
        boolean whole = srcX == 0 && srcY == 0 && srcW == size[0] && srcH == size[1];
        entries.add(Entry.sprite(sprite.toString(), texture.toString(),
                srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH,
                // 取局部时原版忽略 tile / nine_slice，直接拉伸这一块
                whole ? scalingOf(sprite) : STRETCH_JSON));
    }

    /**
     * 由 Mixin 在 {@code blit(..., TextureAtlasSprite)} 的 HEAD 调用。
     *
     * <p>这是 1.21.1 里少数仍直接吃精灵图对象的路径（背包里的空护甲槽、织布机图案槽等）。
     * 拿不到贴图 id，但 {@link TextureAtlasSprite#contents()} 里有精灵名：
     * <ul>
     *   <li>GUI 图集：精灵名就是 {@code textures/gui/sprites/} 下的相对路径；</li>
     *   <li>方块/物品图集：精灵名本身就是纹理路径（{@code block/stone} → {@code textures/block/stone.png}）。</li>
     * </ul>
     */
    public void recordAtlasSprite(TextureAtlasSprite sprite,
                                  int dstX, int dstY, int dstW, int dstH) {
        recordAtlasSprite(sprite, 0.0F, 1.0F, 0.0F, 1.0F, dstX, dstY, dstW, dstH);
    }

    /**
     * @param u0/u1/v0/v1 精灵内部的归一化 UV（0~1），整图绘制时是 0,1,0,1
     */
    public void recordAtlasSprite(TextureAtlasSprite sprite,
                                  float u0, float u1, float v0, float v1,
                                  int dstX, int dstY, int dstW, int dstH) {
        // 已经在外层 sprite 调用里，内层不再重复记
        if (spriteDepth > 0 || entries.size() >= MAX_ENTRIES) {
            return;
        }
        if (dstW <= 0 || dstH <= 0) {
            return;
        }
        boolean gui = GUI_ATLAS.equals(sprite.atlasLocation());
        ResourceLocation name = sprite.contents().name();
        ResourceLocation texture = gui
                ? ResourceLocation.tryParse(name.getNamespace() + ":" + GUI_SPRITE_ROOT + name.getPath() + ".png")
                : ResourceLocation.tryParse(name.getNamespace() + ":textures/" + name.getPath() + ".png");
        if (texture == null) {
            return;
        }
        int[] size = sizeOf(texture);
        if (size[0] <= 0 || size[1] <= 0) {
            // 图集里有些精灵没有对应的独立 PNG（图层/拼合生成），跳过即可
            LOGGER.debug("[viewmyui] 图集精灵没有独立文件，跳过: {} ({})", name, texture);
            return;
        }
        int srcX = clamp(Math.round(u0 * size[0]), 0, size[0]);
        int srcY = clamp(Math.round(v0 * size[1]), 0, size[1]);
        int srcW = Math.min(Math.round((u1 - u0) * size[0]), size[0] - srcX);
        int srcH = Math.min(Math.round((v1 - v0) * size[1]), size[1] - srcY);
        if (srcW <= 0 || srcH <= 0) {
            return;
        }
        if (!gui) {
            entries.add(Entry.blit(texture.toString(),
                    srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH));
            return;
        }
        boolean whole = srcX == 0 && srcY == 0 && srcW == size[0] && srcH == size[1];
        entries.add(Entry.sprite(name.toString(), texture.toString(),
                srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH,
                whole ? scalingOf(name) : STRETCH_JSON));
    }

    private static ResourceLocation spriteTexture(ResourceLocation sprite) {
        // 注意必须带 .png：这里要拿真实资源去读尺寸/快照，
        // ResourceManager 不像编辑器的 PackResolver 那样会自动补扩展名
        return ResourceLocation.tryParse(
                sprite.getNamespace() + ":" + GUI_SPRITE_ROOT + sprite.getPath() + ".png");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void endSprite() {
        if (spriteDepth > 0) {
            spriteDepth--;
        }
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
     * 从 {@code <sprite>.png.mcmeta} 读 {@code gui.scaling}，规范化成编辑器好读的形状：
     * {@code {"type":"nine_slice","border":[l,t,r,b],"stretchInner":true}} / {@code {"type":"tile"}}。
     *
     * @return {@code null} 表示没有元数据（按 stretch 处理）
     */
    private String scalingOf(ResourceLocation sprite) {
        String key = sprite.toString();
        if (scalings.containsKey(key)) {
            return scalings.get(key);
        }
        String value = loadScaling(sprite);
        scalings.put(key, value);
        return value;
    }

    /**
     * @return 规范化后的 scaling JSON，{@code null} 表示没有元数据（按 stretch 处理）
     */
    private String loadScaling(ResourceLocation sprite) {
        // 约定路径：assets/<ns>/textures/gui/sprites/<path>.png.mcmeta
        ResourceLocation meta = ResourceLocation.tryParse(
                sprite.getNamespace() + ":" + GUI_SPRITE_ROOT + sprite.getPath() + ".png.mcmeta");
        if (meta == null) {
            return null;
        }
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(meta);
            if (resource.isEmpty()) {
                return null;
            }
            String json;
            try (InputStream in = resource.get().open()) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return normalizeScaling(json);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[viewmyui] 读取精灵图元数据失败: {}", meta, e);
            return null;
        }
    }

    /**
     * @return 规范化后的 scaling JSON；无法识别时 {@code null}
     */
    private static String normalizeScaling(String mcmeta) {
        JsonObject root = JsonParser.parseString(mcmeta).getAsJsonObject();
        JsonObject gui = root.getAsJsonObject("gui");
        if (gui == null) {
            return null;
        }
        JsonElement scalingElement = gui.get("scaling");
        if (scalingElement == null || !scalingElement.isJsonObject()) {
            return null;
        }
        JsonObject scaling = scalingElement.getAsJsonObject();
        String type = scaling.has("type") ? scaling.get("type").getAsString() : "stretch";

        if ("nine_slice".equals(type)) {
            int[] border = border(scaling.get("border"));
            boolean stretchInner = !scaling.has("stretch_inner")
                    || scaling.get("stretch_inner").getAsBoolean();
            return "{\"type\":\"nine_slice\",\"border\":[" + border[0] + "," + border[1] + ","
                    + border[2] + "," + border[3] + "],\"stretchInner\":" + stretchInner + "}";
        }
        if ("tile".equals(type)) {
            return "{\"type\":\"tile\"}";
        }
        return "{\"type\":\"stretch\"}";
    }

    /**
     * {@code border} 既可以是整数（四边相同），也可以是 {@code {left,top,right,bottom}}。
     */
    private static int[] border(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new int[]{0, 0, 0, 0};
        }
        if (element.isJsonPrimitive()) {
            int all = element.getAsInt();
            return new int[]{all, all, all, all};
        }
        JsonObject object = element.getAsJsonObject();
        return new int[]{
                optInt(object, "left", 0),
                optInt(object, "top", 0),
                optInt(object, "right", 0),
                optInt(object, "bottom", 0)
        };
    }

    private static int optInt(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
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
     * 把当前这一帧的绘制写成 manifest。
     *
     * @return 给玩家看的一句话摘要（含捕获到的纹理名，方便确认目标贴图有没有进来）
     */
    public String flush() {
        if (entries.isEmpty()) {
            LOGGER.info("[viewmyui] 这一帧没有记录到任何绘制调用");
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
        // 1.21.1 的 GUI 缩放是 double（自动缩放可能是 2.5 这类值）
        double guiScale = minecraft.getWindow().getGuiScale();

        StringBuilder sb = new StringBuilder(entries.size() * 200 + 256);
        sb.append("{\n  \"schemaVersion\": 2,\n");
        sb.append("  \"meta\": {\"minecraft\": \"1.21.1\", \"loader\": \"neoforge\", \"guiScale\": ")
                .append(formatScale(guiScale)).append(", \"capturedFrom\": \"runtime\"},\n");
        sb.append("  \"screens\": [{\n");
        sb.append("    \"id\": \"capture\",\n");
        sb.append("    \"title\": \"capture\",\n");
        sb.append("    \"width\": ").append(width).append(",\n");
        sb.append("    \"height\": ").append(height).append(",\n");
        sb.append("    \"nodes\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            sb.append("      {\"type\":\"").append(e.kind()).append('"');
            if (e.sprite() != null) {
                sb.append(",\"sprite\":\"").append(e.sprite()).append('"');
            }
            sb.append(",\"texture\":\"").append(e.texture())
                    .append("\",\"src\":[").append(e.srcX()).append(',').append(e.srcY()).append(',')
                    .append(e.srcW()).append(',').append(e.srcH())
                    .append("],\"dst\":[").append(e.dstX()).append(',').append(e.dstY()).append(',')
                    .append(e.dstW()).append(',').append(e.dstH()).append(']');
            if (e.sprite() != null && e.scaling() != null) {
                sb.append(",\"scaling\":").append(e.scaling());
            }
            sb.append(",\"z\":").append(i).append('}');
            if (i < entries.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("    ]\n  }]\n}\n");
        return sb.toString();
    }

    /** {@code 2.0} 写成 {@code 2}，{@code 2.5} 保持 {@code 2.5} */
    private static String formatScale(double scale) {
        String text = String.format(Locale.ROOT, "%.3f", scale);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text.isEmpty() ? "1" : text;
    }

    /**
     * 把 manifest 引用到的纹理按游戏内实际解析结果复制成一份普通资源包
     * （{@code <gameDir>/viewmyui/default/assets/...}）。
     *
     * <p>只复制这一帧真正用到的图，且按字节流复制、不解码，内存开销与资源总量无关。
     * 精灵图连同 {@code .png.mcmeta} 一起复制，编辑器才能拿到缩放元数据。
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
                copySpriteMetadata(root, id);
            } catch (IOException e) {
                LOGGER.warn("[viewmyui] 快照纹理失败: {}", id, e);
            }
        }
        LOGGER.info("[viewmyui] 已快照 {} 张纹理 -> {}", copied, root);
    }

    /**
     * 精灵图的缩放信息在 {@code <png>.mcmeta} 里，编辑器（或用户手工排查）需要它。
     */
    private void copySpriteMetadata(Path root, String texture) {
        if (!texture.contains(GUI_SPRITE_ROOT)) {
            return;
        }
        String base = texture.endsWith(".png") ? texture : texture + ".png";
        ResourceLocation meta = ResourceLocation.tryParse(base + ".mcmeta");
        if (meta == null) {
            return;
        }
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(meta);
            if (resource.isEmpty()) {
                return;
            }
            Path target = root.resolve("assets")
                    .resolve(meta.getNamespace())
                    .resolve(meta.getPath())
                    .normalize();
            if (!target.startsWith(root.normalize())) {
                return;
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = resource.get().open()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("[viewmyui] 快照精灵图元数据失败: {}", texture, e);
        }
    }

    private record Entry(String kind,
                         String texture,
                         String sprite,
                         int srcX, int srcY, int srcW, int srcH,
                         int dstX, int dstY, int dstW, int dstH,
                         String scaling) {

        static Entry blit(String texture,
                          int srcX, int srcY, int srcW, int srcH,
                          int dstX, int dstY, int dstW, int dstH) {
            return new Entry("blit", texture, null,
                    srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH, null);
        }

        static Entry sprite(String sprite, String texture,
                            int srcX, int srcY, int srcW, int srcH,
                            int dstX, int dstY, int dstW, int dstH,
                            String scaling) {
            return new Entry("sprite", texture, sprite,
                    srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH, scaling);
        }
    }
}
