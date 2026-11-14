package com.viewmyui.editor;

import com.viewmyui.core.manifest.BlitNode;
import com.viewmyui.core.manifest.ScreenScene;

/**
 * scene 的宽高在 manifest 里可能缺失（旧版本采集器没写），此时按节点包围盒兜底。
 */
final class Scenes {

    private Scenes() {
    }

    static ScreenScene withComputedBounds(ScreenScene scene) {
        if (scene.width() > 0 && scene.height() > 0) {
            return scene;
        }
        int width = 0;
        int height = 0;
        for (BlitNode node : scene.nodes()) {
            width = Math.max(width, node.dstX() + node.dstW());
            height = Math.max(height, node.dstY() + node.dstH());
        }
        return new ScreenScene(scene.id(), scene.title(),
                Math.max(1, width), Math.max(1, height), scene.guiScale(), scene.nodes());
    }
}
