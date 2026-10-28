package com.benefvctr.chunkclaims.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Every claimed chunk on the server, plus the rules for adding and removing them.
 *
 * <p>Thread safety: all state sits behind one read-write lock. Game logic runs on
 * the server thread, but the autosave thread takes snapshots concurrently and
 * protection hooks can be reached from worker threads, so every public method
 * locks. Methods never call out to Minecraft while holding the lock.
 */
public final class ClaimRegistry {

	public enum Placement {
		OK,
		/** The chunk is already yours. */
		ALREADY_YOURS,
		/** Someone else owns the chunk. */
		TAKEN,
		/** Another player's land is within the buffer distance. */
		TOO_CLOSE
	}

	/** Outcome of a placement check; {@code blockingOwner} is set for TAKEN and TOO_CLOSE. */
	public record PlacementCheck(Placement placement, UUID blockingOwner) {
		static final PlacementCheck OK = new PlacementCheck(Placement.OK, null);
	}

	private final ReadWriteLock lock = new ReentrantReadWriteLock();
	private final Map<ClaimPos, Claim> claims = new HashMap<>();
	/** Owner index so chunk counts (and so prices) are O(1). */
	private final Map<UUID, Set<ClaimPos>> byOwner = new HashMap<>();

	public Claim get(ClaimPos pos) {
		lock.readLock().lock();
		try {
			return claims.get(pos);
		} finally {
			lock.readLock().unlock();
		}
	}

	/** True when the chunk is unclaimed or the player is its owner or trusted there. */
	public boolean isAllowed(ClaimPos pos, UUID player) {
		Claim claim = get(pos);
		return claim == null || claim.allows(player);
	}

	public int countOwned(UUID owner) {
		lock.readLock().lock();
		try {
			Set<ClaimPos> owned = byOwner.get(owner);
			return owned == null ? 0 : owned.size();
		} finally {
			lock.readLock().unlock();
		}
	}

	/** Can {@code player} claim {@code pos}? Does not look at payment or allowed dimensions. */
	public PlacementCheck check(ClaimPos pos, UUID player, int buffer) {
		lock.readLock().lock();
		try {
			return checkLocked(pos, player, buffer);
		} finally {
			lock.readLock().unlock();
		}
	}

	private PlacementCheck checkLocked(ClaimPos pos, UUID player, int buffer) {
		Claim existing = claims.get(pos);
		if (existing != null) {
			return existing.owner().equals(player)
					? new PlacementCheck(Placement.ALREADY_YOURS, player)
					: new PlacementCheck(Placement.TAKEN, existing.owner());
		}
		for (int dx = -buffer; dx <= buffer; dx++) {
			for (int dz = -buffer; dz <= buffer; dz++) {
				Claim near = claims.get(pos.offset(dx, dz));
				if (near != null && !near.owner().equals(player)) {
					return new PlacementCheck(Placement.TOO_CLOSE, near.owner());
				}
			}
		}
		return PlacementCheck.OK;
	}

	/**
	 * Claims {@code pos} for {@code owner} if the placement rules allow it. The check
	 * and the insert happen under one write lock, so two players can never both win
	 * the same chunk or end up inside each other's buffer.
	 *
	 * <p>Trust for the new chunk: nobody when it stands alone, the cluster's list when
	 * it extends one cluster, and the intersection when it bridges several (only
	 * people trusted on every side keep access to the merged land).
	 */
	public PlacementCheck claim(ClaimPos pos, UUID owner, int buffer) {
		lock.writeLock().lock();
		try {
			PlacementCheck check = checkLocked(pos, owner, buffer);
			if (check.placement() != Placement.OK) {
				return check;
			}

			Set<UUID> inherited = null;
			boolean mixed = false;
			for (ClaimPos n : pos.neighbours()) {
				Claim neighbour = claims.get(n);
				if (neighbour == null || !neighbour.owner().equals(owner)) {
					continue;
				}
				if (inherited == null) {
					inherited = new HashSet<>(neighbour.trusted());
				} else if (!inherited.equals(neighbour.trusted())) {
					inherited.retainAll(neighbour.trusted());
					mixed = true;
				}
			}

			Claim created = new Claim(owner, inherited == null ? Set.of() : inherited);
			claims.put(pos, created);
			byOwner.computeIfAbsent(owner, k -> new HashSet<>()).add(pos);

			if (mixed) {
				// Bring the whole merged cluster onto the intersected list.
				for (ClaimPos member : clusterLocked(pos)) {
					claims.put(member, claims.get(member).withTrusted(created.trusted()));
				}
			}
			return check;
		} finally {
			lock.writeLock().unlock();
		}
	}

