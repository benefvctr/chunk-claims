package com.benefvctr.chunkclaims.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ClaimRegistryTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final int BUFFER = 2;

	private final UUID alice = UUID.randomUUID();
	private final UUID bob = UUID.randomUUID();
	private final UUID carol = UUID.randomUUID();
	private final UUID dave = UUID.randomUUID();
	private final ClaimRegistry registry = new ClaimRegistry();

	private static ClaimPos at(int x, int z) {
		return new ClaimPos(OVERWORLD, x, z);
	}

	private void claim(UUID owner, int x, int z) {
		assertEquals(ClaimRegistry.Placement.OK, registry.claim(at(x, z), owner, BUFFER).placement());
	}

	@Test
	void claimingAnEmptyChunkSucceeds() {
		claim(alice, 0, 0);
		assertEquals(alice, registry.get(at(0, 0)).owner());
		assertEquals(1, registry.countOwned(alice));
	}

	@Test
	void cannotClaimTwiceOrTakeSomeoneElsesChunk() {
		claim(alice, 0, 0);
		assertEquals(ClaimRegistry.Placement.ALREADY_YOURS, registry.claim(at(0, 0), alice, BUFFER).placement());
		assertEquals(ClaimRegistry.Placement.TAKEN, registry.claim(at(0, 0), bob, BUFFER).placement());
		assertEquals(1, registry.countOwned(alice));
		assertEquals(0, registry.countOwned(bob));
	}

	@Test
	void bufferKeepsTwoFreeChunksBetweenPlayersIncludingDiagonals() {
		claim(alice, 0, 0);
		ClaimRegistry.PlacementCheck tooClose = registry.check(at(2, 0), bob, BUFFER);
		assertEquals(ClaimRegistry.Placement.TOO_CLOSE, tooClose.placement());
		assertEquals(alice, tooClose.blockingOwner());
		assertEquals(ClaimRegistry.Placement.TOO_CLOSE, registry.check(at(2, 2), bob, BUFFER).placement());
		assertEquals(ClaimRegistry.Placement.TOO_CLOSE, registry.check(at(-1, 2), bob, BUFFER).placement());
		assertEquals(ClaimRegistry.Placement.OK, registry.check(at(3, 0), bob, BUFFER).placement());
		assertEquals(ClaimRegistry.Placement.OK, registry.check(at(3, -3), bob, BUFFER).placement());
	}

	@Test
	void ownClaimsNeverBlockYouAndOtherDimensionsAreIndependent() {
		claim(alice, 0, 0);
		assertEquals(ClaimRegistry.Placement.OK, registry.check(at(1, 1), alice, BUFFER).placement());
		assertEquals(ClaimRegistry.Placement.OK, registry.check(new ClaimPos(NETHER, 1, 0), bob, BUFFER).placement());
	}

	@Test
	void countIsSummedAcrossDimensions() {
		claim(alice, 0, 0);
		registry.claim(new ClaimPos(NETHER, 0, 0), alice, BUFFER);
		assertEquals(2, registry.countOwned(alice));
	}

	@Test
	void onlyTheOwnerCanUnclaim() {
		claim(alice, 0, 0);
		assertFalse(registry.unclaim(at(0, 0), bob));
		assertTrue(registry.unclaim(at(0, 0), alice));
		assertNull(registry.get(at(0, 0)));
		assertEquals(0, registry.countOwned(alice));
		assertFalse(registry.unclaim(at(0, 0), alice));
	}

	@Test
	void clustersFollowEdgesNotCorners() {
		claim(alice, 0, 0);
		claim(alice, 1, 0);
		claim(alice, 2, 1); // touches (1,0) only at a corner
		assertEquals(Set.of(at(0, 0), at(1, 0)), registry.cluster(at(0, 0)));
		assertEquals(Set.of(at(2, 1)), registry.cluster(at(2, 1)));
		assertTrue(registry.cluster(at(9, 9)).isEmpty());
	}

	@Test
	void trustAppliesToTheWholeClusterAndNothingElse() {
		claim(alice, 0, 0);
		claim(alice, 1, 0);
		claim(alice, 5, 5);
		assertEquals(2, registry.setTrusted(at(1, 0), alice, bob, true));
		assertTrue(registry.isAllowed(at(0, 0), bob));
		assertTrue(registry.isAllowed(at(1, 0), bob));
		assertFalse(registry.isAllowed(at(5, 5), bob));

		registry.setTrusted(at(0, 0), alice, bob, false);
		assertFalse(registry.isAllowed(at(1, 0), bob));
	}

	@Test
	void onlyTheOwnerCanChangeTrust() {
		claim(alice, 0, 0);
		assertEquals(0, registry.setTrusted(at(0, 0), bob, carol, true));
		assertFalse(registry.isAllowed(at(0, 0), carol));
	}

	@Test
	void unclaimedLandIsOpenToEveryone() {
		assertTrue(registry.isAllowed(at(0, 0), bob));
	}

	@Test
	void newChunkInheritsTheClusterItJoins() {
		claim(alice, 0, 0);
		registry.setTrusted(at(0, 0), alice, bob, true);
		claim(alice, 0, 1);
		assertTrue(registry.isAllowed(at(0, 1), bob));
	}

	@Test
	void isolatedNewChunkTrustsNobody() {
		claim(alice, 0, 0);
		registry.setTrusted(at(0, 0), alice, bob, true);
		claim(alice, 0, 2);
		assertFalse(registry.isAllowed(at(0, 2), bob));
	}

	@Test
	void bridgingClustersKeepsOnlyPlayersTrustedOnEverySide() {
		claim(alice, 0, 0);
		claim(alice, 2, 0);
		registry.setTrusted(at(0, 0), alice, bob, true);
		registry.setTrusted(at(0, 0), alice, carol, true);
		registry.setTrusted(at(2, 0), alice, carol, true);
		registry.setTrusted(at(2, 0), alice, dave, true);

		claim(alice, 1, 0); // bridges the two

		for (int x = 0; x <= 2; x++) {
			assertEquals(Set.of(carol), registry.get(at(x, 0)).trusted(), "chunk " + x);
		}
	}

	@Test
	void splittingAClusterKeepsEachSidesTrust() {
		claim(alice, 0, 0);
		claim(alice, 1, 0);
		claim(alice, 2, 0);
		registry.setTrusted(at(1, 0), alice, bob, true);
		registry.unclaim(at(1, 0), alice);
		assertTrue(registry.isAllowed(at(0, 0), bob));
		assertTrue(registry.isAllowed(at(2, 0), bob));
		assertEquals(Set.of(at(0, 0)), registry.cluster(at(0, 0)));
	}

	@Test
	void claimsInDimensionOnlyReturnsThatDimension() {
		claim(alice, 0, 0);
		registry.claim(new ClaimPos(NETHER, 4, 4), alice, BUFFER);
		assertEquals(1, registry.claimsIn(OVERWORLD).size());
		assertEquals(1, registry.claimsIn(NETHER).size());
	}

	@Test
	void restoreReplacesEverything() {
		claim(alice, 0, 0);
		ClaimRegistry copy = new ClaimRegistry();
		copy.restore(registry.snapshot());
		assertEquals(alice, copy.get(at(0, 0)).owner());
		assertEquals(1, copy.countOwned(alice));
	}
}
