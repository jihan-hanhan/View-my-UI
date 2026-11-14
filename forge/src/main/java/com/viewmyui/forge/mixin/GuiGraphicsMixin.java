package com.viewmyui.forge.mixin;

import com.viewmyui.forge.capture.BlitRecorder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.1 里所有 2D 绘制最终都汇聚到 {@code GuiGraphics.innerBlit}（归一化 UV 的那个重载），
 * 因此只拦这一个点就能覆盖全部纹理绘制。
 *
 * <p>注意：{@code require = 0} —— 若版本升级导致描述符变化，宁可静默失效也不要让游戏崩。
 *
 * <p>方法名同时给两个：开发环境（official mappings）用 {@code innerBlit}，
 * 正式环境（SRG）用 {@code m_280444_}。1.17+ 的类名不做混淆，所以描述符两边一致，
 * 这样不需要 refmap，也就不需要额外的注解处理器 / MixinGradle。
 * SRG 名来自 FG 反混淆用的 {@code build/reobfJar/mappings.tsrg}。
 */
@Mixin(GuiGraphics.class)
public class GuiGraphicsMixin {

    @Inject(
            method = {
                    "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFF)V",
                    "m_280444_(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFF)V"
            },
            at = @At("HEAD"),
            require = 0
    )
    private void viewmyui$captureBlit(ResourceLocation texture,
                                      int x1, int x2, int y1, int y2, int blitOffset,
                                      float minU, float maxU, float minV, float maxV,
                                      CallbackInfo ci) {
        BlitRecorder.INSTANCE.record(
                texture,
                x1, y1, x2 - x1, y2 - y1,
                minU, minV, maxU - minU, maxV - minV);
    }
}
