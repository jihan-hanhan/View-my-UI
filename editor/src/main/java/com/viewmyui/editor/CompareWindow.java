package com.viewmyui.editor;

import com.viewmyui.core.manifest.BlitNode;
import com.viewmyui.core.manifest.ScreenScene;
import com.viewmyui.core.pack.PackResolver;
import com.viewmyui.core.render.Compositor;
import com.viewmyui.core.render.ImageDiff;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 并排对比窗口：同一个 scene 用两套资源包各合成一张，左右显示，差异像素标洋红。
 *
 * <p>这是补丁作者最常用的视图——"我改的这张图到底影响了界面的哪几块"直接肉眼可见。
 */
final class CompareWindow {

    private static final double PADDING = 10;
    private static final double TITLE_HEIGHT = 18;
    private static final double GAP = 18;

    private final Stage stage = new Stage();
    private final Canvas canvas = new Canvas(1, 1);
    private final Label info = new Label();
    private final CheckBox highlight = new CheckBox("差异高亮");
    private final Slider zoomSlider = new Slider(0.25, 16, 1);
    private final Label zoomLabel = new Label("1.00x");

    private final ScreenScene scene;
    private final BufferedImage leftImage;
    private final BufferedImage rightImage;
    private final WritableImage fxLeft;
    private final WritableImage fxRight;
    private final WritableImage fxOverlay;

    private double zoom = 1;
    private boolean updatingZoom;

    private CompareWindow(Window owner, ScreenScene scene, List<Path> leftPacks,
                          List<Path> rightPacks, boolean highlightDiffs) throws IOException {
        this.scene = scene;

        PackResolver leftResolver = new PackResolver(leftPacks);
        PackResolver rightResolver = new PackResolver(rightPacks);
        this.leftImage = new Compositor(leftResolver).render(scene);
        this.rightImage = new Compositor(rightResolver).render(scene);
        this.fxLeft = FxImages.toFx(leftImage);
        this.fxRight = FxImages.toFx(rightImage);

        ImageDiff.Result diff = ImageDiff.compare(leftImage, rightImage);
        this.fxOverlay = FxImages.toFx(diff.overlay());

        highlight.setSelected(highlightDiffs);
        highlight.selectedProperty().addListener((o, old, value) -> redraw());

        BorderPane root = new BorderPane();
        root.setTop(buildToolbar(leftPacks, rightPacks));
        root.setCenter(buildCenter());
        root.setBottom(buildStatus(diff, leftResolver, rightResolver));

        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double width = Math.min(1240, screen.getWidth() * 0.94);
        double height = Math.min(820, screen.getHeight() * 0.92);
        stage.setScene(new Scene(root, width, height));
        stage.initOwner(owner);
        stage.setTitle("并排对比 — " + scene.title());
        redraw();
    }

    static void open(Window owner, ScreenScene scene, List<Path> leftPacks,
                     List<Path> rightPacks, boolean highlight) throws IOException {
        new CompareWindow(owner, scene, leftPacks, rightPacks, highlight).stage.show();
    }

