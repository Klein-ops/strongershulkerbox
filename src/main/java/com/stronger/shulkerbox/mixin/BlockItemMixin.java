package com.stronger.shulkerbox.mixin;

import net.minecraft.world.item.BlockItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 解除「潜影盒（及其它方块物品）能否放入容器」的限制：
 * 原版 BlockItem#canFitInsideContainerItems 对潜影盒返回 false（用于阻止潜影盒嵌套），
 * 本模组改为始终返回 true，使潜影盒可以放入任何容器。
 * 嵌套层数限制由 ShulkerBoxSlotMixin / ShulkerBoxBlockEntityMixin 单独实施。
 */
@Mixin(BlockItem.class)
public class BlockItemMixin {
    @Inject(method = "canFitInsideContainerItems()Z", at = @At("HEAD"), cancellable = true)
    private void sbs$allowFitInsideContainer(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}