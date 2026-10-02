package com.lynn.curiossyncfix;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack;

/**
 * A fault-tolerant decorator around {@link SPacketSyncStack}'s network {@code StreamCodec}.
 *
 * <p>Why this exists: {@code curios:sync_stack} carries, in order,
 * {@code INT (entityId), STRING (curioId), INT (slotId), ItemStack.OPTIONAL_STREAM_CODEC (stack),
 * INT (handlerType), COMPOUND_TAG (compoundTag)}. If any data component inside the ItemStack
 * decodes to a different byte-length than it was written with, the read head is left misaligned and
 * the trailing {@code COMPOUND_TAG} read blows up with "Expected non-null compound tag" or
 * "Invalid tag id: N". Vanilla treats that decode failure as fatal and drops the connection, which
 * is the disconnect players actually see.</p>
 *
 * <p>The fix: catch the decode failure, drain the remainder of this (length-framed) custom-payload
 * buffer so the outer connection stays byte-aligned, and return a harmless sentinel packet whose
 * {@code entityId} is -1. Curios' client handler resolves the entity by id, so a -1 never matches
 * anything and the handler simply returns without touching any inventory. The affected slot is
 * re-synced by the server on the next update tick, so no state is lost.</p>
 *
 * <p>Encoding is left completely untouched: it delegates straight through. This class only changes
 * how a malformed inbound packet is handled on the receiving (client) side.</p>
 */
public final class ResilientSyncStackCodec
        implements StreamCodec<RegistryFriendlyByteBuf, SPacketSyncStack> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final StreamCodec<RegistryFriendlyByteBuf, SPacketSyncStack> delegate;

    // Lazily created so we never construct an SPacketSyncStack while its own <clinit> is still
    // running (this decorator is installed from inside that <clinit>).
    private static SPacketSyncStack sentinel;

    public ResilientSyncStackCodec(StreamCodec<RegistryFriendlyByteBuf, SPacketSyncStack> delegate) {
        this.delegate = delegate;
    }

    private static SPacketSyncStack sentinel() {
        SPacketSyncStack s = sentinel;
        if (s == null) {
            // entityId = -1 -> ClientLevel.getEntity(-1) == null -> handler no-ops.
            s = new SPacketSyncStack(-1, "", -1, ItemStack.EMPTY, 0, new CompoundTag());
            sentinel = s;
        }
        return s;
    }

    @Override
    public SPacketSyncStack decode(RegistryFriendlyByteBuf buf) {
        try {
            return delegate.decode(buf);
        } catch (Exception e) {
            // The payload is length-framed, so draining what's left keeps the connection aligned
            // and avoids the vanilla "packet was larger than expected" secondary error.
            if (buf.isReadable()) {
                buf.skipBytes(buf.readableBytes());
            }
            LOGGER.error(
                    "[CuriosSyncFix] Dropped a malformed curios:sync_stack packet to prevent a client "
                    + "disconnect. A curio's synced data component failed to decode; that slot will "
                    + "re-sync on the next update. See stack trace for the underlying cause.",
                    e);
            return sentinel();
        }
    }

    @Override
    public void encode(RegistryFriendlyByteBuf buf, SPacketSyncStack packet) {
        delegate.encode(buf, packet);
    }
}
