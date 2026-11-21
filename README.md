# View My UI

Minecraft 界面采集与离线预览工具：游戏里按 **F9** 把当前界面的所有 `blit` 记录成
manifest，按 **F10** 用独立的 JavaFX 编辑器打开，直接看到「界面由哪些精灵图、哪些
源矩形拼出来」。面向补丁 / 资源包作者改贴图。

支持 **1.20.1 Forge** 与 **1.21.1 NeoForge**，两个版本共用同一份 `core` 与 `editor`。

## 组成

| 模块 | 说明 |
|---|---|
| `core` | manifest 解析、资源包叠加、合成、差异比较（纯 Java，零 Minecraft 依赖） |
| `editor` | JavaFX 编辑器：画布、树状节点列表、资源包优先级、九宫格、导出、热重载 |
| `forge` | 1.20.1 Forge 薄壳：Mixin 采集、F9 落盘、F10 拉起编辑器；编辑器内嵌在 jar 里 |
| `1211/forge` | 1.21.1 NeoForge 薄壳，与 `forge` 行为一致，共用上面的 `core` / `editor` |
| `dist` | 本地发布产物（构建生成，不入库） |

## 环境

- JDK 17（`core` / `editor` / 1.20.1 模组）与 JDK 21（1.21.1 模组）
- Gradle 8.14.3（wrapper 已指定；NeoGradle 7 要求 8.14+，ForgeGradle 6 也兼容）
- 首次构建需要网络（Gradle 发行版、Forge / NeoForge、JavaFX 各平台 jar）
- 设计与进度记录见 `roadmap.md`

## 构建

```bash
./gradlew build                  # 两个版本一起构建（Windows: gradlew.bat build）
./gradlew :forge:build           # 只构建 1.20.1
./gradlew :1211:forge:build      # 只构建 1.21.1
```

产物：

- `forge/build/libs/viewmyui-forge-1.20.1-0.1.0.jar` —— 1.20.1 模组 + 内嵌编辑器（约 29 MB）
- `1211/forge/build/libs/viewmyui-neoforge-1.21.1-0.2.0.jar` —— 1.21.1 模组 + 内嵌编辑器
- `editor/build/distributions/editor-0.1.0.zip` —— 独立编辑器（只含本平台 JavaFX）

只重新整理内嵌资源：`./gradlew :editor:editorBundle`。

## 使用

1. 把模组 jar 放进 `mods/`；
2. 游戏内按 **F9** 采集当前界面 → `<gameDir>/viewmyui/manifest.json`；
3. 按 **F10** 打开编辑器。首次会自动把 jar 内嵌的编辑器解压到
   `<gameDir>/viewmyui/editor/<版本>/`，之后复用；起不来先看 `viewmyui/editor.log`。

编辑器会自动带上采集时实际使用的材质（快照在 `viewmyui/default/`），无需手动导入资源包。

## 编辑器

画布与悬停/选中联动、按纹理分组的树状节点表、资源包增删与优先级、0.25x–16x 缩放、
九宫格校验、节点框 / 像素网格、合成图与切图参考图导出、manifest 与资源包热重载。

「缩放采样」默认 **无**：按游戏 `guiScale` 的物理分辨率合成（群峦铁砧那种 32×32→16
逻辑像素的精灵图会和游戏一样 1:1）；另有「最近邻 / 双线性」两档逻辑像素对照。

## 开发

```bash
./gradlew :forge:runClient                               # 1.20.1 开发环境客户端
./gradlew :1211:forge:runClient                          # 1.21.1 开发环境客户端
./gradlew :forge:runClient -PviewmyuiAutocapture=true    # 进界面后自动采集
./gradlew :editor:run                                    # 直接运行编辑器
```

开发环境默认从 `forge/run/viewmyui/editor`（1.21.1 为 `1211/forge/run/viewmyui/editor`）
找编辑器，发布版优先用 jar 内嵌的那份；
两种情况都可以用 `-Dviewmyui.editor=<路径>` 或环境变量 `VIEWMYUI_EDITOR` 覆盖。

## CI

两个独立 workflow：`.github/workflows/build-1201.yml` 与 `build-1211.yml`。

## 已知限制

- 只采集 `GuiGraphics.blit` 路径；自己绑纹理 + `RenderSystem` 的绘制尚未支持
- 尚未记录 `RenderSystem.setShaderColor` 的 tint，编辑器显示的是未着色版本。
- JavaFX 不发布 `linux-aarch64` / `win-aarch64`，这两类机器需要外置编辑器。
