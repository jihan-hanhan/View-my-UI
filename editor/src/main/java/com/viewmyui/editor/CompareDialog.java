package com.viewmyui.editor;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;

/**
 * 选择「基准包」和「补丁包」两套资源包，用于左右并排对比。
 *
 * <p>两边默认都是编辑器当前的包列表——通常做法是只给右边再加一个补丁包目录。
 */
final class CompareDialog {

    /**
     * @param left      基准（原版 / 未打补丁的包）
     * @param right     对比（补丁包）
     * @param highlight 是否叠加差异高亮
     */
    record Choice(List<Path> left, List<Path> right, boolean highlight) {
    }

    private CompareDialog() {
    }

    /**
     * @return 用户取消时返回 {@code null}
     */
    static Choice show(Window owner, List<Path> current) {
        Dialog<Choice> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("并排对比");
        dialog.setResizable(true);

        PackListBox left = new PackListBox(owner, "A · 基准（原版 / 未打补丁）", current);
        PackListBox right = new PackListBox(owner, "B · 对比（补丁包）", current);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setPrefWidth(340);
        right.setPrefWidth(340);

        HBox lists = new HBox(12, left, right);
        VBox.setVgrow(lists, Priority.ALWAYS);

        Label hint = new Label("同一个 manifest 分别用两套包合成，左右并排显示；洋红色 = 两边像素不一致的位置。");
        hint.setStyle("-fx-text-fill: #9a9aa2;");
        hint.setWrapText(true);

        CheckBox highlight = new CheckBox("差异高亮");
        highlight.setSelected(true);

        VBox content = new VBox(8, hint, highlight, lists);
        content.setPadding(new Insets(10));
        content.setPrefSize(760, 440);
        VBox.setVgrow(lists, Priority.ALWAYS);

        dialog.getDialogPane().setContent(content);
        ButtonType compare = new ButtonType("对比", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(compare, ButtonType.CANCEL);
        dialog.setResultConverter(button ->
                button == compare ? new Choice(left.packs(), right.packs(), highlight.isSelected()) : null);

        return dialog.showAndWait().orElse(null);
    }
}
