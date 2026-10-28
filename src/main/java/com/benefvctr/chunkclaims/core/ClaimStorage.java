package com.benefvctr.chunkclaims.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * JSON persistence for claims and config.
 *
 * <p>Writes go to a temp file that is then moved over the real one, so a crash
 * mid-save never leaves a half-written file. The previous good file is kept as
 * {@code .bak}. If the main file is unreadable the backup is used; if both are,
 * loading fails loudly instead of starting with no claims, because a server that
 * silently forgets every claim is wide open to griefing.
 */
public final class ClaimStorage {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int FORMAT_VERSION = 1;

	private ClaimStorage() {
	}

	/** Everything that lives in {@code claims.json}. */
	public record Data(Map<ClaimPos, Claim> claims, Map<UUID, String> players) {
	}

	// On-disk shape. Kept separate from the domain types so the file format can evolve.
	private record FileClaim(String dim, int x, int z, String owner, List<String> trusted) {
	}

	private record FileData(int version, Map<String, String> players, List<FileClaim> claims) {
	}

	public static void save(Path file, Data data) throws IOException {
		List<FileClaim> claims = new ArrayList<>(data.claims().size());
		data.claims().forEach((pos, claim) -> claims.add(new FileClaim(
				pos.dimension(), pos.x(), pos.z(), claim.owner().toString(),
				claim.trusted().stream().map(UUID::toString).sorted().toList())));
		// Stable order keeps diffs of the file readable.
		claims.sort((a, b) -> a.dim().equals(b.dim())
				? (a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()))
				: a.dim().compareTo(b.dim()));

		Map<String, String> players = new HashMap<>();
		data.players().forEach((id, name) -> players.put(id.toString(), name));

		writeAtomically(file, GSON.toJson(new FileData(FORMAT_VERSION, players, claims)), true);
	}

	public static Data load(Path file) throws IOException {
		if (!Files.exists(file)) {
			return new Data(Map.of(), Map.of());
		}
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException primaryFailure) {
			Path backup = backupOf(file);
			if (Files.exists(backup)) {
				try {
					return parse(Files.readString(backup, StandardCharsets.UTF_8));
				} catch (IOException | RuntimeException backupFailure) {
					primaryFailure.addSuppressed(backupFailure);
				}
			}
			throw new IOException("Could not read " + file + " (or its .bak). Fix or remove it; refusing to start with no claims.", primaryFailure);
		}
	}

	private static Data parse(String json) throws IOException {
		FileData raw;
		try {
			raw = GSON.fromJson(json, FileData.class);
		} catch (JsonParseException e) {
			throw new IOException("malformed claims file", e);
		}
		if (raw == null) {
			throw new IOException("empty claims file");
		}
		Map<ClaimPos, Claim> claims = new HashMap<>();
		if (raw.claims() != null) {
			for (FileClaim c : raw.claims()) {
				Set<UUID> trusted = new HashSet<>();
				if (c.trusted() != null) {
					c.trusted().forEach(t -> trusted.add(UUID.fromString(t)));
				}
				claims.put(new ClaimPos(c.dim(), c.x(), c.z()), new Claim(UUID.fromString(c.owner()), trusted));
			}
		}
		Map<UUID, String> players = new HashMap<>();
		if (raw.players() != null) {
			raw.players().forEach((id, name) -> players.put(UUID.fromString(id), name));
		}
		return new Data(claims, players);
	}

	/** Reads the config, writing the defaults first if the file does not exist yet. */
	public static ClaimsConfig loadConfig(Path file) throws IOException {
		if (!Files.exists(file)) {
			ClaimsConfig defaults = ClaimsConfig.defaults();
			writeAtomically(file, GSON.toJson(defaults), false);
			return defaults;
		}
		try {
			ClaimsConfig parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClaimsConfig.class);
			if (parsed == null) {
				throw new IOException("empty config file");
			}
			return parsed.sanitized();
		} catch (JsonParseException e) {
			throw new IOException("Malformed " + file + ": " + e.getMessage(), e);
		}
	}

	private static Path backupOf(Path file) {
		return file.resolveSibling(file.getFileName() + ".bak");
	}

	private static void writeAtomically(Path file, String content, boolean keepBackup) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, content, StandardCharsets.UTF_8);
		if (keepBackup && Files.exists(file)) {
			Files.copy(file, backupOf(file), StandardCopyOption.REPLACE_EXISTING);
		}
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
