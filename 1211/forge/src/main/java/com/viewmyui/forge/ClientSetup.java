package com.viewmyui.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * 客户端专用初始化。用 {@code value = Dist.CLIENT} 保证该类不会在专用服务端被加载
 * （否则会触发客户端专属类的 NoClassDefFoundError）。
 */
@EventBusSubscriber(modid = ViewMyUI.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    public static final String CATEGORY = "key.categories.viewmyui";

    public static KeyMapping captureKey;
    public static KeyMapping editorKey;

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        captureKey = new KeyMapping("key.viewmyui.capture", InputConstants.KEY_F9, CATEGORY);
        editorKey = new KeyMapping("key.viewmyui.editor", InputConstants.KEY_F10, CATEGORY);
        event.register(captureKey);
        event.register(editorKey);
    }
}
