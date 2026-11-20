package com.viewmyui.core.manifest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 读 {@code manifest.json}。对缺失字段一律给默认值——
 * manifest 是给人看/给工具产的中间产物，不该因为少一个 meta 字段就拒绝加载。
 *
 * <p>同时支持 schema v1（1.20.1：只有 {@code blit}）与 v2（1.21.1：多了 {@code sprite} / {@code scaling}）。
 * 这正是「同一份编辑器服务两代游戏」的关键：v1 的节点读出来就是白色、stretch 的普通 blit。
 */
public final class ManifestReader {

    private ManifestReader() {
    }

    public static UiManifest read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static UiManifest parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        int schemaVersion = optInt(root, "schemaVersion", 1);
        UiManifest.Meta meta = readMeta(root.getAsJsonObject("meta"));

        List<ScreenScene> screens = new ArrayList<>();
        JsonArray screenArray = root.getAsJsonArray("screens");
        if (screenArray != null) {
            for (JsonElement element : screenArray) {
                screens.add(readScreen(element.getAsJsonObject(), meta.guiScale()));
            }
        }
        return new UiManifest(schemaVersion, meta, screens);
    }

    private static UiManifest.Meta readMeta(JsonObject meta) {
        if (meta == null) {
            return new UiManifest.Meta("unknown", "unknown", null, 0, "unknown");
        }
        return new UiManifest.Meta(
                optString(meta, "minecraft", "unknown"),
                optString(meta, "loader", "unknown"),
                meta.has("modId") ? meta.get("modId").getAsString() : null,
                optDouble(meta, "guiScale", 0),
                optString(meta, "capturedFrom", "unknown"));
    }

    private static ScreenScene readScreen(JsonObject screen, double guiScale) {
        List<BlitNode> nodes = new ArrayList<>();
        JsonArray nodeArray = screen.getAsJsonArray("nodes");
        if (nodeArray != null) {
            int index = 0;
            for (JsonElement element : nodeArray) {
                BlitNode node = readNode(element.getAsJsonObject(), index++);
                if (node != null) {
                    nodes.add(node);
                }
            }
        }
        return new ScreenScene(
                optString(screen, "id", "capture"),
                optString(screen, "title", "capture"),
                optInt(screen, "width", 0),
                optInt(screen, "height", 0),
                guiScale,
                nodes);
    }

    /**
     * 认两种节点：
     * <ul>
     *   <li>{@code blit}：纹理 + src + dst；</li>
     *   <li>{@code sprite}：按约定路径 {@code <ns>:textures/gui/sprites/<path>} 解析成纹理，
     *       src 是整张图，{@code scaling} 描述缩放语义。</li>
     * </ul>
     * 无法还原的节点（缺 dst 等）跳过。文字/物品等占位节点留到 Phase 4。
     */
    private static BlitNode readNode(JsonObject node, int index) {
        String type = optString(node, "type", "blit");
        boolean sprite = "sprite".equals(type);
        if (!sprite && !"blit".equals(type)) {
            return null;
        }

        String texture = optString(node, "texture", "");
        if (texture.isEmpty() && sprite) {
            String id = optString(node, "sprite", "");
            if (id.isEmpty()) {
                return null;
            }
            texture = spriteTexture(id);
        }
        if (texture.isEmpty()) {
            return null;
        }

        int[] dst = intArray(node.getAsJsonArray("dst"), 4);
        if (dst == null) {
            return null;
        }
        int[] src = intArray(node.getAsJsonArray("src"), 4);
        if (src == null) {
            // 精灵图没有显式 src 时按整张图处理；具体尺寸编辑器读到图后再兜底
            src = new int[]{0, 0, dst[2], dst[3]};
        }

        return new BlitNode(texture,
                src[0], src[1], src[2], src[3],
                dst[0], dst[1], dst[2], dst[3],
                optInt(node, "z", index),
                readScaling(node.getAsJsonObject("scaling")));
    }

    /**
     * 1.21.1 精灵图的约定路径：{@code minecraft:widget/button} →
     * {@code minecraft:textures/gui/sprites/widget/button}（PackResolver 会补 {@code .png}）。
     */
    private static String spriteTexture(String sprite) {
        int colon = sprite.indexOf(':');
        String namespace = colon >= 0 ? sprite.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? sprite.substring(colon + 1) : sprite;
        return namespace + ":textures/gui/sprites/" + path;
    }

    private static GuiScaling readScaling(JsonObject scaling) {
        if (scaling == null) {
            return GuiScaling.STRETCH;
        }
        String type = optString(scaling, "type", "stretch");
        if ("nine_slice".equals(type)) {
            int[] border = intArray(scaling.getAsJsonArray("border"), 4);
            if (border == null) {
                border = new int[]{0, 0, 0, 0};
            }
            return new GuiScaling(GuiScaling.Type.NINE_SLICE,
                    border[0], border[1], border[2], border[3],
                    optBoolean(scaling, "stretchInner", true));
        }
        if ("tile".equals(type)) {
            return new GuiScaling(GuiScaling.Type.TILE, 0, 0, 0, 0, true);
        }
        return GuiScaling.STRETCH;
    }

    private static int[] intArray(JsonArray array, int size) {
        if (array == null || array.size() < size) {
            return null;
        }
        int[] values = new int[size];
        for (int i = 0; i < size; i++) {
            values[i] = array.get(i).getAsInt();
        }
        return values;
    }

    private static int optInt(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    private static double optDouble(JsonObject object, String name, double fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static boolean optBoolean(JsonObject object, String name, boolean fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
    }

    private static String optString(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    /** 保留给调试输出的倍率格式：2.0 → "2"，2.5 → "2.5" */
    static String formatScale(double scale) {
        String text = String.format(Locale.ROOT, "%.3f", scale);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text.isEmpty() ? "1" : text;
    }
}
