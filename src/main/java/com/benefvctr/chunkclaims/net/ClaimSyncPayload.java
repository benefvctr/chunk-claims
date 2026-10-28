package com.benefvctr.chunkclaims.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.benefvctr.chunkclaims.ChunkClaims;

/**
 * Server to client: claims in the player's current dimension.
 *
 * <p>Used both for full syncs ({@code reset = true} on the first batch, which tells
 * the client to forget everything it had) and for deltas after a claim or unclaim.
 * Owners are sent once in a table and referenced by index to keep big syncs small.
 */
public record ClaimSyncPayload(String dimension, boolean reset, List<Owner> owners, List<Entry> added, List<Long> removed)
		implements CustomPacketPayload {

	public static final Type<ClaimSyncPayload> TYPE = new Type<>(ChunkClaims.id("sync"));
	public static final StreamCodec<FriendlyByteBuf, ClaimSyncPayload> CODEC = StreamCodec.ofMember(ClaimSyncPayload::write, ClaimSyncPayload::read);

	/** Batch size for full syncs, keeps every packet far below the 1 MiB payload limit. */
	public static final int MAX_ENTRIES = 4096;

	public record Owner(UUID id, String name) {
	}

	/** A claimed chunk; {@code owner} indexes into {@link #owners()}. */
	public record Entry(int x, int z, int owner) {
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(dimension);
		buf.writeBoolean(reset);
		buf.writeVarInt(owners.size());
		for (Owner o : owners) {
			buf.writeUUID(o.id());
			buf.writeUtf(o.name(), 64);
		}
		buf.writeVarInt(added.size());
		for (Entry e : added) {
			buf.writeVarInt(e.x());
			buf.writeVarInt(e.z());
			buf.writeVarInt(e.owner());
		}
		buf.writeVarInt(removed.size());
		for (long packed : removed) {
			buf.writeLong(packed);
		}
	}

	private static ClaimSyncPayload read(FriendlyByteBuf buf) {
		String dimension = buf.readUtf();
		boolean reset = buf.readBoolean();
		int ownerCount = buf.readVarInt();
		List<Owner> owners = new ArrayList<>(ownerCount);
		for (int i = 0; i < ownerCount; i++) {
			owners.add(new Owner(buf.readUUID(), buf.readUtf(64)));
		}
		int addedCount = buf.readVarInt();
		List<Entry> added = new ArrayList<>(Math.min(addedCount, MAX_ENTRIES));
		for (int i = 0; i < addedCount; i++) {
			int x = buf.readVarInt();
			int z = buf.readVarInt();
			int owner = buf.readVarInt();
			if (owner < 0 || owner >= ownerCount) {
				throw new IllegalArgumentException("claim sync references unknown owner " + owner);
			}
			added.add(new Entry(x, z, owner));
		}
		int removedCount = buf.readVarInt();
		List<Long> removed = new ArrayList<>(Math.min(removedCount, MAX_ENTRIES));
		for (int i = 0; i < removedCount; i++) {
			removed.add(buf.readLong());
		}
		return new ClaimSyncPayload(dimension, reset, owners, added, removed);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
