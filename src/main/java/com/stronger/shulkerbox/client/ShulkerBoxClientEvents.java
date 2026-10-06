package com.stronger.shulkerbox.client;

import com.stronger.shulkerbox.ShulkerBoxNesting;
import com.stronger.shulkerbox.StrongerShulkerBoxMod;
import com.stronger.shulkerbox.network.ShulkerBoxOpenPayload;
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
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端：在背包/潜影盒界面内右键潜影盒物品，请求服务端打开其 GUI。
 *
 * 判定规则：
 *  - 当前屏幕是 ShulkerBoxScreen（已在潜影盒界面内）：悬停槽位若属于
 *    潜影盒内部（槽位容器不是玩家背包），右键 → fromShulker=true，槽位号为
 *    slot.getContainerSlot()（0-26）；
 *  - 其他屏幕（背包 InventoryScreen 等）：悬停槽位若属于玩家背包/快捷栏
 *    （槽位容器是玩家背包或槽位索引 < 36），右键 → fromShulker=false，槽位号为
 *    Inventory 槽位号。
 *
 * 事件挂载：ScreenEvent.MouseButtonPressed.Pre（右键 button=1），
 * 通过 PacketDistributor.sendToServer 发给服务端。事件发在游戏总线。
 */
@EventBusSubscriber(modid = StrongerShulkerBoxMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class ShulkerBoxClientEvents {
    private ShulkerBoxClientEvents() {
    }

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
        // （ShulkerBoxMenu 中潜影盒内部槽位容器是同步的空容器，背包槽位容器是 Inventory）
        boolean fromShulker = screen instanceof ShulkerBoxScreen
                && !(slot.container instanceof Inventory);
        // 槽位号：getContainerSlot() 直接返回容器内索引
        //  潜影盒内部 = 0-26；背包/快捷栏 = 0-35
        int slotIndex = slot.getContainerSlot();

        // 阻止原版处理（避免同时触发拖拽/移动），并请求服务端打开
        event.setCanceled(true);
        PacketDistributor.sendToServer(new ShulkerBoxOpenPayload(fromShulker, slotIndex));
    }
}