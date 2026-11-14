package com.viewmyui.editor;

import com.viewmyui.core.manifest.BlitNode;
import com.viewmyui.core.manifest.ManifestReader;
import com.viewmyui.core.manifest.ScreenScene;
import com.viewmyui.core.manifest.UiManifest;
import com.viewmyui.core.pack.PackResolver;
import com.viewmyui.core.render.Compositor;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 编辑器：读 manifest + 资源包目录 → 合成 → 显示，并把"每个节点用了精灵图的哪一块"画出来。
 *
 * <p>合成逻辑全在 {@code core}，这里只做显示与交互。
 */
public final class EditorApp extends Application {

    private static final int MAX_WATCH_DIRS = 512;

    private final Canvas canvas = new Canvas(1, 1);
    private final TextureSheet sheet = new TextureSheet();
    private final Label sheetInfo = new Label("—");
    private final Label status = new Label("用「打开 manifest」载入采集结果");
    private final Label packsLabel = new Label();
    private final ComboBox<ScreenItem> screenBox = new ComboBox<>();
    private final ComboBox<WorkspaceItem> workspaceBox = new ComboBox<>();
    private final ComboBox<ScaleModeItem> scaleBox = new ComboBox<>();
    private final TreeTableView<NodeRow> table = new TreeTableView<>();
    private final CheckBox gridBox = new CheckBox("节点框");
    private final CheckBox pixelBox = new CheckBox("像素网格");
    private final CheckBox nineSliceBox = new CheckBox("九宫格");
    private final Spinner<Integer> sliceSpinner = new Spinner<>(1, 32, 4);
    private final Slider zoomSlider = new Slider(0.25, 16, 1);
    private final Label zoomLabel = new Label("1.00x");

    private final List<Path> packs = new ArrayList<>();
    private final Map<String, BufferedImage> textureCache = new HashMap<>();

    private Stage stage;
    private ScrollPane scrollPane;
    private Path manifestPath;
    private EditorConfig config;
    private UiManifest manifest;
    private ScreenScene scene;
    private BufferedImage composite;
    private WritableImage fxImage;
    private PackResolver resolver = new PackResolver(List.of());

    private BlitNode hovered;
    private BlitNode selected;
    private double zoom = 1;
    private int screenIndex;
    private boolean updatingScreens;
    private boolean updatingTable;
    private boolean updatingWorkspaces;
    private boolean updatingZoom;
    private boolean initialFitDone;
    private int missingCount;
    private List<String> missingTextures = List.of();
    private Set<String> missingSet = Set.of();
    private final Map<BlitNode, TreeItem<NodeRow>> rowItems = new HashMap<>();
    private final Set<String> expandedGroups = new HashSet<>();

    private Thread watchThread;
    private volatile boolean watching;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;

        BorderPane root = new BorderPane();
        root.setTop(buildToolbar());
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        // 小屏幕上不要把窗口撑出屏幕外，否则底部状态栏会被任务栏挡住
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double windowWidth = Math.min(1180, screen.getWidth() * 0.94);
        double windowHeight = Math.min(760, screen.getHeight() * 0.92);
        Scene fxScene = new Scene(root, windowWidth, windowHeight);
        installShortcuts(fxScene);
        installDragAndDrop(root);

        primaryStage.setScene(fxScene);
        primaryStage.setTitle("View My UI — Minecraft UI 补丁预览");
        // 从游戏里拉起来时很容易被游戏窗口挡住，先置顶一下再取消
        primaryStage.setAlwaysOnTop(true);
        primaryStage.show();
        primaryStage.toFront();
        primaryStage.requestFocus();
        Platform.runLater(() -> primaryStage.setAlwaysOnTop(false));

