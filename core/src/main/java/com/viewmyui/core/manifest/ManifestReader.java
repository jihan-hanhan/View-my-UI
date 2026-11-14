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

/**
 * 读 {@code manifest.json}。对缺失字段一律给默认值——
 * manifest 是给人看/给工具产的中间产物，不该因为少一个 meta 字段就拒绝加载。
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
                optInt(meta, "guiScale", 0),
                optString(meta, "capturedFrom", "unknown"));
    }

    private static ScreenScene readScreen(JsonObject screen, int guiScale) {
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
     * 目前只认 {@code blit}；文字/物品等占位节点先跳过（roadmap Phase 4 再补）。
     */
    private static BlitNode readNode(JsonObject node, int index) {
        String type = optString(node, "type", "blit");
        if (!"blit".equals(type) || !node.has("texture")) {
            return null;
        }
        int[] src = intArray(node.getAsJsonArray("src"));
        int[] dst = intArray(node.getAsJsonArray("dst"));
        if (src == null || dst == null) {
            return null;
        }
        return new BlitNode(optString(node, "texture", ""),
                src[0], src[1], src[2], src[3],
                dst[0], dst[1], dst[2], dst[3],
                optInt(node, "z", index));
    }

    private static int[] intArray(JsonArray array) {
        if (array == null || array.size() < 4) {
            return null;
        }
        return new int[]{array.get(0).getAsInt(), array.get(1).getAsInt(),
                array.get(2).getAsInt(), array.get(3).getAsInt()};
    }

    private static int optInt(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    private static String optString(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }
}