    private HBox buildToolbar(List<Path> leftPacks, List<Path> rightPacks) {
        zoomSlider.setPrefWidth(140);
        zoomSlider.setBlockIncrement(0.25);
        zoomSlider.valueProperty().addListener((o, old, value) -> {
            if (updatingZoom) {
                return;
            }
            setZoom(value.doubleValue());
            redraw();
        });
        zoomLabel.setMinWidth(48);

        HBox toolbar = new HBox(8, new Label("缩放"), zoomSlider, zoomLabel,
                new javafx.scene.control.Separator(), highlight);

        Label packs = new Label("A: " + summary(leftPacks) + "    B: " + summary(rightPacks));
        packs.setStyle("-fx-text-fill: #8a8a92;");
        packs.setMaxWidth(520);
        packs.setTooltip(new javafx.scene.control.Tooltip(
                "A: " + String.join("\n", leftPacks.stream().map(Path::toString).toList())
                        + "\nB: " + String.join("\n", rightPacks.stream().map(Path::toString).toList())));
        HBox.setHgrow(packs, Priority.ALWAYS);
        toolbar.getChildren().add(packs);

        toolbar.setPadding(new Insets(8));
        toolbar.setAlignment(Pos.CENTER_LEFT);
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

    private ScrollPane buildCenter() {
        ScrollPane scrollPane = new ScrollPane(new Pane(canvas));
        scrollPane.setPannable(true);
        scrollPane.setFitToWidth(false);
        scrollPane.setFitToHeight(false);
        canvas.setOnScroll(e -> {
            if (!e.isControlDown()) {
                return;
            }
            setZoom(zoom + (e.getDeltaY() > 0 ? 0.5 : -0.5));
            redraw();
            e.consume();
        });
        return scrollPane;
    }

    private HBox buildStatus(ImageDiff.Result diff, PackResolver leftResolver,
                             PackResolver rightResolver) {
        info.setText(String.format("差异 %,d / %,d 像素（%.2f%%） · A 缺纹理 %d 张 · B 缺纹理 %d 张",
                diff.differing(), diff.compared(), diff.ratio() * 100,
                missing(scene, leftResolver), missing(scene, rightResolver)));
        info.setStyle("-fx-text-fill: #b8b8be;");
        HBox bar = new HBox(info);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void redraw() {
        int imageWidth = Math.max(leftImage.getWidth(), rightImage.getWidth());
        int imageHeight = Math.max(leftImage.getHeight(), rightImage.getHeight());

        double panelWidth = imageWidth * zoom;
        double panelHeight = imageHeight * zoom;
        double totalWidth = PADDING * 2 + panelWidth * 2 + GAP;
        double totalHeight = PADDING + TITLE_HEIGHT + panelHeight + PADDING;

        canvas.setWidth(totalWidth);
        canvas.setHeight(totalHeight);

        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, totalWidth, totalHeight);

        double top = PADDING + TITLE_HEIGHT;
        drawPanel(g, 0, fxLeft, panelWidth, panelHeight, top);
        drawPanel(g, panelWidth + GAP, fxRight, panelWidth, panelHeight, top);

        g.setFill(Color.rgb(205, 205, 212));
        g.setFont(Font.font(12));
        g.fillText("A · 基准", PADDING, PADDING + 10);
        g.fillText("B · 对比", PADDING + panelWidth + GAP, PADDING + 10);
    }

    private void drawPanel(GraphicsContext g, double x, WritableImage image,
                           double panelWidth, double panelHeight, double top) {
        drawChecker(g, x, top, panelWidth, panelHeight);

        g.setImageSmoothing(false);
        g.drawImage(image, x, top, panelWidth, panelHeight);
        if (highlight.isSelected()) {
            g.drawImage(fxOverlay, x, top, panelWidth, panelHeight);
        }

        g.setLineWidth(1);
        g.setStroke(Color.color(1, 1, 1, 0.18));
        g.strokeRect(x + 0.5, top + 0.5, panelWidth - 1, panelHeight - 1);
    }

    private void drawChecker(GraphicsContext g, double x, double y, double width, double height) {
        double cell = Math.max(8, zoom * 4);
        g.save();
        g.beginPath();
        g.rect(x, y, width, height);
        g.closePath();
        g.clip();
        for (double row = 0; row * cell < height; row++) {
            for (double col = 0; col * cell < width; col++) {
                boolean dark = ((int) row + (int) col) % 2 == 0;
                g.setFill(dark ? Color.rgb(32, 32, 36) : Color.rgb(44, 44, 50));
                g.fillRect(x + col * cell, y + row * cell, cell, cell);
            }
        }
        g.restore();
    }

    private static int missing(ScreenScene scene, PackResolver resolver) {
        Set<String> missing = new HashSet<>();
        for (BlitNode node : scene.nodes()) {
            if (resolver.resolve(node.texture()) == null) {
                missing.add(node.texture());
            }
        }
        return missing.size();
    }

    private static String summary(List<Path> packs) {
        if (packs.isEmpty()) {
            return "未添加";
        }
        return packs.stream().map(path -> path.getFileName() == null
                ? path.toString() : path.getFileName().toString()).collect(Collectors.joining(" > "));
    }
}