        List<String> args = getParameters().getRaw();
        Path initialManifest = null;
        for (int i = 0; i < args.size(); i++) {
            if ("--packs".equals(args.get(i)) && i + 1 < args.size()) {
                for (String dir : args.get(++i).split(";")) {
                    if (!dir.isBlank()) {
                        packs.add(Path.of(dir));
                    }
                }
            } else if (!args.get(i).startsWith("--")) {
                initialManifest = Path.of(args.get(i));
            }
        }
        refreshPacksLabel();
        if (initialManifest != null) {
            load(initialManifest);
        }
        System.out.println("[viewmyui-editor] manifest=" + initialManifest + " packs=" + packs);
    }

    // ---------------------------------------------------------------- 界面

    private VBox buildToolbar() {
        Button open = new Button("打开 manifest…");
        open.setOnAction(e -> chooseManifest());

        Button packButton = new Button("资源包…");
        packButton.setOnAction(e -> editPacks());

        Button compare = new Button("并排对比…");
        compare.setOnAction(e -> comparePacks());

        MenuItem compositeItem = new MenuItem("合成图 PNG…");
        compositeItem.setOnAction(e -> exportPng());
        MenuItem referenceItem = new MenuItem("切图参考图…");
        referenceItem.setOnAction(e -> exportReference());
        MenuButton export = new MenuButton("导出", null, compositeItem, referenceItem);

        Button fit = new Button("适应");
        fit.setOnAction(e -> fitToWindow());

        zoomSlider.setPrefWidth(150);
        zoomSlider.setBlockIncrement(0.25);
        zoomSlider.setMajorTickUnit(1);
        zoomSlider.setMinorTickCount(3);
        zoomSlider.valueProperty().addListener((o, old, value) -> {
            if (updatingZoom) {
                return;
            }
            setZoom(value.doubleValue());
            redraw();
        });
        zoomLabel.setMinWidth(48);
        zoomLabel.setTooltip(new Tooltip("1.00x = 游戏物理像素 1:1（合成图已按 GUI 缩放放大）"));

        HBox primary = new HBox(8, open, packButton, compare, export, new Separator(),
                new Label("缩放"), zoomSlider, zoomLabel, fit, new Separator(), gridBox, pixelBox);
        primary.setAlignment(Pos.CENTER_LEFT);

        workspaceBox.setPrefWidth(230);
        workspaceBox.setPromptText("历史工作区");
        workspaceBox.setTooltip(new Tooltip("记住用过的资源包组合，选中即切换"));
        workspaceBox.valueProperty().addListener((o, old, value) -> {
            if (updatingWorkspaces || value == null || value.packs().equals(packs)) {
                return;
            }
            packs.clear();
            packs.addAll(value.packs());
            refreshPacksLabel();
            persistPacks();
            reload();
            restartWatchers();
        });

        // 「无」= 游戏物理分辨率 1:1；另外两个按逻辑像素合成，用于对照"被压缩后"的样子
        scaleBox.getItems().setAll(
                new ScaleModeItem(Compositor.ScaleMode.NONE, "无（游戏 1:1）"),
                new ScaleModeItem(Compositor.ScaleMode.NEAREST, "直接（最近邻）"),
                new ScaleModeItem(Compositor.ScaleMode.SMOOTH, "平滑（双线性）"));
        scaleBox.getSelectionModel().selectFirst();
        scaleBox.setPrefWidth(126);
        scaleBox.setTooltip(new Tooltip("「无」按 GUI 缩放的物理分辨率合成，和游戏 1:1；"
                + "最近邻/双线性按逻辑像素合成，用于对照"));

        scaleBox.valueProperty().addListener((o, old, value) -> {
            if (value != null) {
                rerender();
            }
        });

        nineSliceBox.setTooltip(new Tooltip("对发生缩放的节点画 3×3 分割线，标出被拉伸的边与越界源矩形"));
        nineSliceBox.selectedProperty().addListener((o, old, value) -> redraw());
        sliceSpinner.setPrefWidth(70);
        sliceSpinner.setEditable(true);
        sliceSpinner.valueProperty().addListener((o, old, value) -> redraw());

        screenBox.setPrefWidth(190);
        screenBox.valueProperty().addListener((o, old, value) -> {
            if (updatingScreens || value == null) {
                return;
            }
            screenIndex = value.index();
            applyScene();
        });

        HBox secondary = new HBox(8, new Label("工作区"), workspaceBox, new Separator(),
                new Label("缩放采样"), scaleBox, new Separator(),
                nineSliceBox, sliceSpinner, new Separator(), screenBox);
        secondary.setAlignment(Pos.CENTER_LEFT);

        VBox toolbar = new VBox(6, primary, secondary);
        toolbar.setPadding(new Insets(8));

        gridBox.selectedProperty().addListener((o, old, value) -> redraw());
        pixelBox.selectedProperty().addListener((o, old, value) -> redraw());

        packsLabel.setStyle("-fx-text-fill: #8a8a92;");
        packsLabel.setMaxWidth(460);
        return toolbar;
    }

    private void setZoom(double value) {
        double clamped = Math.max(0.25, Math.min(16, value));
        zoom = clamped;
        if (Math.abs(zoomSlider.getValue() - clamped) > 1e-6) {
            updatingZoom = true;
            zoomSlider.setValue(clamped);
            updatingZoom = false;
        }
        zoomLabel.setText(String.format("%.2fx", zoom));
    }

    private SplitPane buildCenter() {
        scrollPane = new ScrollPane(new Pane(canvas));
        scrollPane.setPannable(true);
        scrollPane.setFitToWidth(false);
        scrollPane.setFitToHeight(false);

        canvas.setOnMouseMoved(e -> hover(e.getX(), e.getY()));
        canvas.setOnMouseExited(e -> {
            hovered = null;
            redraw();
            updateSheet();
        });
        canvas.setOnMouseClicked(e -> {
            BlitNode hit = pick(e.getX(), e.getY());
            if (hit == null) {
                selected = null;
                updatingTable = true;
                table.getSelectionModel().clearSelection();
                updatingTable = false;
            } else {
                selected = hit;
                selectRow(hit);
            }
            redraw();
            updateSheet();
        });
        canvas.setOnScroll(e -> {
            if (!e.isControlDown()) {
                return;
            }
            setZoom(zoom + (e.getDeltaY() > 0 ? 0.5 : -0.5));
            redraw();
            e.consume();
        });

        SplitPane split = new SplitPane(scrollPane, buildSidePanel());
        split.setDividerPositions(0.68);
        return split;
    }

    private VBox buildSidePanel() {
        Label textureTitle = new Label("源纹理（红框 = 当前元素，蓝框 = 同图其它引用）");
        textureTitle.setStyle("-fx-text-fill: #8a8a92; -fx-font-size: 11px;");

        sheet.setPrefHeight(250);
        sheet.setMinHeight(120);

        sheetInfo.setStyle("-fx-text-fill: #c8c8ce; -fx-font-size: 11px;");
        sheetInfo.setWrapText(true);

        TreeTableColumn<NodeRow, String> indexColumn = new TreeTableColumn<>("#");
        indexColumn.setCellValueFactory(data -> {
            NodeRow row = data.getValue().getValue();
            return new ReadOnlyStringWrapper(row == null || row.group()
                    ? "" : String.valueOf(row.index()));
        });
        indexColumn.setPrefWidth(38);
        indexColumn.setMinWidth(30);
        indexColumn.setMaxWidth(52);

        TreeTableColumn<NodeRow, String> textureColumn = new TreeTableColumn<>("纹理");
        textureColumn.setCellValueFactory(data -> {
            NodeRow row = data.getValue().getValue();
            return new ReadOnlyStringWrapper(row == null || !row.group()
                    ? "" : shortName(row.texture()));
        });

        TreeTableColumn<NodeRow, String> srcColumn = new TreeTableColumn<>("src");
        srcColumn.setCellValueFactory(data -> {
            NodeRow row = data.getValue().getValue();
            return new ReadOnlyStringWrapper(row == null || row.node() == null ? ""
                    : rect(row.node().srcX(), row.node().srcY(),
                    row.node().srcW(), row.node().srcH()));
        });
        srcColumn.setPrefWidth(96);

        TreeTableColumn<NodeRow, String> dstColumn = new TreeTableColumn<>("dst");
        dstColumn.setCellValueFactory(data -> {
            NodeRow row = data.getValue().getValue();
            return new ReadOnlyStringWrapper(row == null || row.node() == null ? ""
                    : rect(row.node().dstX(), row.node().dstY(),
                    row.node().dstW(), row.node().dstH()));
        });
        dstColumn.setPrefWidth(96);

        table.getColumns().setAll(List.of(indexColumn, textureColumn, srcColumn, dstColumn));
        table.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY);
        table.setShowRoot(false);
        table.setPlaceholder(new Label("没有节点"));
        table.setRowFactory(view -> new TreeTableRow<>() {
            @Override
            protected void updateItem(NodeRow row, boolean empty) {
                super.updateItem(row, empty);
                // 缺纹理的节点用橙色标出来，一眼看出是哪个包没带图
                if (empty || row == null) {
                    setStyle("");
                } else if (row.group()) {
                    setStyle("-fx-font-weight: bold;"
                            + (missingSet.contains(row.texture()) ? "-fx-text-fill: #e0913c;" : ""));
                } else {
                    setStyle(missingSet.contains(row.texture()) ? "-fx-text-fill: #e0913c;" : "");
                }
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (updatingTable || item == null) {
                return;
            }
            NodeRow row = item.getValue();
            if (row == null || row.group()) {
                // 选中分组只做展开/收起，不干扰画布上的当前元素
                return;
            }
            selected = row.node();
            redraw();
            updateSheet();
        });

        VBox textureBox = new VBox(6, textureTitle, sheet, sheetInfo);
        VBox.setVgrow(sheet, Priority.ALWAYS);

        VBox nodeBox = new VBox(6, new Label("节点（按纹理分组）"), table);
        VBox.setVgrow(table, Priority.ALWAYS);

        // 上下可拖动分隔：精灵图小的时候把空间让给节点列表，反之亦然
        SplitPane vertical = new SplitPane(textureBox, nodeBox);
        vertical.setOrientation(javafx.geometry.Orientation.VERTICAL);
        vertical.setDividerPositions(0.46);

        VBox panel = new VBox(vertical);
        VBox.setVgrow(vertical, Priority.ALWAYS);
        panel.setPadding(new Insets(10));
        panel.setPrefWidth(380);
        return panel;
    }

    private HBox buildStatusBar() {
        status.setStyle("-fx-text-fill: #b8b8be;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, status, spacer, packsLabel);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void installShortcuts(Scene fxScene) {
        fxScene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN), this::chooseManifest);
        fxScene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.E, KeyCombination.CONTROL_DOWN), this::exportPng);
        fxScene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F5), this::reload);
    }

    private void installDragAndDrop(BorderPane root) {
        root.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        root.setOnDragDropped(e -> {
            boolean handled = false;
            boolean packsChanged = false;
            for (File file : e.getDragboard().getFiles()) {
                Path path = file.toPath();
                if (Files.isDirectory(path) && Files.isDirectory(path.resolve("assets"))) {
                    packs.add(0, path);
                    packsChanged = true;
                    handled = true;
                } else if (file.getName().endsWith(".json")) {
                    load(path);
                    handled = true;
                }
            }
            if (packsChanged) {
                refreshPacksLabel();
                persistPacks();
                reload();
                restartWatchers();
            }
            e.setDropCompleted(handled);
            e.consume();
        });
    }

    // ---------------------------------------------------------------- 载入与合成

    private void chooseManifest() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 manifest.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("manifest", "*.json"));
        Path initial = manifestPath != null ? manifestPath.getParent() : Path.of(System.getProperty("user.home"));
        if (initial != null && Files.isDirectory(initial)) {
            chooser.setInitialDirectory(initial.toFile());
        }
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            load(file.toPath());
        }
    }

    private void editPacks() {
        List<Path> updated = PackDialog.show(stage, packs);
        if (updated.size() == packs.size() && updated.equals(packs)) {
            return;
        }
        packs.clear();
        packs.addAll(updated);
        refreshPacksLabel();
        persistPacks();
        reload();
        restartWatchers();
    }

    /**
     * 同一个 scene 用两套资源包各合成一张，左右并排看补丁改了哪些像素。
     */
    private void comparePacks() {
        if (scene == null) {
            status.setText("先载入 manifest 再对比");
            return;
        }
        CompareDialog.Choice choice = CompareDialog.show(stage, packs);
        if (choice == null) {
            return;
        }
        try {
            CompareWindow.open(stage, scene, choice.left(), choice.right(), choice.highlight());
        } catch (IOException e) {
            status.setText("对比失败：" + e.getMessage());
        }
    }

    private void load(Path file) {
        manifestPath = file.toAbsolutePath().normalize();
        screenIndex = 0;
        initialFitDone = false;
        if (config == null) {
            config = EditorConfig.load(manifestPath);
            if (packs.isEmpty()) {
                packs.addAll(config.packs());
            }
            ensureDefaultPack();
            persistPacks();
        }
        reload();
        restartWatchers();
    }

    /**
     * 采集器会把 manifest 用到的纹理快照到同目录的 {@code default/}。
     * 把它作为最低优先级包补上，编辑器打开即可看到画面，不必手动导入原版材质。
     */
    private void ensureDefaultPack() {
        if (manifestPath == null || manifestPath.getParent() == null) {
            return;
        }
        Path snapshot = manifestPath.getParent().resolve("default");
        if (Files.isDirectory(snapshot.resolve("assets")) && !packs.contains(snapshot)) {
            packs.add(snapshot);
        }
    }

    /**
     * 把当前包列表写回配置，并记入历史工作区。
     */
    private void persistPacks() {
        if (config == null) {
            return;
        }
        config.setPacks(packs);
        config.remember(packs);
        config.save();
        refreshWorkspaces();
    }

    private void refreshWorkspaces() {
        updatingWorkspaces = true;
        workspaceBox.getItems().clear();
        if (config != null) {
            for (EditorConfig.Workspace workspace : config.workspaces()) {
                workspaceBox.getItems().add(new WorkspaceItem(workspace.packs()));
            }
        }
        for (WorkspaceItem item : workspaceBox.getItems()) {
            if (item.packs().equals(packs)) {
                workspaceBox.getSelectionModel().select(item);
                break;
            }
        }
        workspaceBox.setDisable(workspaceBox.getItems().isEmpty());
        updatingWorkspaces = false;
    }

    private void reload() {
        if (manifestPath == null) {
            return;
        }
        try {
            manifest = ManifestReader.read(manifestPath);
            if (manifest.screens().isEmpty()) {
                status.setText("manifest 里没有任何 screen");
                return;
            }
            screenIndex = Math.min(screenIndex, manifest.screens().size() - 1);
            refreshScreenBox();
            applyScene();
        } catch (IOException | RuntimeException e) {
            status.setText("加载失败：" + e.getMessage());
        }
    }

    private void refreshScreenBox() {
        updatingScreens = true;
        screenBox.getItems().clear();
        for (int i = 0; i < manifest.screens().size(); i++) {
            screenBox.getItems().add(new ScreenItem(i, manifest.screens().get(i).title()));
        }
        screenBox.getSelectionModel().select(screenIndex);
        screenBox.setDisable(manifest.screens().size() <= 1);
        updatingScreens = false;
    }

    private void applyScene() {
        if (manifest == null) {
            return;
        }
        String selectedKey = key(selected);
        scene = Scenes.withComputedBounds(manifest.screens().get(screenIndex));
        resolver = new PackResolver(packs);
        textureCache.clear();

        try {
            composite = new Compositor(resolver, scaleMode()).render(scene);
        } catch (IOException e) {
            status.setText("合成失败：" + e.getMessage());
            return;
        }
        fxImage = FxImages.toFx(composite);

        computeMissing();

        // 只要存在 源矩形 ≠ 目标矩形 的节点，三种方式就会画出不同的像素
        boolean scaled = false;
        for (BlitNode node : scene.nodes()) {
            if (node.dstW() != node.srcW() || node.dstH() != node.srcH()) {
                scaled = true;
                break;
            }
        }
        scaleBox.setDisable(!scaled);
        scaleBox.setTooltip(new Tooltip(scaled
                ? "「无」= 按 GUI " + Math.max(1, scene.guiScale())
                + "x 的物理分辨率合成（和游戏一致）；最近邻/双线性 = 按逻辑像素合成，用于对照"
                : "当前场景没有缩放节点，三种方式结果一致"));

        rebuildTable();

        selected = null;
        if (selectedKey != null) {
            for (BlitNode node : scene.nodes()) {
                if (key(node).equals(selectedKey)) {
                    selected = node;
                    selectRow(node);
                    break;
                }
            }
        }
        hovered = null;
        redraw();
        updateSheet();

        if (!initialFitDone) {
            initialFitDone = true;
            Platform.runLater(this::fitIfOverflowing);
        }
    }

    /**
     * 首次载入时如果 2x 放不下就自动缩到能看全，避免一打开只看到左上角。
     */
    private void fitIfOverflowing() {
        if (scene == null || scrollPane == null) {
            return;
        }
        double viewWidth = scrollPane.getViewportBounds().getWidth();
        double viewHeight = scrollPane.getViewportBounds().getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        if (composite.getWidth() * zoom > viewWidth || composite.getHeight() * zoom > viewHeight) {
            fitToWindow();
        }
    }

    private void computeMissing() {
        Set<String> missing = new HashSet<>();
        for (BlitNode node : scene.nodes()) {
            if (resolver.resolve(node.texture()) == null) {
                missing.add(node.texture());
            }
        }
        missingSet = Set.copyOf(missing);
        missingTextures = new ArrayList<>(missing);
        Collections.sort(missingTextures);
        missingCount = missingTextures.size();
    }

    /**
     * 节点列表按纹理分组：一张精灵图下挂所有引用它的节点，找图时不用在几百行里翻。
     */
    private void rebuildTable() {
        Map<String, List<NodeRow>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < scene.nodes().size(); i++) {
            BlitNode node = scene.nodes().get(i);
            grouped.computeIfAbsent(node.texture(), key -> new ArrayList<>())
                    .add(new NodeRow(node.texture(), i, node));
        }

        TreeItem<NodeRow> root = new TreeItem<>();
        rowItems.clear();
        updatingTable = true;
        for (Map.Entry<String, List<NodeRow>> entry : grouped.entrySet()) {
            TreeItem<NodeRow> group = new TreeItem<>(new NodeRow(entry.getKey(), -1, null));
            group.setExpanded(expandedGroups.contains(entry.getKey()));
            group.expandedProperty().addListener((o, old, value) -> {
                if (value) {
                    expandedGroups.add(entry.getKey());
                } else {
                    expandedGroups.remove(entry.getKey());
                }
            });
            for (NodeRow row : entry.getValue()) {
                TreeItem<NodeRow> item = new TreeItem<>(row);
                group.getChildren().add(item);
                rowItems.put(row.node(), item);
            }
            root.getChildren().add(group);
        }
        table.setRoot(root);
        updatingTable = false;
    }

    private Compositor.ScaleMode scaleMode() {
        ScaleModeItem item = scaleBox.getValue();
        return item == null ? Compositor.ScaleMode.NONE : item.mode();
    }

    /**
     * 只换采样方式时重新合成，不重建节点树、不动选中状态。
     */
    private void rerender() {
        if (scene == null) {
            return;
        }
        try {
            composite = new Compositor(resolver, scaleMode()).render(scene);
        } catch (IOException e) {
            status.setText("合成失败：" + e.getMessage());
            return;
        }
        fxImage = FxImages.toFx(composite);
        redraw();
        updateSheet();
    }

    // ---------------------------------------------------------------- 绘制

    private void redraw() {
        if (fxImage == null || scene == null) {
            return;
        }
        // 合成图是物理分辨率（逻辑像素 × guiScale），节点坐标都要先乘 renderScale 才能对上
        double unit = zoom * renderScale();
        double width = composite.getWidth() * zoom;
        double height = composite.getHeight() * zoom;
        canvas.setWidth(width);
        canvas.setHeight(height);

        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);

        double cell = Math.max(8, zoom * 4);
        for (double y = 0; y < height; y += cell) {
            for (double x = 0; x < width; x += cell) {
                boolean dark = ((int) (x / cell) + (int) (y / cell)) % 2 == 0;
                g.setFill(dark ? Color.rgb(32, 32, 36) : Color.rgb(44, 44, 50));
                g.fillRect(x, y, cell, cell);
            }
        }

        g.setImageSmoothing(false);
        g.drawImage(fxImage, 0, 0, width, height);

        if (pixelBox.isSelected() && zoom >= 3) {
            g.setLineWidth(1);
            g.setStroke(Color.color(1, 1, 1, 0.10));
            for (double x = 0; x <= width; x += zoom) {
                double px = Math.floor(x) + 0.5;
                g.strokeLine(px, 0, px, height);
            }
            for (double y = 0; y <= height; y += zoom) {
                double py = Math.floor(y) + 0.5;
                g.strokeLine(0, py, width, py);
            }
        }

        if (gridBox.isSelected()) {
            g.setLineWidth(1);
            for (BlitNode node : scene.nodes()) {
                g.setStroke(resolver.resolve(node.texture()) == null
                        ? Color.color(1.0, 0.55, 0.1, 0.75)
                        : Color.color(0.1, 0.6, 1.0, 0.40));
                g.strokeRect(node.dstX() * unit + 0.5, node.dstY() * unit + 0.5,
                        node.dstW() * unit - 1, node.dstH() * unit - 1);
            }
        }

        if (nineSliceBox.isSelected()) {
            drawNineSlice(g);
        }

        BlitNode active = activeNode();
        if (hovered != null && hovered != selected) {
            g.setLineWidth(2);
            g.setStroke(Color.rgb(255, 214, 64));
            g.strokeRect(hovered.dstX() * unit, hovered.dstY() * unit,
                    hovered.dstW() * unit, hovered.dstH() * unit);
        }
        if (active != null) {
            g.setLineWidth(2);
            g.setStroke(active == selected ? Color.rgb(255, 64, 64) : Color.rgb(255, 160, 64));
            g.strokeRect(active.dstX() * unit, active.dstY() * unit,
                    active.dstW() * unit, active.dstH() * unit);
        }

        updateStatus();
    }

    /**
     * 九宫格校验：对发生缩放的节点画 3×3 分割线，中心区就是被拉伸的部分。
     * 源矩形越界时用红框标出——这种节点在游戏里会采样到图外，是补丁最容易踩的坑。
     */
    private void drawNineSlice(GraphicsContext g) {
        Integer value = sliceSpinner.getValue();
        int border = value == null ? 4 : value;
        g.setLineWidth(1);

        for (BlitNode node : scene.nodes()) {
            if (node.dstW() == node.srcW() && node.dstH() == node.srcH()) {
                continue;
            }
            BufferedImage texture = texture(node.texture());
            if (texture == null) {
                continue;
            }

            // 把源图的边框像素映射到目标尺寸：九宫格里角块保持原像素，只有中心被拉伸
            int bx = Math.max(1, Math.round(border * node.dstW() / (float) node.srcW()));
            int by = Math.max(1, Math.round(border * node.dstH() / (float) node.srcH()));
            double unit = zoom * renderScale();
            double x = node.dstX() * unit;
            double y = node.dstY() * unit;
            double w = node.dstW() * unit;
            double h = node.dstH() * unit;
            double bxz = bx * unit;
            double byz = by * unit;
            if (w <= 2 * bxz || h <= 2 * byz) {
                continue;
            }

            g.setFill(Color.color(1.0, 0.55, 0.1, 0.10));
            g.fillRect(x + bxz, y + byz, w - 2 * bxz, h - 2 * byz);

            g.setStroke(Color.color(0.3, 0.8, 1.0, 0.85));
            g.strokeLine(x + bxz + 0.5, y, x + bxz + 0.5, y + h);
            g.strokeLine(x + w - bxz + 0.5, y, x + w - bxz + 0.5, y + h);
            g.strokeLine(x, y + byz + 0.5, x + w, y + byz + 0.5);
            g.strokeLine(x, y + h - byz + 0.5, x + w, y + h - byz + 0.5);

            if (node.srcX() < 0 || node.srcY() < 0
                    || node.srcX() + node.srcW() > texture.getWidth()
                    || node.srcY() + node.srcH() > texture.getHeight()) {
                g.setStroke(Color.color(1.0, 0.25, 0.25, 0.9));
                g.setLineWidth(2);
                g.strokeRect(x + 1, y + 1, Math.max(1, w - 2), Math.max(1, h - 2));
                g.setLineWidth(1);
            }
        }
    }

    private void hover(double px, double py) {
        BlitNode hit = pick(px, py);
        if (hit == hovered) {
            return;
        }
        hovered = hit;
        redraw();
        updateSheet();
    }

    private BlitNode pick(double px, double py) {
        if (scene == null) {
            return null;
        }
        double unit = zoom * renderScale();
        double x = px / unit;
        double y = py / unit;
        BlitNode top = null;
        for (BlitNode node : scene.nodes()) {
            if (x >= node.dstX() && x < node.dstX() + node.dstW()
                    && y >= node.dstY() && y < node.dstY() + node.dstH()) {
                if (top == null || node.z() >= top.z()) {
                    top = node;
                }
            }
        }
        return top;
    }

    private void updateStatus() {
        if (scene == null) {
            return;
        }
        String text = String.format("%d 个节点 · 缺失纹理 %d 个 · %d×%d（合成 %dx）· %.2fx",
                scene.nodes().size(), missingCount, scene.width(), scene.height(),
                renderScale(), zoom);
        status.setText(text);
        if (missingTextures.isEmpty()) {
            status.setTooltip(null);
        } else {
            Tooltip tooltip = new Tooltip("缺失：\n" + String.join("\n", missingTextures));
            tooltip.setStyle("-fx-font-size: 11px;");
            status.setTooltip(tooltip);
        }
    }

    private void updateSheet() {
        BlitNode active = activeNode();
        if (active == null) {
            sheet.clear(scene == null ? "载入 manifest 后显示源纹理" : "悬停或点击画布上的元素");
            sheetInfo.setText("—");
            return;
        }
        BufferedImage texture = texture(active.texture());
        List<BlitNode> references = new ArrayList<>();
        for (BlitNode node : scene.nodes()) {
            if (node.texture().equals(active.texture())) {
                references.add(node);
            }
        }
        sheet.show(texture, active, references);

        PackResolver.Hit hit = resolver.resolveHit(active.texture());
        String origin = hit == null ? "未找到" : name(hit.root());
        int textureWidth = texture == null ? 0 : texture.getWidth();
        int textureHeight = texture == null ? 0 : texture.getHeight();
        sheetInfo.setText(String.format("%s · %d×%d · 来自 %s%nsrc=(%d,%d,%d,%d)  dst=(%d,%d,%d,%d)  z=%d",
                shortName(active.texture()), textureWidth, textureHeight, origin,
                active.srcX(), active.srcY(), active.srcW(), active.srcH(),
                active.dstX(), active.dstY(), active.dstW(), active.dstH(), active.z()));
    }

    private void fitToWindow() {
        if (scene == null || scrollPane == null) {
            return;
        }
        double viewWidth = scrollPane.getViewportBounds().getWidth() - 16;
        double viewHeight = scrollPane.getViewportBounds().getHeight() - 16;
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        double fit = Math.min(viewWidth / composite.getWidth(), viewHeight / composite.getHeight());
        // 按 0.25 步进取整，既能铺满又不会因为小数缩放把像素拉花
        setZoom(Math.max(0.25, Math.floor(fit * 4) / 4.0));
        redraw();
    }

    private void selectRow(BlitNode node) {
        TreeItem<NodeRow> item = rowItems.get(node);
        if (item == null) {
            return;
        }
        updatingTable = true;
        TreeItem<NodeRow> group = item.getParent();
        if (group != null) {
            group.setExpanded(true);
            expandedGroups.add(group.getValue().texture());
        }
        table.getSelectionModel().select(item);
        table.scrollTo(table.getRow(item));
        updatingTable = false;
    }

    private void exportPng() {
        if (composite == null) {
            status.setText("还没有可导出的合成图");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出合成图");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        if (manifestPath != null && manifestPath.getParent() != null) {
            chooser.setInitialDirectory(manifestPath.getParent().toFile());
        }
        chooser.setInitialFileName((scene == null ? "preview" : scene.id()) + ".png");
        File out = chooser.showSaveDialog(stage);
        if (out == null) {
            return;
        }
        try {
            ImageIO.write(composite, "png", out);
            status.setText("已导出 " + out.getAbsolutePath());
        } catch (IOException e) {
            status.setText("导出失败：" + e.getMessage());
        }
    }

    /**
     * 切图参考图：每张精灵图导出一张标注了所有 src 矩形的 PNG，画师直接照着改。
     */
    private void exportReference() {
        if (scene == null) {
            status.setText("先载入 manifest 再导出参考图");
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择参考图输出目录");
        if (manifestPath != null && manifestPath.getParent() != null) {
            chooser.setInitialDirectory(manifestPath.getParent().toFile());
        }
        File dir = chooser.showDialog(stage);
        if (dir == null) {
            return;
        }
        try {
            int count = ReferenceExport.export(dir.toPath(), scene, resolver);
            status.setText("已导出 " + count + " 张参考图 → " + dir.getAbsolutePath());
        } catch (IOException e) {
            status.setText("参考图导出失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 资源

    private BufferedImage texture(String location) {
        if (textureCache.containsKey(location)) {
            return textureCache.get(location);
        }
        BufferedImage image = null;
        Path file = resolver.resolve(location);
        if (file != null) {
            try {
                image = ImageIO.read(file.toFile());
            } catch (IOException ignored) {
                // 坏图按缺失处理
            }
        }
        textureCache.put(location, image);
        return image;
    }

    private BlitNode activeNode() {
        return selected != null ? selected : hovered;
    }

    /**
     * 合成图相对逻辑像素的倍率。
     *
     * <p>「无」与游戏一致：1 个逻辑像素 = guiScale 个真实像素；另外两种按逻辑像素合成（倍率 1）。
     */
    private int renderScale() {
        if (scene == null) {
            return 1;
        }
        return scaleMode() == Compositor.ScaleMode.NONE ? Math.max(1, scene.guiScale()) : 1;
    }

    private void refreshPacksLabel() {
        if (packs.isEmpty()) {
            packsLabel.setText("资源包：未添加（游戏内按 F9 会导出默认材质快照 default/）");
            return;
        }
        packsLabel.setText("资源包：" + packs.stream().map(EditorApp::name)
                .collect(Collectors.joining("  >  ")));
    }

    // ---------------------------------------------------------------- 热重载

    private void restartWatchers() {
        watching = false;
        if (watchThread != null) {
            watchThread.interrupt();
        }
        List<Path> roots = new ArrayList<>();
        if (manifestPath != null && manifestPath.getParent() != null) {
            roots.add(manifestPath.getParent());
        }
        roots.addAll(packs);
        if (roots.isEmpty()) {
            return;
        }
        watching = true;
        watchThread = new Thread(() -> watchLoop(List.copyOf(roots)), "viewmyui-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void watchLoop(List<Path> roots) {
        AtomicInteger budget = new AtomicInteger(MAX_WATCH_DIRS);
        try (WatchService service = FileSystems.getDefault().newWatchService()) {
            for (Path root : roots) {
                registerTree(root, service, budget, 0);
            }
            while (watching) {
                WatchKey key = service.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
                boolean changed = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (!(key.watchable() instanceof Path dir) || !(event.context() instanceof Path name)) {
                        changed = true;
                        continue;
                    }
                    // 自己写配置不算资源变更，否则每次切换工作区都会多触发一次重载
                    if (name.getFileName().toString().equals(EditorConfig.FILE_NAME)) {
                        continue;
                    }
                    changed = true;
                    Path child = dir.resolve(name);
                    if (Files.isDirectory(child)) {
                        registerTree(child, service, budget, 0);
                    }
                }
                key.reset();
                if (changed) {
                    // 合并连续写入（PS 保存一张图会产生多个事件）
                    Thread.sleep(250);
                    if (watching) {
                        Platform.runLater(this::reload);
                    }
                }
            }
        } catch (InterruptedException ignored) {
            // 被新一轮监听替换
        } catch (IOException e) {
            Platform.runLater(() -> status.setText("目录监听失败：" + e.getMessage()));
        }
    }

    private void registerTree(Path root, WatchService service, AtomicInteger budget, int depth)
            throws IOException {
        if (depth > 8 || budget.get() <= 0 || !Files.isDirectory(root)) {
            return;
        }
        budget.decrementAndGet();
        try {
            root.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException ignored) {
            return;
        }
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)) {
                    registerTree(child, service, budget, depth + 1);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String key(BlitNode node) {
        if (node == null) {
            return null;
        }
        return node.texture() + '|' + node.srcX() + ',' + node.srcY() + ',' + node.srcW() + ',' + node.srcH()
                + '|' + node.dstX() + ',' + node.dstY() + ',' + node.dstW() + ',' + node.dstH()
                + '|' + node.z();
    }

    private static String name(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private static String shortName(String texture) {
        int slash = texture.lastIndexOf('/');
        return slash < 0 ? texture : texture.substring(slash + 1);
    }

    private static String rect(int x, int y, int width, int height) {
        return x + "," + y + "," + width + "," + height;
    }

    private record NodeRow(String texture, int index, BlitNode node) {
        boolean group() {
            return node == null;
        }
    }

    private record ScaleModeItem(Compositor.ScaleMode mode, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record ScreenItem(int index, String title) {
        @Override
        public String toString() {
            return index + " · " + title;
        }
    }

    private record WorkspaceItem(List<Path> packs) {
        @Override
        public String toString() {
            if (packs.isEmpty()) {
                return "（空）";
            }
            String text = packs.stream().map(EditorApp::name).collect(Collectors.joining(" > "));
            return text.length() > 44 ? text.substring(0, 43) + "…" : text;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
