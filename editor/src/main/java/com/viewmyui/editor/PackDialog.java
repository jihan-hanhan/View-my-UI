package com.viewmyui.editor;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;

/**
 * 资源包优先级管理。列表靠上的包优先——和 Minecraft 自己的覆盖规则一致。
 */
final class PackDialog {

    private PackDialog() {
    }

    /**
     * @return 用户确认后的新列表（保持顺序）；直接关闭则原样返回
     */
    static List<Path> show(Window owner, List<Path> current) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("资源包");
        dialog.setResizable(true);

        PackListBox box = new PackListBox(owner, "资源包", current);
        box.setPadding(new Insets(10));
        box.setPrefSize(520, 380);

        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();

        return box.packs();
    }
}
