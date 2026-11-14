package com.viewmyui.editor;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 可编辑的资源包优先级列表：添加 / 移除 / 上下移动，靠上的包优先。
 *
 * <p>编辑器的「资源包」对话框与「对比」对话框都用它，避免两份一样的列表逻辑。
 */
final class PackListBox extends VBox {

    private final ListView<Path> list = new ListView<>();
    private final Window owner;

    PackListBox(Window owner, String title, List<Path> initial) {
        super(6);
        this.owner = owner;

        Label header = new Label(title);
        header.setStyle("-fx-font-weight: bold;");

        list.getItems().setAll(initial);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                if (empty || path == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(path.getFileName() == null ? path.toString()
                        : path.getFileName().toString());
                Label full = new Label(path.toString());
                full.setStyle("-fx-text-fill: #9a9aa2; -fx-font-size: 11px;");
                VBox box = new VBox(1, name, full);
                setGraphic(box);
                setText(null);
            }
        });

        Button add = new Button("添加…");
        add.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择资源包根目录（包含 assets/ 的那一层）");
            File dir = chooser.showDialog(owner);
            if (dir != null) {
                list.getItems().add(dir.toPath());
            }
        });

        Button remove = new Button("移除");
        remove.setOnAction(e -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                list.getItems().remove(index);
            }
        });

        Button up = new Button("↑");
        up.setOnAction(e -> move(-1));

        Button down = new Button("↓");
        down.setOnAction(e -> move(1));

        HBox buttons = new HBox(6, add, remove, up, down);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(header, hint(), list, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
    }

    private Label hint() {
        Label hint = new Label("靠上的包优先覆盖下面的包。目录下应包含 assets/。");
        hint.setStyle("-fx-text-fill: #9a9aa2;");
        hint.setWrapText(true);
        return hint;
    }

    private void move(int delta) {
        int index = list.getSelectionModel().getSelectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= list.getItems().size()) {
            return;
        }
        Path moved = list.getItems().remove(index);
        list.getItems().add(target, moved);
        list.getSelectionModel().select(target);
    }

    /**
     * @return 仍存在的目录（用户可能在外面把包删了）
     */
    List<Path> packs() {
        List<Path> result = new ArrayList<>();
        for (Path path : list.getItems()) {
            if (Files.isDirectory(path)) {
                result.add(path);
            }
        }
        return result;
    }
}
