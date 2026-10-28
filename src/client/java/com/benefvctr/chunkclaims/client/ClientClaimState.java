package com.benefvctr.chunkclaims.client;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.benefvctr.chunkclaims.core.ClaimPos;
import com.benefvctr.chunkclaims.net.ClaimStatusPayload;
import com.benefvctr.chunkclaims.net.ClaimSyncPayload;

/**
 * The client's copy of the claim map for the current dimension, plus the player's
 * own economy status and the edit-mode toggle. Touched only on the client thread
 * (payload handlers, ticks and rendering all run there).
 *
 * <p>Everything here is for display. The server re-checks every action, so a
 * stale or wrong client map can only ever paint the wrong colour.
 */
public final class ClientClaimState {
	private static String dimension = "";
	private static final Map<Long, UUID> owners = new HashMap<>();
	private static final Map<UUID, String> names = new HashMap<>();
	private static ClaimStatusPayload status;
	private static boolean editMode;
	/** Bumped on every claim change so the overlay knows to rebuild. */
	private static int version;

	private ClientClaimState() {
	}

	static void apply(ClaimSyncPayload sync) {
		if (sync.reset()) {
			owners.clear();
			dimension = sync.dimension();
		} else if (!sync.dimension().equals(dimension)) {
			return; // a delta for a dimension we have already left
		}
		for (ClaimSyncPayload.Owner owner : sync.owners()) {
			names.put(owner.id(), owner.name());
		}
		for (ClaimSyncPayload.Entry entry : sync.added()) {
			owners.put(ClaimPos.pack(entry.x(), entry.z()), sync.owners().get(entry.owner()).id());
		}
		for (long packed : sync.removed()) {
			owners.remove(packed);
		}
		version++;
	}

	static void apply(ClaimStatusPayload newStatus) {
		status = newStatus;
		version++;
	}

	static void reset() {
		owners.clear();
		names.clear();
		dimension = "";
		status = null;
		editMode = false;
		version++;
	}

	public static boolean editMode() {
		return editMode;
	}

	static boolean toggleEditMode() {
		editMode = !editMode;
		version++;
		return editMode;
	}

	static int version() {
		return version;
	}

	/** Null until the server has sent anything, e.g. on a server without the mod. */
	static ClaimStatusPayload status() {
		return status;
	}

	public static UUID ownerAt(int chunkX, int chunkZ) {
		return owners.get(ClaimPos.pack(chunkX, chunkZ));
	}

	public static String nameOf(UUID id) {
		return names.getOrDefault(id, "?");
	}

	static boolean dimensionClaimable() {
		return status != null && (status.claimableDimensions().isEmpty() || status.claimableDimensions().contains(dimension));
	}

	/**
	 * Owner of the nearest-found foreign claim inside the buffer around an
	 * unowned chunk, or null when the chunk is clear (or owned).
	 */
	static UUID blockingOwner(int chunkX, int chunkZ, UUID self) {
		if (status == null || ownerAt(chunkX, chunkZ) != null) {
			return null;
		}
		int buffer = status.buffer();
		for (int dx = -buffer; dx <= buffer; dx++) {
			for (int dz = -buffer; dz <= buffer; dz++) {
				UUID near = ownerAt(chunkX + dx, chunkZ + dz);
				if (near != null && !near.equals(self)) {
					return near;
				}
			}
		}
		return null;
	}

	/** Size of the same-owner, edge-connected group around a chunk (capped so huge bases stay cheap). */
	static int clusterSize(int chunkX, int chunkZ) {
		UUID owner = ownerAt(chunkX, chunkZ);
		if (owner == null) {
			return 0;
		}
		Set<Long> seen = new HashSet<>();
		ArrayDeque<long[]> queue = new ArrayDeque<>();
		seen.add(ClaimPos.pack(chunkX, chunkZ));
		queue.add(new long[] {chunkX, chunkZ});
		while (!queue.isEmpty() && seen.size() < 10_000) {
			long[] c = queue.poll();
			int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
			for (int[] s : steps) {
				int nx = (int) c[0] + s[0];
				int nz = (int) c[1] + s[1];
				if (owner.equals(ownerAt(nx, nz)) && seen.add(ClaimPos.pack(nx, nz))) {
					queue.add(new long[] {nx, nz});
				}
			}
		}
		return seen.size();
	}
}
