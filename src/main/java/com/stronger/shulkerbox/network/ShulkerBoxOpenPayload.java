package com.stronger.shulkerbox.network;

import com.stronger.shulkerbox.StrongerShulkerBoxMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：请求打开一个潜影盒 GUI。
 *
 * @param fromShulker true  = 目标在已打开的潜影盒界面内部（嵌套打开），
 *                    slotIndex 是潜影盒内部槽位（0-26）
 *                    false = 目标在背包/快捷栏（顶层打开），
 *                    slotIndex 是 Inventory 槽位号（0-35）
 */
public record ShulkerBoxOpenPayload(boolean fromShulker, int slotIndex) implements CustomPacketPayload {
    public static final Type<ShulkerBoxOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StrongerShulkerBoxMod.MODID, "open_shulker"));

    public static final StreamCodec<ByteBuf, ShulkerBoxOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ShulkerBoxOpenPayload::fromShulker,
                    ByteBufCodecs.VAR_INT, ShulkerBoxOpenPayload::slotIndex,
                    ShulkerBoxOpenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}