	/** Removes the claim if {@code owner} owns it. A split cluster keeps its trust on both sides. */
	public boolean unclaim(ClaimPos pos, UUID owner) {
		lock.writeLock().lock();
		try {
			Claim existing = claims.get(pos);
			if (existing == null || !existing.owner().equals(owner)) {
				return false;
			}
			claims.remove(pos);
			Set<ClaimPos> owned = byOwner.get(owner);
			owned.remove(pos);
			if (owned.isEmpty()) {
				byOwner.remove(owner);
			}
			return true;
		} finally {
			lock.writeLock().unlock();
		}
	}

	/** The contiguous same-owner group containing {@code pos}, or empty if unclaimed. */
	public Set<ClaimPos> cluster(ClaimPos pos) {
		lock.readLock().lock();
		try {
			return clusterLocked(pos);
		} finally {
			lock.readLock().unlock();
		}
	}

	private Set<ClaimPos> clusterLocked(ClaimPos start) {
		Claim origin = claims.get(start);
		if (origin == null) {
			return Set.of();
		}
		Set<ClaimPos> seen = new LinkedHashSet<>();
		ArrayDeque<ClaimPos> queue = new ArrayDeque<>();
		seen.add(start);
		queue.add(start);
		while (!queue.isEmpty()) {
			for (ClaimPos n : queue.poll().neighbours()) {
				Claim c = claims.get(n);
				if (c != null && c.owner().equals(origin.owner()) && seen.add(n)) {
					queue.add(n);
				}
			}
		}
		return seen;
	}

	/**
	 * Grants or revokes {@code target}'s access to the whole cluster at {@code anyChunk}.
	 *
	 * @return the number of chunks changed, 0 if {@code owner} does not own that chunk
	 */
	public int setTrusted(ClaimPos anyChunk, UUID owner, UUID target, boolean allowed) {
		lock.writeLock().lock();
		try {
			Claim origin = claims.get(anyChunk);
			if (origin == null || !origin.owner().equals(owner) || owner.equals(target)) {
				return 0;
			}
			Set<UUID> updated = new HashSet<>(origin.trusted());
			if (allowed) {
				updated.add(target);
			} else {
				updated.remove(target);
			}
			Set<ClaimPos> members = clusterLocked(anyChunk);
			for (ClaimPos member : members) {
				claims.put(member, claims.get(member).withTrusted(updated));
			}
			return members.size();
		} finally {
			lock.writeLock().unlock();
		}
	}

	/** Copy of every claim in one dimension, keyed by packed chunk x/z. */
	public Map<Long, Claim> claimsIn(String dimension) {
		lock.readLock().lock();
		try {
			Map<Long, Claim> out = new HashMap<>();
			claims.forEach((pos, claim) -> {
				if (pos.dimension().equals(dimension)) {
					out.put(pos.packed(), claim);
				}
			});
			return out;
		} finally {
			lock.readLock().unlock();
		}
	}

	/** Point-in-time copy for saving. Claims are immutable, so a shallow map copy is enough. */
	public Map<ClaimPos, Claim> snapshot() {
		lock.readLock().lock();
		try {
			return new HashMap<>(claims);
		} finally {
			lock.readLock().unlock();
		}
	}

	/** Replaces all claims, used when loading from disk. */
	public void restore(Map<ClaimPos, Claim> loaded) {
		lock.writeLock().lock();
		try {
			claims.clear();
			byOwner.clear();
			loaded.forEach((pos, claim) -> {
				claims.put(pos, claim);
				byOwner.computeIfAbsent(claim.owner(), k -> new HashSet<>()).add(pos);
			});
		} finally {
			lock.writeLock().unlock();
		}
	}
}
