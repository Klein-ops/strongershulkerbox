package com.stronger.shulkerbox.mixin;

import com.stronger.shulkerbox.ShulkerBoxNesting;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 普通槽位（箱子、物品栏、投掷器 GUI 等）的放入校验：
 * 原版 Slot#mayPlace 恒 true。本模组追加安全网：潜影盒物品放入普通容器时，
 * 其相对深度不得超过 MAX（≤ 5），防止超深物品（旧档/外部导入）进入任意容器。
 * 注意：ShulkerBoxSlot 覆写了 mayPlace，不经过本类（由 ShulkerBoxSlotMixin 处理）。
 */
@Mixin(Slot.class)
public class SlotMixin {
    @Inject(method = "mayPlace(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void sbs$enforceNormalContainerDepth(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!ShulkerBoxNesting.canPlaceIntoNormalContainer(stack)) {
            cir.setReturnValue(false);
        }
    }
}