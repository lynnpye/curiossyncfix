package com.lynn.curiossyncfix.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.lynn.curiossyncfix.ResilientSyncStackCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack;

/**
 * Wraps the {@code STREAM_CODEC} built in {@link SPacketSyncStack}'s static initializer with a
 * fault-tolerant decorator, so a malformed {@code curios:sync_stack} packet is dropped instead of
 * disconnecting the player.
 *
 * <p>The injection point is the {@code StreamCodec.composite(...)} call whose result is stored into
 * {@code STREAM_CODEC}. {@code @ModifyExpressionValue} (MixinExtras, bundled with NeoForge) receives
 * that composed codec and returns our wrapper in its place.</p>
 *
 * <p>Client-only: this only alters decode behavior, and a clientbound packet is only ever decoded on
 * the physical client. Encode (server side) is delegated unchanged.</p>
 */
@Mixin(SPacketSyncStack.class)
public class SPacketSyncStackMixin {

    @ModifyExpressionValue(
            method = "<clinit>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/codec/StreamCodec;composite("
                            + "Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;"
                            + "Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;"
                            + "Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;"
                            + "Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;"
                            + "Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;"
                            + "Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;"
                            + "Lcom/mojang/datafixers/util/Function6;)"
                            + "Lnet/minecraft/network/codec/StreamCodec;"
            )
    )
    private static StreamCodec<RegistryFriendlyByteBuf, SPacketSyncStack> curiossyncfix$harden(
            StreamCodec<RegistryFriendlyByteBuf, SPacketSyncStack> original) {
        return new ResilientSyncStackCodec(original);
    }
}
