package com.benefvctr.chunkclaims.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.benefvctr.chunkclaims.ChunkClaims;
import com.benefvctr.chunkclaims.core.ItemCost;

/**
 * Server to client: the player's own economy state, used by the edit-mode HUD and
 * to colour chunks with the server's buffer and dimension rules.
 *
 * @param owned               chunks the player owns, all dimensions
 * @param nextCost            price of the next claim
 * @param refund              what unclaiming one chunk gives back right now
 * @param buffer              required free chunks between territories
 * @param claimableDimensions dimensions where claiming works; empty means all
 */
public record ClaimStatusPayload(int owned, List<ItemCost> nextCost, List<ItemCost> refund, int buffer, List<String> claimableDimensions)
		implements CustomPacketPayload {

	public static final Type<ClaimStatusPayload> TYPE = new Type<>(ChunkClaims.id("status"));
	public static final StreamCodec<FriendlyByteBuf, ClaimStatusPayload> CODEC = StreamCodec.ofMember(ClaimStatusPayload::write, ClaimStatusPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(owned);
		writeCosts(buf, nextCost);
		writeCosts(buf, refund);
		buf.writeVarInt(buffer);
		buf.writeVarInt(claimableDimensions.size());
		claimableDimensions.forEach(buf::writeUtf);
	}

	private static ClaimStatusPayload read(FriendlyByteBuf buf) {
		int owned = buf.readVarInt();
		List<ItemCost> next = readCosts(buf);
		List<ItemCost> refund = readCosts(buf);
		int buffer = buf.readVarInt();
		int dims = buf.readVarInt();
		List<String> claimable = new ArrayList<>(Math.min(dims, 64));
		for (int i = 0; i < dims; i++) {
			claimable.add(buf.readUtf());
		}
		return new ClaimStatusPayload(owned, next, refund, buffer, claimable);
	}

	private static void writeCosts(FriendlyByteBuf buf, List<ItemCost> costs) {
		buf.writeVarInt(costs.size());
		for (ItemCost c : costs) {
			buf.writeUtf(c.item());
			buf.writeVarInt(c.count());
		}
	}

	private static List<ItemCost> readCosts(FriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<ItemCost> out = new ArrayList<>(Math.min(n, 64));
		for (int i = 0; i < n; i++) {
			out.add(new ItemCost(buf.readUtf(), buf.readVarInt()));
		}
		return out;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
