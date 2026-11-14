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

    public record Meta(
            String minecraft,
            String loader,
            String modId,
            int guiScale,
            String capturedFrom) {
    }
}
