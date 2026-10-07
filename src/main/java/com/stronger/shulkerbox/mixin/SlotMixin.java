package com.stronger.shulkerbox.mixin;

import com.stronger.shulkerbox.ShulkerBoxItemContainer;
import com.stronger.shulkerbox.ShulkerBoxNesting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 普通槽位（箱子、物品栏、投掷器 GUI 等）的放入 / 取出校验。
 *
 * 1) 放入：潜影盒物品相对深度不得超过 MAX（≤ 5），防止超深物品进入任意容器。
 *    （ShulkerBoxSlot 覆写了 mayPlace，不经过本类，由 ShulkerBoxSlotMixin 处理。）
 * 2) v1.2.2 新增「锁死正在打开的潜影盒」：
 *    - mayPickup 返回 false → 该盒子所在槽位不能被取走。
 *      这一处同时覆盖：
 *        PICKUP（左/右键拾取）、QUICK_MOVE（Shift 快速移动）、
 *        SWAP（数字键交换）、THROW（按 Q 丢弃，经 safeTake→tryRemove→mayPickup）。
 *    - mayPlace 返回 false → 该盒子也无法被放进任何其它槽位。
 *    从而做到：打开哪个盒子，就锁哪个盒子，不能移动、不能丢弃、也不能被光标拿起。
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow
    public abstract ItemStack getItem();

    @Inject(method = "mayPlace(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void sbs$enforceNormalContainerDepth(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!ShulkerBoxNesting.canPlaceIntoNormalContainer(stack)) {
            cir.setReturnValue(false);
            return;
        }
        // 正在打开（锁定）的潜影盒不能被放进任何槽位
        if (ShulkerBoxItemContainer.isOpenBacking(stack)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mayPickup(Lnet/minecraft/world/entity/player/Player;)Z", at = @At("HEAD"), cancellable = true)
    private void sbs$lockOpenShulkerBoxSlot(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (ShulkerBoxItemContainer.isOpenBackingFor(player, this.getItem())) {
            // 该槽位里是「该玩家当前打开链中的宿主潜影盒」：
            // 不允许被取走 / 丢弃 / 交换。
            // 注意：只锁「正在打开的宿主盒子」本身，盒子内部的其它潜影盒不受影响。
            cir.setReturnValue(false);
        }
    }
}