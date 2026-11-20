package com.viewmyui.core.manifest;

import java.util.List;

/**
 * UI 布局清单。工具的核心产物：把"画什么、画在哪、用哪块纹理"从"纹理内容"中解耦。
 *
 * @param capturedFrom {@code runtime} | {@code static-decompile} | {@code manual}
 */
public record UiManifest(
        int schemaVersion,
        Meta meta,
        List<ScreenScene> screens) {

    public UiManifest {
        screens = List.copyOf(screens);
    }

    /**
     * @param guiScale 1.21.1 起是 double（自动缩放可能是 2.5），1.20.1 的整数会被原样读入
     */
    public record Meta(
            String minecraft,
            String loader,
            String modId,
            double guiScale,
            String capturedFrom) {
    }
}
