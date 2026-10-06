package com.stronger.shulkerbox;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * 潜影盒嵌套深度计算与校验工具。
 *
 * 深度定义（相对深度）：一个潜影盒物品本身算第 1 层；它的 CONTAINER 组件内
 * 每再放一个潜影盒，该潜影盒的相对深度 +1。例如「A 内放 B，B 内放 C」→
 * A=1 层，B=2 层，C=3 层。此深度随物品 NBT 递归存储，无需额外标记。
 *
 * 校验规则：
 * - 放入「潜影盒容器」（潜影盒 GUI 槽 / 潜影盒方块实体）时：要求
 *   物品相对深度 ≤ MAX_NESTING_DEPTH - 1（即 ≤ 4），因为目标潜影盒自身
 *   占用最外层第 1 层，放入后链路 = 1 + 物品深度，恰好 ≤ 5。
 * - 放入「普通容器」（箱子、物品栏、投掷器等）时：要求物品相对深度 ≤ 5，
 *   因为该潜影盒成为链路最外层，其内部链路深度就是物品相对深度。
 *
 * 由于任何潜影盒物品的深度都只能通过「打开某层潜影盒再放入」来增加，
 * 而该路径被 ≤4 限制，因此任意潜影盒物品的相对深度恒 ≤ 5，
 * 嵌套 5 层的上限被完整闭合，无法通过拆装绕过。
 */
public final class ShulkerBoxNesting {
    /** 最大嵌套层数：一个潜影盒本身算第 1 层，最多嵌套 5 层（即最内层的潜影盒在第 5 层）。 */
    public static final int MAX_NESTING_DEPTH = 5;

    private ShulkerBoxNesting() {
    }

    /**
     * 计算物品栈的潜影盒相对嵌套深度。
     * 非潜影盒返回 0；潜影盒返回 1 + 其容器内物品的最大深度（递归）。
     */
    public static int depthOf(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return 0;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return 1;
        }
        int maxChildDepth = 0;
        for (ItemStack child : contents.nonEmptyItems()) {
            maxChildDepth = Math.max(maxChildDepth, depthOf(child));
        }
        return 1 + maxChildDepth;
    }

    /**
     * 校验一个物品能否放入「潜影盒容器」（潜影盒 GUI 槽位 / 潜影盒方块实体内部）。
     * 潜影盒物品要求相对深度 ≤ MAX-1，保证放入后链路（含目标潜影盒自身）≤ MAX。
     * 非潜影盒物品始终允许。
     */
    public static boolean canPlaceIntoShulker(ItemStack stack) {
        if (stack.isEmpty() || !isShulkerBoxItem(stack)) {
            return true;
        }
        return depthOf(stack) <= MAX_NESTING_DEPTH - 1;
    }

    /**
     * 校验一个物品能否放入「普通容器」（箱子、物品栏、投掷器等）。
     * 潜影盒物品要求相对深度 ≤ MAX（其自身将成为链路最外层）。
     * 非潜影盒物品始终允许。
     */
    public static boolean canPlaceIntoNormalContainer(ItemStack stack) {
        if (stack.isEmpty() || !isShulkerBoxItem(stack)) {
            return true;
        }
        return depthOf(stack) <= MAX_NESTING_DEPTH;
    }

    /**
     * 判断物品是否为潜影盒（潜影盒物品项）。
     */
    public static boolean isShulkerBoxItem(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }
}