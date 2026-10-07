package com.stronger.shulkerbox.mixin;

import com.stronger.shulkerbox.ShulkerBoxItemContainer;
import com.stronger.shulkerbox.ShulkerBoxNesting;
import net.minecraft.world.inventory.ShulkerBoxSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 潜影盒 GUI 槽位的放入校验：
 *  1) 嵌套深度：潜影盒物品的相对深度不得超过 MAX-1（即 ≤ 4）；
 *  2) 自包含/祖先包含：禁止把「正在打开的潜影盒（本层或任意祖先层）」
 *     塞进潜影盒容器 —— 否则物品会被移出原槽并被吞，GUI 也会异常关闭。
 */
@Mixin(ShulkerBoxSlot.class)
public class ShulkerBoxSlotMixin {
    @Inject(method = "mayPlace(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void sbs$enforceNestingDepth(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!ShulkerBoxNesting.canPlaceIntoShulker(stack)) {
            cir.setReturnValue(false);
            return;
        }
        if (ShulkerBoxItemContainer.isOpenBacking(stack)) {
            // 不能把「正在打开的盒子」放进潜影盒，防止自包含/环
            cir.setReturnValue(false);
        }
    }
}