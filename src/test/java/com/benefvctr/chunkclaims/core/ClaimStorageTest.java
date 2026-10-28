package com.benefvctr.chunkclaims.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaimStorageTest {
	@TempDir
	Path dir;

	private final UUID alice = UUID.randomUUID();
	private final UUID bob = UUID.randomUUID();

	private ClaimStorage.Data sample() {
		return new ClaimStorage.Data(
				Map.of(
						new ClaimPos("minecraft:overworld", 3, -7), new Claim(alice, Set.of(bob)),
						new ClaimPos("minecraft:the_nether", 0, 0), Claim.of(bob)),
				Map.of(alice, "Alice", bob, "Bob"));
	}

	@Test
	void roundTripsClaimsTrustAndPlayers() throws IOException {
		Path file = dir.resolve("claims.json");
		ClaimStorage.save(file, sample());
		assertEquals(sample(), ClaimStorage.load(file));
	}

	@Test
	void missingFileLoadsEmpty() throws IOException {
		ClaimStorage.Data data = ClaimStorage.load(dir.resolve("nope.json"));
		assertTrue(data.claims().isEmpty());
		assertTrue(data.players().isEmpty());
	}

	@Test
	void corruptFileFallsBackToTheBackup() throws IOException {
		Path file = dir.resolve("claims.json");
		ClaimStorage.save(file, sample());
		ClaimStorage.save(file, sample()); // second save rotates the first into .bak
		Files.writeString(file, "{ not json");
		assertEquals(sample(), ClaimStorage.load(file));
	}

	@Test
	void corruptFileWithoutBackupRefusesToLoad() throws IOException {
		Path file = dir.resolve("claims.json");
		Files.writeString(file, "{ not json");
		assertThrows(IOException.class, () -> ClaimStorage.load(file));
	}

	@Test
	void configRoundTripsAndFillsDefaults() throws IOException {
		Path file = dir.resolve("chunkclaims.json");
		ClaimsConfig loaded = ClaimStorage.loadConfig(file);
		assertEquals(ClaimsConfig.defaults(), loaded);
		assertTrue(Files.exists(file), "defaults are written for the admin to edit");
		assertEquals(ClaimsConfig.defaults(), ClaimStorage.loadConfig(file));
	}
}
