package com.benefvctr.chunkclaims.server;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Server-side chat/action-bar text. Every message carries English fallback text,
 * so players without the client mod (who have no lang file for these keys) still
 * read a proper sentence instead of a raw translation key.
 */
final class Messages {
	private Messages() {
	}

	private static MutableComponent msg(String key, String fallback, Object... args) {
		return Component.translatableWithFallback("chunkclaims.msg." + key, fallback, args);
	}

	static MutableComponent dimensionDisabled() {
		return msg("dimension_disabled", "Claiming is disabled in this dimension.");
	}

	static MutableComponent cannotAfford(Component cost, Component missing) {
		return msg("cannot_afford", "This chunk costs %s. You still need %s.", cost, missing);
	}

	static MutableComponent claimed(int x, int z, Component paid) {
		return msg("claimed", "Claimed chunk %s, %s for %s.", x, z, paid);
	}

	static MutableComponent free() {
		return msg("free", "free (creative)");
	}

	static MutableComponent alreadyYours() {
		return msg("already_yours", "You already own this chunk.");
	}

	static MutableComponent taken(String owner) {
		return msg("taken", "This chunk belongs to %s.", owner);
	}

	static MutableComponent tooClose(String owner, int buffer) {
		return msg("too_close", "Too close to %s's land. Keep %s free between territories.", owner, chunks(buffer));
	}

	static MutableComponent unclaimed(int x, int z, Component refund) {
		return msg("unclaimed", "Unclaimed chunk %s, %s. Refunded %s.", x, z, refund);
	}

	static MutableComponent nothing() {
		return msg("nothing", "nothing");
	}

	static MutableComponent notYours() {
		return msg("not_yours", "That chunk is not yours.");
	}

	static MutableComponent standInOwn() {
		return msg("stand_in_own", "Stand inside one of your own claims to edit its permissions.");
	}

	static MutableComponent trusted(String name, int chunks) {
		return msg("trusted", "%s can now build in this area (%s).", name, chunks(chunks));
	}

	static MutableComponent untrusted(String name, int chunks) {
		return msg("untrusted", "%s can no longer build in this area (%s).", name, chunks(chunks));
	}

	/** "1 chunk" / "5 chunks". Server text is English-only (it is the fallback). */
	private static String chunks(int count) {
		return count + (count == 1 ? " chunk" : " chunks");
	}

	static MutableComponent protectedLand(String owner) {
		return msg("protected", "This land belongs to %s.", owner);
	}
}
