package com.stronger.shulkerbox.client;

import com.stronger.shulkerbox.ShulkerBoxItemContainer;
import com.stronger.shulkerbox.ShulkerBoxNesting;
import com.stronger.shulkerbox.StrongerShulkerBoxMod;
import com.stronger.shulkerbox.network.ShulkerBoxOpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端：在背包/潜影盒界面内右键潜影盒物品，请求服务端打开其 GUI。
 *
 * v1.2.2：客户端本地登记「正在打开的潜影盒」，使客户端的
 * Slot#mayPickup / #mayPlace 与服务端一致地锁死该盒子，
 * 避免服务端拒绝、客户端却已预测移动造成的状态漂移（假物品/回弹）。
 *
 * 标记时序（关键）：
 *  - 发出顶层打开请求时登记该盒子，并进入「等待潜影盒界面」状态；
 *  - 等待期间（服务端往返，通常 1~2 tick）保留标记，避免空档被误清；
 *  - 进入潜影盒界面后结束等待；离开界面后再短暂观察几 tick（嵌套逐层返回会
 *    重新进入潜影盒界面，此时应保留标记），否则清空标记解锁。
 */
@EventBusSubscriber(modid = StrongerShulkerBoxMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class ShulkerBoxClientEvents {
    private ShulkerBoxClientEvents() {
    }

    /** 已发出打开请求、等待潜影盒界面出现 */
    private static boolean awaitingScreen = false;
    private static int awaitingTicks = 0;
    /** 刚离开潜影盒界面，短暂观察是否为「嵌套逐层返回」 */
    private static boolean bridging = false;
    private static int bridgingTicks = 0;
    private static boolean wasInShulker = false;

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        // 仅处理右键（GLFW 鼠标按钮 1）
        if (event.getButton() != 1) {
            return;
        }
        Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        // 仅在背包界面或潜影盒界面内拦截；其他容器（箱子等）不干扰原版行为
        boolean isInventoryScreen = screen instanceof InventoryScreen;
        boolean isShulkerScreen = screen instanceof ShulkerBoxScreen;
        if (!isInventoryScreen && !isShulkerScreen) {
            return;
        }
        Slot slot = containerScreen.getSlotUnderMouse();
        if (slot == null) {
            return;
        }
        ItemStack stack = slot.getItem();
        if (!ShulkerBoxNesting.isShulkerBoxItem(stack)) {
            return; // 悬停的不是潜影盒
        }
        // 判定来源：是否在潜影盒界面内且悬停的是潜影盒内部槽位
        boolean fromShulker = screen instanceof ShulkerBoxScreen
                && !(slot.container instanceof Inventory);
        int slotIndex = slot.getContainerSlot();

        // 客户端本地登记：顶层打开时重置链路并登记源物品
        if (!fromShulker) {
            ShulkerBoxItemContainer.clearOpenClient();
            ShulkerBoxItemContainer.markOpenClient(stack);
        }
        awaitingScreen = true;
        awaitingTicks = 0;
        bridging = false;
        bridgingTicks = 0;

        // 阻止原版处理（避免同时触发拖拽/移动），并请求服务端打开
        event.setCanceled(true);
        PacketDistributor.sendToServer(new ShulkerBoxOpenPayload(fromShulker, slotIndex));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean inShulker = mc.screen instanceof ShulkerBoxScreen;

        if (inShulker) {
            wasInShulker = true;
            awaitingScreen = false;
            awaitingTicks = 0;
            bridging = false;
            bridgingTicks = 0;
            return;
        }

        if (wasInShulker) {
            // 刚从潜影盒界面离开：可能是嵌套逐层返回，先观察几 tick 再决定是否解锁
            wasInShulker = false;
            awaitingScreen = false;
            awaitingTicks = 0;
            bridging = true;
            bridgingTicks = 0;
            return;
        }

        if (awaitingScreen) {
            // 等待服务端打开界面期间保留标记；超时（界面迟迟不来）则放弃并解锁
            if (++awaitingTicks > 60) {
                awaitingScreen = false;
                awaitingTicks = 0;
                ShulkerBoxItemContainer.clearOpenClient();
            }
            return;
        }

        if (bridging) {
            if (++bridgingTicks > 5) {
                bridging = false;
                bridgingTicks = 0;
                ShulkerBoxItemContainer.clearOpenClient();
            }
            return;
        }

        // 不在潜影盒界面：解锁
        ShulkerBoxItemContainer.clearOpenClient();
    }
}