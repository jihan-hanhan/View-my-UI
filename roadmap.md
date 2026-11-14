# Minecraft 模组 UI 补丁预览器 — 技术路线图

> 目标：让材质包 / UI 补丁作者在**不启动游戏、不反复重载资源包**的前提下，按游戏内的真实布局拼接并预览模组 UI，
> 从而验证精灵图（sprite sheet）切图有没有画错、九宫格拉伸有没有变形、边界像素有没有溢出。

> **当前定向（2026-11-14 定案）**：技术栈 **全 Java**；首个目标平台 **Minecraft 1.20.1 + Forge**；
> 进程模型见 [2.3](#23-进程模型与文件访问已定案)。

---

## 0. 当前进度总览（2026-11-14）

**一句话**：`采集 → manifest → 编辑器合成` 这条主链路已经端到端跑通（真实客户端 + 真实 HUD + TFC 砧界面验证过），
编辑器打开后能自动带出游戏内实际使用的材质，不再需要手动导入资源包。

| 模块 | 状态 | 说明 |
|---|---|---|
| `core`（manifest 解析 / 资源包叠加 / 合成） | ✅ 可用 | 纯 Java，零 Minecraft 依赖 |
| `forge-mod` 采集 | ✅ 可用 | Mixin 拦 `GuiGraphics.innerBlit`；帧边界 = `RenderTickEvent.START`；F9 落盘 |
| 游戏内热键 | ✅ 可用 | 监听 `InputEvent.Key`，GUI 打开时也能触发；键位跟随原版按键绑定 |
| `editor`（JavaFX 独立进程） | ✅ 可用 | 画布 / 树状节点列表 / 源纹理 / 资源包优先级 / 导出 / 热重载 / 自由缩放 / 九宫格校验 |
| F10 拉起编辑器 | ✅ 可用 | 用游戏自身 JVM 执行 `java -cp <editor>/lib/*`，输出写 `editor.log`；聊天栏提示避免连点 |
| 编辑器随模组分发 | ✅ 可用 | 编辑器（含全平台 JavaFX）内嵌在模组 jar 的 `META-INF/viewmyui/editor/`，首次 F10 解压到 `<gameDir>/viewmyui/editor/<版本>/` 再启动 |
| 原版 / 补丁包并排对比 | ✅ 可用 | 两套资源包各合成一张 → 左右并排 + 洋红差异高亮；CLI `--compare` |
| 默认材质自动带出 | ✅ 可用 | F9 时把 manifest 引用到的纹理流式快照到 `viewmyui/default/assets/`，编辑器自动挂为最低优先级包 |
| 工作区记忆 | ✅ 可用 | `editor-config.json` 记录当前包列表 + 最近 12 个历史工作区（下拉切换） |
| 自由缩放 | ✅ 可用 | 0.25x–16x 滑条（Ctrl+滚轮 0.5 步进），不再局限 1/2/4 |
| 缩放采样切换 | ✅ 可用 | 「无」按 `guiScale` 物理分辨率合成（32×32→16 逻辑像素在 GUI 2x 下即 1:1，与游戏一致）；另有「最近邻」「双线性」两档逻辑像素对照 |
| 九宫格校验 | ✅ 可用 | 对缩放节点画 3×3 分割线 + 拉伸区着色，源矩形越界标红；边框像素可调（默认 4） |
| 切图参考图导出 | ✅ 可用 | 每张精灵图导出一张标注全部 `src` 矩形的 PNG；GUI 与 CLI `--references` |
| 非 `GuiGraphics` 绘制（自己绑纹理 + `RenderSystem`） | ⬜ 未支持 | TFC 砧界面已确认走 `GuiGraphics`，可正常采集；该路径仅为其他模组兜底 |
| `blitSprite` / `enableScissor` | ⬜ 未支持 | `blitSprite` 是 1.20.2+ 的东西；scissor 影响可见性 |

**已完成（按时间顺序）**

1. 技术选型与架构定案：全 Java、`core` + Forge 薄壳 + JavaFX 双进程（第 2 章）。
2. 工具链在国内网络下落成：Gradle 8.1.1 + JDK 17 + ForgeGradle 6 + Forge 1.20.1-47.2.0（8.1–8.3）。
3. Mixin 注册方式实测定案：dev 用 `--mixin`，生产用 jar manifest 的 `MixinConfigs`；免 refmap 双名注入（8.5）。
4. 采集粒度两次修正：多帧累加 → 构造器清帧（会丢 TFC 界面）→ `RenderTickEvent.START` 帧边界（8.6）。
5. 热键修正：`consumeClick()` 拿不到 GUI 里的按键 → 改走 `InputEvent.Key`（8.6）。
6. 编辑器 MVP + 功能补齐，并用真实客户端 manifest 验证合成、联动、纹理来源显示（8.6 / 8.7）。
7. 原版 / 补丁包并排对比：双包列表 → 两套合成 → 差异高亮，GUI 与 CLI 双通道（8.8）。
8. TFC 砧界面实机确认：容器界面里的模组精灵图能正常抓到，非 `GuiGraphics` 兜底暂不需要。
9. 默认材质快照 + 工作区记忆：打开编辑器零导入；历史包组合自动保留（8.9）。
10. 精修：自由缩放、树状节点列表、九宫格校验、参考图导出、最近邻缩放采样、F10 聊天提示（8.10）。
11. 编辑器内嵌进模组 jar：Gradle 自动打包 core + editor + 四平台 JavaFX，首次 F10 自解压启动（8.11）。

**下一步（按优先级）**

1. **资源包元信息**：读 `pack.mcmeta` 的 `pack_format` 并提示版本不匹配；支持一个包继承另一个包。
2. **分发**：编辑器已随模组 jar 内嵌（8.11），无需再手工补全；jpackage 自带 runtime 降级为可选优化。

统一收口点：以上 1 项应落在 `editor` + `core`，模组侧保持薄壳（第 2.3 节）。

---

## 1. 问题定义

模组 UI 的本质是「一堆 `blit(纹理, 源矩形, 目标矩形)` 调用」，纹理是精灵图。
画错的情况通常只有这几类：

| 错误类型 | 现象 | 能否被本工具检出 |
|---|---|---|
| 切图区域偏移 | 边框串色、图案错位 | ✅ 核心能力 |
| 九宫格边角尺寸不对 | 拉伸变形、边框粗细不均 | ✅ 核心能力 |
| 纹理尺寸/画布尺寸不匹配 | 平铺、留白、整体缩放 | ✅ |
| 透明区域没抠干净 | 边缘黑边/白边 | ✅（可选描边检测） |
| 状态图放错位置（hover/pressed/disabled） | 交互时跳变 | ✅（需状态表） |
| 颜色 tint / alpha 用错 | 颜色发灰或过曝 | ✅ |
| 动态内容（文字、进度条、物品图标） | 无法覆盖 | ⚠️ 只能占位/模拟 |

因此工具的核心产物不是「截图」，而是 **UI 布局清单（UI Manifest）**：
把「画什么、画在哪、用什么纹理的哪一块」从「纹理内容」中解耦出来。
只要清单是准确的，纹理换成任意资源包都能立即预览。

---

## 2. 关键技术决策

### 2.1 如何确定模组的 UI 呈现形式

按可靠性从高到低，共有三条路线，**推荐混合使用（A 为主，B 兜底）**。

#### 路线 A：运行时拦截绘制调用（推荐主方案）

原理：在客户端渲染路径上挂钩，把每一次 `blit` 及其参数（纹理 ID、u/v/w/h、目标 x/y/w/h、
tint、blend、当前 GUI scale、z 序）落盘成 JSON。

可行性：
- 现代 Minecraft（1.17+）的 `GuiGraphics.blit(...)` 是**唯一的 2D 绘制入口**，重载方法虽多但最终汇聚到少数几个。
- 实现载体有两种：
  - **伴随模组（Companion Mod）**：Fabric / NeoForge 各写一个极小的 mod，用 Mixin 在 `GuiGraphics` 上 `@Inject`。
    优点：不用关心 class 重定位与混淆，跟着目标模组的加载器版本走即可；缺点：要为目标加载器各维护一份。
  - **Java Agent（ASM 字节码插桩）**：`-javaagent` 挂进 JVM，直接改写 `GuiGraphics` 的方法体。
    优点：与模组加载器解耦；缺点：1.20.5+ 之后部分类有混淆/重定位，需要处理 mapping（Mojmap / intermediary）。
- 输出：`manifest.json`，按「打开一次界面 = 一个 Scene」组织，节点含 `textureId / srcRect / dstRect / tint / z / guiScale / parentScreen`。

优点：**100% 真实**，不依赖源码、不需要反编译，兼容闭源模组。
缺点：必须能在本机跑起目标模组（需要同版本、同加载器、同依赖）。

#### 路线 B：静态分析（源码或字节码），作为无运行环境时的兜底

三种输入，成本递增、准确度递增：

1. **有源码**（GitHub / CurseForge 源码包）：
   直接扫描 `blit(...)` / `blitSprite(...)` 调用点，抽常量。可用 tree-sitter-java 做语法级解析，
   比正则可靠（能拿到完整参数列表与变量绑定）。
2. **只有 jar**：
   - 反编译：**Vineflower**（JF 系，对 Java 17+ / record / switch 表达式支持最好）或 CFR。
   - 反编译后走与 (1) 相同的解析流程。
   - 退化方案：不反编译，直接读 class 常量池——`CONSTANT_String` 里能拿到纹理路径，
     `LDC` 的整型常量能拿到大量硬编码坐标，但**丢失结构**，只能用来猜。
3. **启发式推断**：对 GUI 纹理做连通域分析，自动猜测九宫格边角尺寸与控件块；
   配合 `pack.mcmeta` / 官方原版 GUI 纹理做比对，用于「连源码都没有」的极端场景。

优点：不需要运行环境，可批处理。
缺点：**必然不完整**——坐标常常来自运行时计算（`this.width / 2 - 90`、来自配置文件的偏移、
循环绘制的列表项），静态分析无法还原；一旦逻辑分支多，误报率高。

#### 路线 C：UI 库识别（增益项，不是必需品）

若目标模组用 Cloth Config / ModMenu / owo-lib / LibGui 等，它们的界面是**声明式**的，
可以从配置类反推控件树，再按库的默认皮肤渲染。这比逐 blit 还原高级得多，但强依赖具体库，
只作为特定热门的加分项。

**结论**：主链路 = 路线 A 产出 Manifest；路线 B 用于「没有运行环境 / 只想快速看一眼」的降级路径。

### 2.2 语言与平台

先明确模块划分，再谈语言——因为不同模块的最优解不同：

| 模块 | 职责 | 建议语言 | 理由 |
|---|---|---|---|
| **Capture（采集器）** | 在游戏内拦截 blit，输出 manifest | **Java** | 几乎无替代。Mixin / ASM / Vineflower 全在 JVM 生态，且必须运行在客户端 JVM 内 |
| **Analyzer（静态分析）** | 反编译 jar + 解析调用点 | **Java**（调 Vineflower API） | 避免跨语言胶水；Vineflower 本身就是 Java 库 |
| **Viewer（预览器）** | 读 manifest + 资源包，合成画面 | 见下方选型 | 纯 2D 合成，性能要求低 |
| **Authoring（辅助）** | 九宫格标尺、切图网格、差异对比、热重载 | 同 Viewer | — |

**Viewer 的三个候选：**

| 方案 | 优点 | 缺点 | 适用 |
|---|---|---|---|
| **Java + JavaFX / LWJGL** | 单语言栈，与 Capture 共用数据模型；可反过来做成「游戏内 F8 面板」 | JavaFX 打包体积大；UI 框架生态弱于 Web | 想要**单一技术栈**、可接受打包体积 |
| **Rust + egui / wgpu** | 单文件分发、启动快、纹理与混合控制精细 | 与 Java 生态割裂，JSON/PNG 之外的边界处理都要手写；开发迭代慢 | 想要**最终交付成一个绿色小工具** |
| **TypeScript + Canvas2D / WebGL**（Electron 或 Tauri 外壳） | 2D 合成与九宫格绘制最省事；热重载、缩放、拖拽、UI 面板开发最快；`requestAnimationFrame` 天然适合做状态预览 | 需要 Node/前端工具链；Electron 体积大（Tauri 可缓解） | 想要**最快看到效果**、后续还要做大量交互式编辑 |

> 补充：用户的 HTML/CSS 经验有限。若选 Web 方案，建议坚持用 **Canvas 2D 直接绘制**（`drawImage` 的 9 参数版本天然支持源矩形裁切，
> 几乎是 `blit` 的一一映射），**不引入 Vue/React**，用原生 TS + 少量 DOM 做侧栏即可。

**最终选择：全 Java —— 核心库 + 双进程前端（已定案，不再选型）**

否决理由：
- **Rust**：与 JVM 生态割裂。采集器只能用 Java，跨语言胶水（JSON schema 双份维护、构建链两套）
  的收益不足以抵消成本。
- **TypeScript / Web**：Canvas2D 绘制确实最省事，但会引入第二套工具链与 IPC 边界，且用户前端经验有限。
- **Go / Python**：2D 精细混合与分发体验均不占优，仅保留用一次性脚本（PNG 连通域分析）。

选择 Java 的核心理由：**采集必须跑在游戏 JVM 内**。单栈可让「采集 → manifest → 合成 → 预览」
共用同一份数据模型，避免 schema 在两种语言之间漂移。

**模块划分：**

| 模块 | 职责 | 形态 |
|---|---|---|
| `core` | manifest 解析 / 资源包叠加 / 九宫格计算 / 合成出 `BufferedImage` | 纯 Java 库，**零 Minecraft 依赖** |
| `forge-mod` | Mixin 拦 `blit` → 写 manifest；F9 采集；F10 拉起编辑器；可选 Overlay | Forge 模组，**薄壳** |
| `editor` | JavaFX 编辑器：图层树、标尺、切图网格、diff、热重载 | 独立进程，jpackage 打包 |

**关键约束：`core` 不许 `import net.minecraft.*`。** 这是「换 MC 版本时编辑器和核心逻辑一行不用动」
的唯一保障。

### 2.3 进程模型与文件访问（已定案）

Minecraft 的 `Screen` 体系不足以支撑编辑器（无文本编辑框、表格、树形目录、代码高亮），
且开第二个 GL 窗口会与 GLFW 抢焦点、抢 GL 上下文、触发游戏自动暂停。
因此编辑器**必须是独立进程**，模组只负责拉起它。

```
Minecraft 进程                     编辑器进程（jpackage 独立 exe）
┌──────────────────────┐          ┌──────────────────────────┐
│ Mixin 拦 blit        │          │ JavaFX 编辑器             │
│ F9 采集 → manifest   │──写文件──▶│ WatchService 监听 → 重绘  │
│ F10 拉起编辑器 ──────┼─spawn───▶ │                          │
│ （可选）Overlay 对比  │◀─HTTP────│ 反向指令（重截/换包）     │
└──────────────────────┘          └──────────────────────────┘
```

**文件系统**：Minecraft 无文件系统沙箱，`java.nio.file` 可读写任意绝对路径，
所以材质包工作目录在实例之外完全没问题。两个注意点：

1. 相对路径的基准是**进程 cwd，不一定是 gameDir** —— 统一用绝对路径。
2. `ResourceManager` 只扫 `<gameDir>/resourcepacks`，**看不到外部包**。
   预览器绕开它，自己用 `ImageIO` 读 PNG 再合成；仅当需要「游戏内真实渲染也用外部包」时，
   才注入 `PathResourcePack`（Forge：`AddPackFindersEvent`）。

**ProcessBuilder 的四个坑：**

| 坑 | 现象 | 对策 |
|---|---|---|
| 依赖系统 Java | 1.20.1 是 Java 17、1.21 是 21，编辑器要求 21 就在旧版本崩 | jpackage 自带裁剪 runtime，与游戏 JVM 完全脱钩 |
| 未消费 stdout/stderr | 管道缓冲满 → 子进程假死 | `redirectErrorStream(true)` + `redirectOutput(DISCARD)` 或日志文件 |
| 继承 cwd | 相对路径指向错误目录 | `directory(...)` 显式指定编辑器目录 |
| 重复 spawn | 按几次键开几个窗口 | 持有 `Process` 引用 + `isAlive()`；跨启动用锁文件/端口占用兜底 |

**通信通道**：manifest 走「文件 + `WatchService`」——零依赖、跨版本零维护、崩溃可恢复，
且 manifest 落盘后天然可 diff，便于以后做 UI 回归测试。反向指令（重截、换包）
后续按需加 `127.0.0.1` 本地 HTTP，不提前建设。

**留在游戏内的只剩三件事**：拦 `blit`、写文件、贴一张 Overlay 图（可选）。全部是薄壳。

---

## 3. 核心数据结构：UI Manifest

```jsonc
{
  "schemaVersion": 1,
  "meta": {
    "minecraft": "1.20.1",
    "loader": "forge-47.2.0",
    "mod": { "id": "examplemod", "version": "1.4.2", "jarSha1": "..." },
    "guiScale": 3,
    "window": [854, 480],
    "capturedFrom": "runtime"            // runtime | static-decompile | manual
  },
  "screens": [
    {
      "id": "examplemod:main_menu",
      "title": "Main Menu",
      "size": [320, 240],
      "nodes": [
        {
          "type": "blit",
          "texture": "examplemod:textures/gui/main.png",
          "src": [0, 0, 8, 8],          // u, v, w, h —— 像素坐标
          "dst": [10, 10, 8, 8],        // x, y, w, h
          "tint": "#FFFFFFFF",
          "blend": "normal",
          "z": 0,
          "nineSlice": null              // 或 { "border": [4,4,4,4] }
        },
        { "type": "text", "value": "%s", "pos": [120, 20], "color": "#404040", "placeholder": true },
        { "type": "item", "itemId": "minecraft:diamond", "pos": [40, 60], "placeholder": true }
      ]
    }
  ]
}
```

设计要点：
- `src` 用**纹理像素**，`dst` 用**界面像素**，两者分离（这是游戏内 `blit` 的真实语义，也是还原缩放关系的关键）。
- `placeholder: true` 标记无法静态还原的动态内容，预览器画占位框即可。
- 采集时额外记录 `textureSize`，用于事后判断「资源包里的图尺寸变了」这类问题。
- 同一次采集可输出多条 `screens`（主线：进入界面 → 截图前快照）。

---

## 4. 分阶段路线图

### Phase 0 — 技术验证（Spike，不写产品代码）

目的是把四个未知数打掉，每个都有明确的通过标准。

1. ✅ **采集可行性**：已实机通过（见 8.4），日志参数与画面一致，未崩溃、无可见掉帧。
2. ⬜ **覆盖度抽检**：选 2~3 个真实模组（一个简单（如配置界面），一个复杂（含滚动列表/进度条）），
   统计有多少 blit 能还原成静态 manifest、多少是动态。产出一份「动态节点类型清单」。
   —— TFC 砧界面正是这个抽检的第一个样本，目前卡在「能不能抓到」。
3. ⬜ **反编译链路**：Vineflower 跑通一个 jar，确认能定位到 GUI 类并抽出 blit 常量（Phase 3 才需要）。
4. ✅ **渲染基线**：JavaFX `Canvas` 最近邻缩放已验证，4x 放大边缘无插值毛刺（`setImageSmoothing(false)`）。

### Phase 1 — 最小闭环（MVP）

- ✅ **Capture**：Mixin 版采集器，拦 `GuiGraphics.innerBlit`；每帧由 `TickEvent.RenderTickEvent.START`
  清空，按 F9 把**当前这一帧**写成 manifest.json（不再需要"先开始、后结束"）。
- ⬜ Capture 补全：`blitSprite`（1.20.2+ 才有）、`enableScissor`（裁剪会影响可见性）。
- ✅ **Editor（JavaFX 独立进程）**：加载 manifest + 指定资源包目录；按 `dst` 绘制、按 `src` 裁切；
  缩放（1x/2x/4x/适应，Ctrl+滚轮）、节点框/像素网格、悬停与点击选中、节点表、源纹理面板；
  资源包优先级管理、导出 PNG、manifest 与资源包目录双向热重载。
  详见 8.7。
- ✅ **Pack 切换**：同一场景用两套资源包各合成一张，左右并排 + 差异像素洋红高亮（8.8）。
- 通过标准：拿一个真实模组，Viewer 里拼出的画面与游戏内截图**逐像素一致**（排除动态内容）。

### Phase 2 — 资源包工作流

- 资源包解析：`pack.mcmeta` / `pack_format` / 多层 pack 叠加（补丁包覆盖原 mod 资源）/
  继承链（一个 pack 基于另一个 pack 时只改一张图）。
- **热重载**：监听补丁目录的文件变更，保存 PNG 即刷新（替代游戏内 F3+T）。
- **切图网格**：按纹理的 1x 网格 + 可自定义参考线，标出每个 `src` 矩形；导出为叠加在 PNG 上的参考图。
- **九宫格校验**：对 `nineSlice` 节点画出 3x3 分割线，高亮被拉伸的 1px 边，直接指出「边框被拉花了」。
- **溢出检测**：自动检查源矩形是否越界、是否跨到了相邻精灵块的区域（连通域分析）。

### Phase 3 — 无运行环境降级路径

- **静态分析器**：jar → Vineflower → 解析 `blit` 调用点 → manifest（`capturedFrom: "static-decompile"`）。
- 在 Viewer 中用不同颜色标注「静态推断」节点，明确告知不可靠。
- 支持手工编辑 manifest（画布上拖拽矩形框、手动绑定纹理），把静态分析结果补全。

### Phase 4 — 交互与状态

- 状态机：normal / hover / pressed / disabled / focused —— 采集时对同一控件多次采样，
  或在 Viewer 中手动切换状态查看对应精灵块。
- 动画纹理：`.mcmeta` 的 `animation` 帧序列，Viewer 内播放。
- 字体渲染：复用原版字体图集（`ascii.png`）做近似文字预览（可选，成本不低，优先级靠后）。

### Phase 5 — 集成与分发

- **游戏内叠加模式**：`core` 合成出整图 → `DynamicTexture` → 一次 `blit` 半透明盖在真实界面上，
  A/B 对比「我画的和实际渲染的差在哪」。这是画师最想要的功能，且只需几十行。
- 一键打包（jpackage 独立 image，自带裁剪 runtime），附使用说明。
- 多版本适配：manifest schema 带 `minecraft` 字段，采集器按版本分支。

---

## 5. 主要风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 目标模组本机跑不起来（依赖缺失/闭源/版本不符） | 链路 A 不可用 | 强制保留路线 B，并在 UI 中明示准确度等级 |
| 混淆 / 重映射（1.20.5+ 类重定位） | Agent 插桩失败 | 优先做 Mixin 版（跟随加载器官方 mappings） |
| 绘制不走 `GuiGraphics`（自绘 GL、自写 shader 的模组） | 采集缺失 | 检测并标记为不支持；这类模组极少 |
| 动态坐标（列表滚动、进度条宽度） | manifest 只能记录一帧 | 记录「参数化节点」：存表达式/依赖值，Viewer 提供滑块 |
| 缩放与 GUI scale 不一致 | 预览与游戏观感不同 | manifest 记录 `guiScale`，Viewer 提供同样的缩放预设 |
| 过度追求全自动还原 | 项目失控 | 明确工具定位是「辅助验证」而非「自动重建 UI」；允许手工修正 |

---

## 6. 暂不做的事（明确边界）

- 不做 UI 的自动重绘/美化。
- 不做模组的汉化、翻译。
- 不追求覆盖所有模组，先服务「你自己在画的那些」。
- 不做 3D 世界内 HUD 的预览（只做 Screen / 容器界面；HUD 留作后续）。

---

## 7. 待定问题

已定案：

- ~~技术栈~~ → 全 Java：`core` + Forge 薄壳 + JavaFX 编辑器。
- ~~编辑器是否内嵌进模组~~ → 否，独立进程，模组用 `ProcessBuilder` 拉起。
- ~~首个目标平台~~ → Minecraft 1.20.1 + Forge（Java 17）。

- ~~Capture 用 Mixin 还是 ASM Agent~~ → **Mixin 伴随模组**（已实机验证注入成功）。
- ~~Forge 1.20.1 的 Mixin 配置声明方式~~ → 见 8.5，结论已实测。

仍待验证：

1. manifest 是否需要版本控制友好（稳定排序、可 diff），以便 CI 里做 UI 回归测试？
2. ~~是否需要支持「打开界面时自动抓取」（遍历所有 Screen）还是只支持手动打点？~~
   → 手动打点即可：热键现在已经能在界面打开时触发（`InputEvent.Key`）。
3. 屏幕里同时存在多个 `GuiGraphics` 实例时（模组自建），z 序是否需要按创建顺序二次排序？

---

## 8. 开发环境与网络落地（2026-11-14 实测）

### 8.1 环境

| 项 | 状态 |
|---|---|
| JDK | `C:\Program Files\Java\jdk-17`（1.20.1 必需），另有 21 / 25 |
| Gradle | **未在 PATH**；`~/.gradle/wrapper/dists` 里的 8.12.1 只下了 2.22 MB（中断残留），不可用 |
| 实际使用 | Gradle **8.1.1**，腾讯镜像下载后解压到 `%TEMP%\gradle-dist\gradle-8.1.1` |
| 磁盘 | D 盘剩余约 195 GB |

### 8.2 国内网络实测结论

| 源 | 实测 | 结论 |
|---|---|---|
| `services.gradle.org` | 目录页 200，下载 **SSL error 35** | **不可用于下载发行包** |
| `mirrors.cloud.tencent.com/gradle/` | **6.5 MB/s** | 首选；124 MB 约 20 秒下完 |
| `mirrors.huaweicloud.com/gradle/` | 6.7 MB/s | 备选 |
| `maven.minecraftforge.net` | 200，约 356 KB/s | 可用，偏慢 |
| `piston-meta.mojang.com` | 200 | 可用，版本清单靠它 |
| `piston-data.mojang.com` | **超时，返回 000** | **不可达**，客户端/服务端 jar 无法直连 |
| `bmclapi2.bangbang93.com/version/` | 大文件 6.5 MB/s | 用于补 Mojang jar |
| `resources.download.minecraft.net` | 200 | 可下，但全量 **624 MB** |

### 8.3 已跑通

```
Gradle 8.1.1 + JDK 17 + ForgeGradle 6 + Forge 1.20.1-47.2.0

:core:build        → core-0.1.0.jar
:forge:compileJava → 7 个 class
:forge:build       → viewmyui-forge-1.20.1-0.1.0.jar（含 mods.toml / mixins.json / lang）
```

绕过的两个坑：

1. **Gradle 发行包**：官方源 SSL 失败 → 腾讯镜像下载 8.1.1。
2. **server.jar**：ForgeGradle 从 `piston-data.mojang.com` 拉取，国内不通 →
   用 BMCLAPI 预置到
   `~/.gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/server.jar`，
   并用版本清单的 SHA1 校验（`84194a2f286ef7c14ed7ce0090dba59902951553`）。
   ForgeGradle 检测到文件已存在即跳过下载。

### 8.4 已实机验证（Phase 0 采集链路打通）

**正式环境（用户真实客户端：Forge 47.4.18 + 13 模组，含 Sinytra Connector）同样通过**：

```
[viewmyui] 捕获到首个 blit: minecraft:textures/misc/vignette.png dst=(0,0,683,353)
[viewmyui] 已写出 26922 个节点 -> <gameDir>/viewmyui/manifest.json
```

说明 jar manifest 的 `MixinConfigs` 路径生效，且无 refmap 的双名注入在 SRG 环境下也能命中。

开发环境（`forgeclientuserdev`）：

`runClient` 需要 624 MB 资源，已通过 BMCLAPI / 腾讯镜像补齐，客户端可正常启动。

```
[mixin/] Mixing GuiGraphicsMixin from viewmyui.mixins.json into net.minecraft.client.gui.GuiGraphics
[viewmyui] 捕获到首个 blit: minecraft:textures/gui/options_background.png dst=(0,0,427,240)
[viewmyui] 已写出 1224 个节点 -> forge/run/viewmyui/manifest.json
```

- Mixin 注入成功，`innerBlit` 参数与画面一致（`src` 已从归一化 UV 折算成纹理像素）。
- `manifest.json` 约 143 KB / 1224 个 blit 节点，纹理路径、`src`/`dst` 均正确。

### 8.5 Mixin 配置注册的坑（重要）

实测结论：**Forge 1.20.1 的 FML 完全没有 mixin 支持代码**（`fmlcore`/`fmlloader`/`javafmllanguage`
里连 `"mixin"` 字符串都搜不到），因此：

| 方式 | 结论 |
|---|---|
| `mods.toml` 的 `[[mixins]]` | **不支持**，会被 TOML 解析器静默忽略 |
| 系统属性 `-Dmixin.configs=...` | **不存在**（Mixin 0.8.5 只认 `--mixin` / `--mixin.config`） |
| 命令行参数 `--mixin <config.json>` | **dev 环境唯一可行**（Mixin 的 `CommandLineOptions` 扫描 `sun.java.command`） |
| jar manifest 的 `MixinConfigs` | **正式环境唯一可行**（无 manifest 的 dev 目录模组读不到） |

所以 `forge/build.gradle` 里两处都要有：

```groovy
runs { client { args '--mixin', 'viewmyui.mixins.json' } }   // dev
jar { manifest { attributes(['MixinConfigs': 'viewmyui.mixins.json']) } }  // 正式
```

另外 **不引入 MixinGradle、不用 refmap**：1.17+ 的类名不做混淆，只有成员名会变成 SRG，
因此 `@Inject` 里同时列出官方名与 SRG 名即可（`innerBlit` / `m_280444_`），
省掉注解处理器与 `repo.spongepowered.org` 依赖。SRG 名可从 `forge/build/reobfJar/mappings.tsrg` 查。

> 网络策略：任何一步出现长时间无进展或多次失败，**立即停止并交由用户手动下载**，
> 不要反复重试。可用镜像：Gradle 用腾讯/华为，Mojang jar 用 BMCLAPI。

### 8.6 Phase 1 编辑器 MVP 已跑通（2026-11-14）

**采集粒度修正（关键）**：真实客户端首份 manifest 有 26922 个节点，是同一帧被反复累加约 6700 次。
最初改为在 `GuiGraphics` 构造器注入 `frameStart()` 清空当前帧（dev 复测 1224 → 16 个节点），
但 1.20.1 的 TFC 等模组会在渲染中途再 `new GuiGraphics`，那样会把之前画的内容整段清掉，
导致只留下 HUD、丢界面。**最终方案**：改用 Forge 的 `TickEvent.RenderTickEvent.START`
作为帧边界，并取消"开始/结束采集"——始终记录最后一帧，按 F9 立刻落盘。

**热键修正（关键）**：`KeyMapping.consumeClick()` 只在没有打开 `Screen` 时才会累积点击，
而我们要抓的恰恰是打开着的界面——所以最初 F9 只能抓到 HUD/物品栏。
不需要（也不该用）全局热键：Forge 的 `InputEvent.Key` 是原始按键事件，不受界面拦截，
在 `keyPress` 里对所有按键都会触发。现在监听它、只取 `action == GLFW_PRESS`，
再延迟到下一个 `ClientTickEvent.END` 执行，保证落盘的是完整的一帧。
键位仍取自 `KeyMapping`，玩家在「选项 → 控制 → 按键绑定」里改绑即生效（`KeyBindsScreen` 中跳过，避免改键时误触发）。
OS 级全局热键不可取：游戏失焦时也会触发，且与 MC 自己的键位系统脱节。

同时补全 manifest 元信息：`meta.guiScale`、`screens[0].width/height/title`，
编辑器因此能按正确的逻辑分辨率布局，不必靠节点包围盒兜底。

**编辑器（`editor` 模块，JavaFX 17.0.13 classpath 模式）**：

```
java -cp ... com.viewmyui.editor.EditorLauncher            # GUI
EditorLauncher --export <manifest> --out <png> --packs a;b  # 无头导出
```

- 工具栏：打开 manifest…／添加资源包…／缩放 1x·2x·4x／网格／资源包列表
- 中央 `ScrollPane(Pane(Canvas))`，全量自绘；`setImageSmoothing(false)` 保证 4x 放大无插值毛刺
- 悬停高亮 `z` 最大的命中节点，状态栏显示 `节点数 · 缺失纹理数 · 分辨率 · 缩放 · 当前节点 src/dst/z`
- `WatchService` 监听 manifest 目录 → 游戏里 F9 重新采集后编辑器自动刷新
- 像素数据用 `PixelFormat.getIntArgbInstance()` 手转 `WritableImage`，避免引入 `javafx.swing` 模块

**验证结果**：用真实客户端 jar 里解出的 4 张原版 PNG 作资源包，导出 427×240 的标题界面合成图，
徽标 / JAVA EDITION / 4 个按钮 / 右侧 2 个图标位置全部正确，缺失纹理 0 个。

产物：

| 文件 | 说明 |
|---|---|
| `dist/viewmyui-forge-1.20.1-0.1.0.jar` | 采集模组（含 `MixinConfigs` / `mods.toml` / `pack.mcmeta`） |
| `editor/build/install/editor/bin/editor.bat` | 编辑器可执行入口（`installDist` 产物） |
| `dist/preview.png` | 无头导出的合成结果 |
| `dist/editor-screenshot.png` | 编辑器 GUI 运行截图 |

### 8.7 编辑器功能完成（2026-11-14）

主界面：左侧画布，右侧上下可拖动的「源纹理 / 节点」双面板，底部状态栏。

| 功能 | 说明 |
|---|---|
| 画布 | 最近邻放大；棋盘底用于看透明；红框 = 选中节点，黄框 = 悬停，橙框 = 缺纹理 |
| 源纹理面板 | 显示整张精灵图（放不下按比例缩小，永不裁切）；红框 = 当前 `src`，蓝框 = 同一张图上其它被引用的精灵 |
| 节点表 | `TreeTableView`，按纹理分组（组行加粗、缺纹理标橙）；点击与画布双向联动，点画布自动展开分组 |
| 资源包 | 对话框内增删 + 上下调优先级（靠上覆盖靠下），显示每个纹理实际来自哪个包 |
| 缩放 | 0.25x–16x 滑条 + 适应；Ctrl+滚轮 0.5 步进 |
| 网格 | 「节点框」画出每个 `dst`；「像素网格」在 ≥3x 时画出 GUI 像素格；「九宫格」校验拉伸与越界 |
| 导出 | `MenuButton`：合成图 PNG（Ctrl+E）、切图参考图 |
| 热重载 | `WatchService` 递归监听 manifest 与所有资源包目录，保存 PNG 后 250ms 自动刷新 |
| 快捷键 | Ctrl+O 打开、Ctrl+E 导出、F5 重载；支持把 manifest/资源包文件夹直接拖进窗口 |

F10 拉起编辑器不再必须配 `-Dviewmyui.editor`。启动器直接用**游戏进程自己的 JVM**
（`java.home`）跑 `java -cp <editor>/lib/* com.viewmyui.editor.EditorLauncher`，
不依赖 PATH 里的 java，也不依赖 .bat 找 JAVA_HOME，因此 PCL / 官方启动器 / 整合包都能用。
输出重定向到 `viewmyui/editor.log`，起不来时先看它。

查找顺序：

1. `-Dviewmyui.editor=<路径>`（.exe / .bat / 含 `lib/` 的目录）
2. 环境变量 `VIEWMYUI_EDITOR`
3. `<gameDir>/viewmyui/editor`
4. `<gameDir>/viewmyui/viewmyui-editor`

启动时若 `<gameDir>/viewmyui/manifest.json` 存在会自动打开它，并把窗口置前一次。

**实测**（真实客户端采集的 in-game HUD manifest，4 个节点）：

- 合成图：vignette 全屏 + 物品栏 9 格 + 血量图标，位置与游戏一致
- 选中 `widgets.png` 第 1 条 → 画布上物品栏被红框圈住，纹理面板显示 256×256 原图并把
  `src=(0,0,182,22)` 框出来，同图的 `src=(0,22,24,22)` 用蓝框标出
- 状态栏：`4 个节点 · 缺失纹理 0 个 · 683x353 · 1x`

### 8.8 原版 / 补丁包并排对比（2026-11-14）

**core**：`ImageDiff.compare(left, right)` 逐像素比较 ARGB（含 alpha），产出差异遮罩与统计；
`ImageDiff.sideBySide(...)` 拼成左右两栏（深色分隔、顶部对齐，尺寸不一致时多出的区域按透明算差异）。

**editor**：

- 工具栏「并排对比…」→ 对话框分别选 A（基准 / 原版）与 B（对比 / 补丁包）两套资源包，默认都取当前包列表。
- 独立对比窗口：1x/2x/4x（Ctrl+滚轮 1–8）、差异高亮开关；
  状态栏显示「差异 N/M 像素（%）· A 缺纹理数 · B 缺纹理数」。
- 洋红 = 两边像素不一致，同时叠在左右两栏上，便于定位是哪一块被补丁改了。

**CLI（无界面 / CI）**：

```
EditorLauncher --compare <manifest> --packs-a <dir;dir> --packs-b <dir;dir> --out <png>
```

**实测**：16×16 单节点 manifest，B 包只改 1 个像素 →
`差异 1 / 256 像素 (0.391%)`，导出 40×16（16+8+16）PNG，左右同一位置均为 `(255,0,255)`，分隔条深色。
GUI 已做启动冒烟（载入 manifest + 包列表正常）。

### 8.9 默认材质快照与工作区记忆（2026-11-14）

**为什么不做"跨进程共享游戏纹理"**：编辑器是独立 JVM，游戏里的纹理在 GPU / `TextureManager` 里，
两个进程无法共享 GL 上下文。可选方案只有两种：让编辑器自己读资源包，或者由模组把需要的图导出成普通文件。
后者不依赖包格式（目录 / zip / jar）、不需要猜包优先级，且拿到的就是**当前这一帧实际渲染用到的图**。

**实现（模组侧）**：

- F9 落盘 manifest 后，把 `textures` 集合里的每个 `ResourceLocation` 通过 `ResourceManager` 原样复制到
  `<gameDir>/viewmyui/default/assets/<namespace>/<path>`，结构就是一个标准资源包。
- **内存开销与资源总量无关**：只处理这一帧被引用的那几十张图；复制用 `Files.copy` 流式字节转发，
  不 `ImageIO.read`、不解码。游戏内查纹理尺寸也改成只读 PNG 的 IHDR 头（24 字节），
  不再为拿宽高把每张图完整解码一遍（非 PNG 才退回解码）。
- 编辑器侧零改动即可当作普通包使用；重复 F9 会覆盖同名文件，保持快照新鲜。

**实现（编辑器侧）**：

- 配置写在 manifest 同目录：`<gameDir>/viewmyui/editor-config.json`，每实例独立。
- 内容只有路径列表：`packs`（当前） + `workspaces`（最近 12 组，去重置顶，带 `usedAt`）。
- 启动顺序：命令行 `--packs` > 配置里的 `packs` > `default/` 快照；无论如何最后都会把 `default/`
  补为最低优先级包（除非用户手动删掉）。
- 工具栏新增「工作区」下拉，选中即切换包列表；任何改动（对话框、拖拽、下拉）都会立即落盘。
- `WatchService` 忽略 `editor-config.json` 自身写入，避免切换工作区时多一次无意义重载。

**验证**（临时目录 + 合成图）：

- 无配置启动：自动 `packs=[default]`，生成配置文件；用该快照 `--export` 出的图像素与原始 PNG 一致。
- 有配置（`packC`）启动：`packs=[packC, default]`，`default` 被追加为最低优先级；
  配置重写后 `workspaces` 首项为当前组合，旧组合保留。

### 8.10 编辑器精修与缩放采样（2026-11-14）

**自由缩放**：1x/2x/4x 按钮换成 0.25–16 的滑条，Ctrl+滚轮以 0.5 步进；「适应」按 0.25 取整，
避免小数缩放把像素拉花。状态栏与滑条旁同步显示精确倍率。

**树状节点列表**：`TableView` → `TreeTableView`，按纹理分组，组行加粗、缺纹理标橙；
画布点选会自动展开所属分组并滚到对应行，分组展开状态在重载后保留。
（JavaFX 自带 `TreeTableView`，不需要第三方组件。）

**九宫格校验**：工具栏开关 + 边框像素 `Spinner`（默认 4）。对发生缩放的节点画 3×3 分割线，
中心区淡橙着色表示被拉伸；源矩形超出纹理边界的用红框标出——这是补丁最常见的采样越界。
角块尺寸按 `border × dst / src` 映射到目标坐标，和九宫格的实际拉伸规则一致。

**切图参考图导出**：`ReferenceExport` 按纹理分组，在 1:1 原图上叠加所有 `src` 矩形的半透明填充与描边
（8 色轮换），文件名 `namespace__path.png`。GUI 走「导出 → 切图参考图…」，
CLI 为 `--references <manifest> --packs <dirs> --out <dir>`。
画师可以直接在标注图上改，不会串到相邻控件。

**缩放采样（按群峦源码对齐）**：先确认了 TFC 的做法——`AnvilStepButton.renderWidget` 与
`AnvilScreen.renderBg` 都只是普通 `GuiGraphics.blit(...)`（32×32 精灵图直接画到 16×16 / 10×10），
**没有任何自定义缩放算法**。它依旧清晰的原因是 Minecraft 的 GUI 按 `guiScale` 的物理分辨率光栅化：
1 个逻辑像素 = `guiScale` 个屏幕像素，GUI 2x 时 16 逻辑像素的按钮实际占 32×32 物理像素，
和 32×32 源区域正好 1:1，一个 texel 都不丢。

因此 `Compositor.ScaleMode` 现在有三档，「缩放采样」下拉里对应：

| 选项 | 行为 |
|---|---|
| **无（游戏 1:1）**（默认） | 画布 = `逻辑尺寸 × guiScale`（`guiScale` 来自 manifest `meta`，缺失按 1），`dst` 同比放大。群峦砧界面那种 32×32→16 逻辑像素在 GUI 2x 下自然回到 1:1，和游戏逐像素一致 |
| **直接（最近邻）** | 按逻辑像素合成（倍率 1），用 GL 最近邻丢像素，等价于"游戏在 GUI 1x 下看到的样子" |
| **平滑（双线性）** | 按逻辑像素合成 + 双线性插值，留给确实做抗锯齿的模组对照 |

三档共用的采样细节：

- 残留缩放（「无」下物理上仍非 1:1，或另外两档的全部缩放）按 GL 规则手写最近邻：
  `sx = floor((dx + 0.5) * srcW / dstW)`。刻意不用 `AffineTransformOp(TYPE_NEAREST_NEIGHBOR)`
  ——32→10 / 32→20 这类非整数倍下它会在某些边界差一个 texel（实测第 3 个目标像素取 7，GL 取 8）。
- 画布与节点框、九宫格、点击拾取都按当前档位的倍率换算，切换档位时整张图重合成。

注意：TFC 的规则图标还叠了 `RenderSystem.setShaderColor` 的绿/红 tint，manifest 尚未记录颜色，
编辑器里看到的是未着色版本（不影响几何与采样）。

**F10 反馈**：`EditorLauncher.launch()` 改为返回是否成功，聊天栏先发「正在打开编辑器…（首次启动 1~4 秒）」；
已在运行提示「编辑器已在运行」，启动失败提示看 `editor.log`，避免玩家以为没按到而连点。

**验证**（无界面）：

- 2×2 四色纹理放大到 8×8：`(0,0)/(3,3)` 纯红、`(4,0)` 纯绿、`(0,4)` 纯蓝、`(7,7)` 纯白，
  分界正好在 4，无过渡色 → 最近邻路径生效。
- GUI 2x + 32×32 → 16 逻辑像素（TFC 砧按钮同款）：导出 128×96，`dst` 区域 32×32 与源纹理
  **逐像素 0 差异**，区域外保持透明 → 物理层 1:1，和游戏里看到的一致。
- GUI 1x + 32×32 → 16：取到的正是奇数 texel（1,3,5…31），与 GL 的 texel 中心规则 0 差异。
- GUI 2x + 32×32 → 10 逻辑像素（物理 20）：逐像素与 `floor((dx+0.5)*32/20)` 期望 0 差异，
  说明非整数倍下也没有 Java2D 的边界偏差。
- 16×16 单节点导出参考图：`test__textures_gui_x.png`，角落为描边色 `(79,195,247)`，
  内部为 40 alpha 叠色 → 标注正确。
- GUI 冒烟：新工具栏（两行）+ `TreeTableView` + 滑条启动无异常。

### 8.11 编辑器内嵌进模组 jar（2026-11-14）

**目标**：不再要求玩家/开发者手动把 `installDist` 目录放到 `viewmyui/editor`，模组 jar 自带编辑器。

**构建侧**（`editor/build.gradle` + `forge/build.gradle`）：

- `:editor:editorBundle`（`Sync`）：把 `installDist/lib` 里的 core / editor / gson 与
  `javafxAll` 配置里的四平台 JavaFX 汇总到 `editor/build/editor-bundle/lib/`，
  同时生成 `version.txt`（版本 + 内容 SHA-256 前 12 位）与 `index.txt`（文件清单）。
- `:forge:jar` 把 `editor/build/editor-bundle` 原样打进 `META-INF/viewmyui/editor/`。
  用普通资源目录而非 JarJar——JarJar 只服务于 FML 加载的库模组，启动外部 JVM 用不上。
- JavaFX 平台默认按构建机自动选择（`win` / `linux` / `mac` / `mac-aarch64`），
  可用 `-PjavafxPlatform=` 覆盖；内嵌包则一次带齐四种。

**运行侧**（`forge/.../EditorLauncher.java`）：

- 查找顺序变成：`-Dviewmyui.editor` → `VIEWMYUI_EDITOR` → `<gameDir>/viewmyui/editor` →
  `<gameDir>/viewmyui/viewmyui-editor` → **jar 内嵌**（兜底）。
- 内嵌解压只走 `getResourceAsStream("META-INF/viewmyui/editor/...")`，不猜模组 jar 的磁盘路径
  （Forge 可能把它挂在 union filesystem 上）；先写临时目录再整体改名，崩溃或双开都不会留半个 `lib/`。
- 版本目录带内容哈希，升级模组自动换新目录，不会覆盖正在运行的 jar。
- classpath 按当前系统只挑对应平台的 JavaFX（四套 native 同名类不重复上阵），并过滤几百字节的空 jar。

**体积**：模组 jar 从 18 KB 变成 **约 29 MB**，其中 JavaFX 四平台约 32 MB（未压缩）。

**平台矩阵**：Windows x64 / Linux x64 / macOS x64 / macOS arm64。
JavaFX 17/21 在 Maven Central 上不发布 `linux-aarch64` 与 `win-aarch64`，
这两类机器仍可用外置编辑器（`-Dviewmyui.editor=`）或自行替换 JavaFX 运行库。

**验证**：从 `editor-bundle/lib` 按运行时的同一套过滤规则拼 classpath（6 个 jar）启动
`--export`，GUI 2x / 32×32→16 用例依旧逐像素 0 差异；最终 `reobfJar` 产物中
`META-INF/viewmyui/editor/` 的 15 个 jar + `index.txt` + `version.txt` 完整保留。
