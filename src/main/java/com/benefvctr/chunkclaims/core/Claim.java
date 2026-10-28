package com.benefvctr.chunkclaims.core;

import java.util.Set;
import java.util.UUID;

/**
 * One claimed chunk. Immutable: changing trust produces a new Claim.
 *
 * <p>Trust is stored per chunk, but {@link ClaimRegistry} keeps every chunk of a
 * cluster on the same set, so "the cluster's permissions" is simply the set on
 * any of its chunks.
 */
public record Claim(UUID owner, Set<UUID> trusted) {

	public Claim {
		trusted = Set.copyOf(trusted);
	}

	public static Claim of(UUID owner) {
		return new Claim(owner, Set.of());
	}

	/** Owner or trusted: may build, break, open containers and interact. */
	public boolean allows(UUID player) {
		return owner.equals(player) || trusted.contains(player);
	}

	public Claim withTrusted(Set<UUID> newTrusted) {
		return new Claim(owner, newTrusted);
	}
}
