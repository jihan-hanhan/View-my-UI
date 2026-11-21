package com.viewmyui.forge.mixin;

import com.viewmyui.forge.capture.BlitRecorder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 采集入口。
 *
 * <p>NeoForge 1.21.1 运行时是 Mojmap，只有一套方法名，不再需要 1.20.1 时代的 SRG 双名。
 *
 * <p>挂钩点：
 * <ul>
 *   <li>{@code innerBlit}：所有原始纹理绘制最终都汇聚到这里（1.21.1 仍然是 10 参、包内可见）。</li>
 *   <li>{@code blitSprite}（五/六参）：整张精灵图，内部会再调 {@code innerBlit}；
 *       这里先记一条 sprite 节点，并在调用期间屏蔽 {@code innerBlit}，避免重复。</li>
 *   <li>{@code blitSprite}（十参，九参委托给它）：精灵图的局部区域（进度条、装饰件等）。</li>
 *   <li>{@code blit(..., TextureAtlasSprite)}：直接传图集精灵的旧接口，按精灵名反推 PNG 路径。</li>
 * </ul>
 *
 * <p>{@code require = 0}：若版本升级导致描述符变化，宁可静默失效也不要让游戏崩。
 */
@Mixin(GuiGraphics.class)
public class GuiGraphicsMixin {

    @Inject(
            method = "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFF)V",
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

    /**
     * 1.21.1 里 {@code blitSprite(id, x, y, w, h)} 会委托给
     * {@code blitSprite(id, x, y, z, w, h)}，所以只挂六参这一个即可覆盖两者。
     *
     * <p>{@code z} 是绘制偏移，编辑器按帧内顺序还原层级，这里忽略。
     */
    @Inject(
            method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIII)V",
            at = @At("HEAD"),
            require = 0
    )
    private void viewmyui$captureSpriteStart(ResourceLocation sprite,
                                             int x, int y, int z, int width, int height,
                                             CallbackInfo ci) {
        BlitRecorder.INSTANCE.beginSprite(sprite, x, y, width, height);
    }

    @Inject(
            method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIII)V",
            at = @At("RETURN"),
            require = 0
    )
    private void viewmyui$captureSpriteEnd(ResourceLocation sprite,
                                           int x, int y, int z, int width, int height,
                                           CallbackInfo ci) {
        BlitRecorder.INSTANCE.endSprite();
    }

    /**
     * 十参 {@code blitSprite(id, srcW, srcH, u, v, x, y, z, w, h)}：只画精灵图的一块。
     * 九参版本内部委托给它（z=0），所以挂这一个就能覆盖两者。
     *
     * <p>原版大量进度条/装饰件走这条路径（熔炉、酿造台、商人经验条、马的箱子格……），
     * 之前只挂了六参，导致这些控件整体缺失。
     */
    @Inject(
            method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIIIIIII)V",
            at = @At("HEAD"),
            require = 0
    )
    private void viewmyui$capturePartialSpriteStart(ResourceLocation sprite,
                                                    int sourceWidth, int sourceHeight,
                                                    int u, int v, int x, int y, int z,
                                                    int width, int height,
                                                    CallbackInfo ci) {
        BlitRecorder.INSTANCE.beginSprite(sprite, u, v, x, y, width, height);
    }

    @Inject(
            method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIIIIIII)V",
            at = @At("RETURN"),
            require = 0
    )
    private void viewmyui$capturePartialSpriteEnd(ResourceLocation sprite,
                                                  int sourceWidth, int sourceHeight,
                                                  int u, int v, int x, int y, int z,
                                                  int width, int height,
                                                  CallbackInfo ci) {
        BlitRecorder.INSTANCE.endSprite();
    }

    /**
     * {@code blit(x, y, z, w, h, TextureAtlasSprite)}：直接传精灵图对象的旧接口，
     * 1.21.1 里仍有原生界面在用（如背包的空护甲槽图标）。
     */
    @Inject(
            method = "blit(IIIIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V",
            at = @At("HEAD"),
            require = 0
    )
    private void viewmyui$captureAtlasSprite(int x, int y, int z, int width, int height,
                                             TextureAtlasSprite sprite, CallbackInfo ci) {
        BlitRecorder.INSTANCE.recordAtlasSprite(sprite, x, y, width, height);
    }
}
