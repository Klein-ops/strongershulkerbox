package com.stronger.shulkerbox.mixin;

import com.stronger.shulkerbox.ShulkerBoxNesting;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 潜影盒方块实体的自动化放入校验（漏斗、投掷器、管道等走 canPlaceItemThroughFace）。
 * 原版实现：
 *   return !(Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock)
 *          && stack.getItem().canFitInsideContainerItems();
 * 本模组移除「排除潜影盒」分支（BlockItemMixin 已让 canFitInsideContainerItems 恒 true），
 * 并追加嵌套深度校验：放入潜影盒的物品相对深度 ≤ MAX-1（≤ 4），
 * 保证自动化路径同样受 5 层上限约束。
 */
@Mixin(ShulkerBoxBlockEntity.class)
public class ShulkerBoxBlockEntityMixin {
    /**
     * @author StrongerShulkerBox
     * @reason 解除潜影盒嵌套限制并施加 5 层深度上限
     */
    @Overwrite
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return stack.getItem().canFitInsideContainerItems() && ShulkerBoxNesting.canPlaceIntoShulker(stack);
    }
}