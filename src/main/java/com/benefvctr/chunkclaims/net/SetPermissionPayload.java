package com.benefvctr.chunkclaims.net;

import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.benefvctr.chunkclaims.ChunkClaims;

/**
 * Client to server: allow or deny {@code target} in the cluster containing the
 * anchor chunk. The server re-checks that the sender owns that chunk.
 */
public record SetPermissionPayload(String dimension, int anchorX, int anchorZ, UUID target, boolean allowed)
		implements CustomPacketPayload {

	public static final Type<SetPermissionPayload> TYPE = new Type<>(ChunkClaims.id("set_permission"));
	public static final StreamCodec<FriendlyByteBuf, SetPermissionPayload> CODEC = StreamCodec.ofMember(SetPermissionPayload::write, SetPermissionPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(dimension);
		buf.writeVarInt(anchorX);
		buf.writeVarInt(anchorZ);
		buf.writeUUID(target);
		buf.writeBoolean(allowed);
	}

	private static SetPermissionPayload read(FriendlyByteBuf buf) {
		return new SetPermissionPayload(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readUUID(), buf.readBoolean());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
