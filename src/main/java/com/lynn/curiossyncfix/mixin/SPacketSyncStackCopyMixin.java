package com.lynn.curiossyncfix.mixin;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack;

/**
 * ROOT-CAUSE FIX.
 *
 * <p>{@code CuriosEventHandler.syncCurios(...)} builds an {@link SPacketSyncStack} holding the
 * <em>live</em> {@code ItemStack} reference from the curio slot (verified in bytecode: the stack
 * argument is passed straight into the record constructor with no {@code .copy()}). The packet is
 * then handed to {@code PacketDistributor.sendToPlayersTrackingEntityAndSelf(...)}, which serializes
 * the payload <em>asynchronously on a Netty thread</em> — while the server thread keeps ticking and
 * mutating that stack's data components (a sealed weapon's summon list, a relic's XP, a Mekanism
 * curio's energy, etc.).</p>
 *
 * <p>If a component is mutated part-way through serialization, the encoded bytes become internally
 * inconsistent (a length prefix that no longer matches the bytes that follow). The client then
 * decodes a misaligned stream and the very next field — the packet's trailing {@code COMPOUND_TAG} —
 * blows up with {@code Invalid tag id: N} / {@code Expected non-null compound tag}, and vanilla drops
 * the connection. That is the disconnect, and it is why it reproduces across unrelated modpacks with
 * no component mod in common (see Curios issue #635).</p>
 *
 * <p>The fix: take an immutable snapshot with {@link ItemStack#copy()} at construction time, on the
 * server thread, before the packet is queued. The async encoder then reads a private copy that the
 * server thread can no longer mutate. This is the same pattern vanilla uses for container slot sync.</p>
 *
 * <p>Placed in the common (both-sides) mixin list so it also applies on dedicated servers, where the
 * packet is built. On the client-decode path the extra copy is harmless.</p>
 */
@Mixin(SPacketSyncStack.class)
public class SPacketSyncStackCopyMixin {

    // MUST be static: @ModifyVariable at HEAD of a record's canonical constructor injects *before*
    // the implicit super() call, and Mixin requires handlers in that position to be static (because
    // `this` is not yet initialized). Copying the arg needs no instance state anyway.
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static ItemStack curiossyncfix$snapshot(ItemStack stack) {
        return (stack == null || stack.isEmpty()) ? stack : stack.copy();
    }
}
