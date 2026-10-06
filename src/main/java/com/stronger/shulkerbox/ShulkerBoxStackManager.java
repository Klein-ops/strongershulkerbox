package com.stronger.shulkerbox;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 服务端「打开栈」管理器：记录每个玩家当前打开的潜影盒链路。
 *
 * 栈底 = 顶层潜影盒（从背包打开），栈顶 = 当前正在展示的最深层潜影盒。
 * 嵌套打开时压栈；按 ESC 关闭时弹栈并重开上一层，实现「逐层返回」。
 */
public class ShulkerBoxStackManager {
    private static final Map<ServerPlayer, Deque<ShulkerBoxItemContainer>> STACKS = new HashMap<>();
    private static final Map<ServerPlayer, Boolean> SWITCHING = new HashMap<>();

    private ShulkerBoxStackManager() {
    }

    public static Deque<ShulkerBoxItemContainer> stackOf(ServerPlayer player) {
        return STACKS.computeIfAbsent(player, p -> new ArrayDeque<>());
    }

    /** 标记「正在切换菜单」（嵌套打开 / 回退），使 Close 事件不误弹栈 */
    public static void setSwitching(ServerPlayer player, boolean switching) {
        SWITCHING.put(player, switching);
    }

    public static boolean isSwitching(ServerPlayer player) {
        return Boolean.TRUE.equals(SWITCHING.get(player));
    }

    /** 压栈：打开更深一层 */
    public static void push(ServerPlayer player, ShulkerBoxItemContainer container) {
        stackOf(player).push(container);
    }

    /** 弹栈：返回上一层，返回 null 表示已到栈底（应回背包） */
    public static ShulkerBoxItemContainer pop(ServerPlayer player) {
        Deque<ShulkerBoxItemContainer> stack = stackOf(player);
        if (stack.isEmpty()) {
            return null;
        }
        stack.pop();
        return stack.peek();
    }

    /** 当前栈顶（正在展示的容器），无则 null */
    public static ShulkerBoxItemContainer peek(ServerPlayer player) {
        return stackOf(player).peek();
    }

    /** 玩家退出/断线时清理 */
    public static void clear(ServerPlayer player) {
        STACKS.remove(player);
        SWITCHING.remove(player);
    }
    /** 仅清空打开栈（不清 switching 标志）：用于「从背包重新打开」时重置链路 */
    public static void clearStack(ServerPlayer player) {
        Deque<ShulkerBoxItemContainer> stack = STACKS.get(player);
        if (stack != null) {
            stack.clear();
        }
    }
}