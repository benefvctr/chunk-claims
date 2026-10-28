package com.benefvctr.chunkclaims.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.benefvctr.chunkclaims.ChunkClaims;

/**
 * Client to server: an edit-mode hotkey was pressed. Carries no coordinates on
 * purpose; the server always acts on the chunk the player is really standing in.
 */
public record ClaimActionPayload(Action action) implements CustomPacketPayload {

	public enum Action {
		/** Buy the current chunk, or unclaim it if it is already yours. */
		TOGGLE_CLAIM,
		/** Send me the permissions menu for the cluster I stand in. */
		OPEN_PERMISSIONS
	}

	public static final Type<ClaimActionPayload> TYPE = new Type<>(ChunkClaims.id("action"));
	public static final StreamCodec<FriendlyByteBuf, ClaimActionPayload> CODEC = StreamCodec.ofMember(
			(payload, buf) -> buf.writeEnum(payload.action()),
			buf -> new ClaimActionPayload(buf.readEnum(Action.class)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
