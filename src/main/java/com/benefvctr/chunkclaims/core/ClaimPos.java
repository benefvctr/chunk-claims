package com.benefvctr.chunkclaims.core;

/**
 * A chunk column in a specific dimension. Pure value type with no Minecraft
 * dependencies so the claim logic can be unit tested without a game instance.
 *
 * @param dimension dimension id, e.g. {@code minecraft:overworld}
 * @param x         chunk x (block x >> 4)
 * @param z         chunk z (block z >> 4)
 */
public record ClaimPos(String dimension, int x, int z) {

	public ClaimPos offset(int dx, int dz) {
		return new ClaimPos(dimension, x + dx, z + dz);
	}

	/** The four edge-sharing neighbours. Clusters are built from these, never diagonals. */
	public ClaimPos[] neighbours() {
		return new ClaimPos[] {offset(1, 0), offset(-1, 0), offset(0, 1), offset(0, -1)};
	}

	/** Square ("king move") distance, so diagonal chunks count toward the buffer. */
	public int chebyshevDistance(ClaimPos other) {
		return Math.max(Math.abs(x - other.x), Math.abs(z - other.z));
	}

	/** Packs x/z into one long, matching vanilla's ChunkPos packing (x low, z high). */
	public long packed() {
		return pack(x, z);
	}

	public static long pack(int x, int z) {
		return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
	}

	public static int unpackX(long packed) {
		return (int) packed;
	}

	public static int unpackZ(long packed) {
		return (int) (packed >>> 32);
	}
}
