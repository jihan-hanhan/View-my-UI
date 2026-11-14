package com.viewmyui.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 编辑器配置：记住当前资源包列表，以及用过的历史工作区。
 *
 * <p>放在 manifest 同目录（{@code <gameDir>/viewmyui/editor-config.json}），
 * 因此不同整合包实例各存各的，不会互相串。
 *
 * <p>不记录编辑器自己产出的任何大文件——只有路径列表，几 KB。
 */
final class EditorConfig {

    static final String FILE_NAME = "editor-config.json";

    /** 历史工作区上限，避免下拉框无限增长 */
    private static final int MAX_WORKSPACES = 12;

    /**
     * @param packs   该工作区的资源包（顺序即优先级）
     * @param usedAt  最近使用时间（ISO-8601，仅用于排序/排查）
     */
    record Workspace(List<Path> packs, String usedAt) {
    }

    private final Path file;
    private List<Path> packs = new ArrayList<>();
    private final List<Workspace> workspaces = new ArrayList<>();

    private EditorConfig(Path file) {
        this.file = file;
    }

    static EditorConfig load(Path manifestFile) {
        Path parent = manifestFile == null ? null : manifestFile.getParent();
        Path file = parent == null ? Path.of(FILE_NAME) : parent.resolve(FILE_NAME);
        EditorConfig config = new EditorConfig(file);
        if (!Files.isRegularFile(file)) {
            return config;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            config.packs = readPacks(root.getAsJsonArray("packs"));
            JsonArray array = root.getAsJsonArray("workspaces");
            if (array != null) {
                for (JsonElement element : array) {
                    JsonObject object = element.getAsJsonObject();
                    List<Path> workspacePacks = readPacks(object.getAsJsonArray("packs"));
                    if (!workspacePacks.isEmpty()) {
                        config.workspaces.add(new Workspace(workspacePacks,
                                optString(object, "usedAt", "")));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[viewmyui-editor] 读取配置失败，按空配置继续：" + e.getMessage());
        }
        return config;
    }

    List<Path> packs() {
        return List.copyOf(packs);
    }

    void setPacks(List<Path> value) {
        packs = new ArrayList<>(value);
    }

    List<Workspace> workspaces() {
        return List.copyOf(workspaces);
    }

    /**
     * 把一组包记为最近使用：去重后置顶，超过上限丢掉最旧的。
     * 空列表不记（否则"清空资源包"会污染历史）。
     */
    void remember(List<Path> value) {
        if (value.isEmpty()) {
            return;
        }
        List<Path> copy = List.copyOf(value);
        workspaces.removeIf(workspace -> workspace.packs().equals(copy));
        workspaces.add(0, new Workspace(copy, Instant.now().toString()));
        while (workspaces.size() > MAX_WORKSPACES) {
            workspaces.remove(workspaces.size() - 1);
        }
    }

    void save() {
        JsonObject root = new JsonObject();
        root.add("packs", toArray(packs));

        JsonArray array = new JsonArray();
        for (Workspace workspace : workspaces) {
            JsonObject object = new JsonObject();
            object.add("packs", toArray(workspace.packs()));
            object.addProperty("usedAt", workspace.usedAt());
            array.add(object);
        }
        root.add("workspaces", array);

        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[viewmyui-editor] 保存配置失败：" + e.getMessage());
        }
    }

    private static JsonArray toArray(List<Path> paths) {
        JsonArray array = new JsonArray();
        for (Path path : paths) {
            array.add(path.toString());
        }
        return array;
    }

    private static List<Path> readPacks(JsonArray array) {
        List<Path> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            String value = element.getAsString();
            if (!value.isBlank()) {
                result.add(Path.of(value));
            }
        }
        return result;
    }

    private static String optString(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }
}
