package com.stronger.shulkerbox.mixin;

import com.stronger.shulkerbox.ShulkerBoxNesting;
import net.minecraft.world.inventory.ShulkerBoxSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 潜影盒 GUI 槽位的放入校验：
 * 原版 ShulkerBoxSlot#mayPlace 只检查 canFitInsideContainerItems（本模组已改为 true），
 * 这里追加嵌套深度校验：潜影盒物品的相对深度不得超过 MAX-1（即 ≤ 4），
 * 从而保证「打开任意一层潜影盒，往里最多还能形成到第 5 层的链路」。
 */
@Mixin(ShulkerBoxSlot.class)
public class ShulkerBoxSlotMixin {
    @Inject(method = "mayPlace(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void sbs$enforceNestingDepth(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!ShulkerBoxNesting.canPlaceIntoShulker(stack)) {
            cir.setReturnValue(false);
        }
    }
}