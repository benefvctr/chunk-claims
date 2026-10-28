package com.benefvctr.chunkclaims.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import com.benefvctr.chunkclaims.ChunkClaims;
import com.benefvctr.chunkclaims.core.ClaimPos;
import com.benefvctr.chunkclaims.core.ClaimRegistry;
import com.benefvctr.chunkclaims.core.ClaimStorage;
import com.benefvctr.chunkclaims.core.ClaimsConfig;
import com.benefvctr.chunkclaims.core.PriceTable;

/**
 * Server-lifetime state: the claim registry, config, known players and saving.
 *
 * <p>One instance exists while a server runs (dedicated or integrated). Mixins
 * reach it through {@link #current()}, which is null on a client with no
 * integrated server, so every hook treats "no manager" as "no protection here".
 */
public final class ClaimManager {
	/** Wait this long after the last change before writing, so bursts of claims save once. */
	private static final int SAVE_DELAY_TICKS = 100;

	private static volatile ClaimManager current;

	private final MinecraftServer server;
	private final Path claimsFile;
	private final ClaimRegistry registry = new ClaimRegistry();
	/** Everyone who has ever joined: UUID to last known name. */
	private final Map<UUID, String> knownPlayers = new ConcurrentHashMap<>();
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ChunkClaims-IO");
		t.setDaemon(true);
		return t;
	});
	private final ClaimsConfig config;
	private final PriceTable prices;

	private boolean dirty;
	private int ticksSinceChange;

	private ClaimManager(MinecraftServer server) throws IOException {
		this.server = server;
		this.config = ClaimStorage.loadConfig(FabricLoader.getInstance().getConfigDir().resolve(ChunkClaims.MOD_ID + ".json"));
		this.prices = new PriceTable(config.prices());
		config.prices().stream().flatMap(tier -> tier.cost().stream()).forEach(cost -> {
			if (Payment.item(cost).isEmpty()) {
				ChunkClaims.LOGGER.error("Unknown item '{}' in {}.json; nobody can pay for that tier", cost.item(), ChunkClaims.MOD_ID);
			}
		});
		this.claimsFile = server.getWorldPath(LevelResource.ROOT).resolve(ChunkClaims.MOD_ID).resolve("claims.json");

		ClaimStorage.Data data = ClaimStorage.load(claimsFile);
		registry.restore(data.claims());
		knownPlayers.putAll(data.players());
		importUserCache();
	}

	public static ClaimManager current() {
		return current;
	}

	public static void registerLifecycle() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			try {
				current = new ClaimManager(server);
				ChunkClaims.LOGGER.info("Loaded {} claims for {} known players", current.registry.snapshot().size(), current.knownPlayers.size());
			} catch (IOException e) {
				// Deliberately fatal: running without the claims file would leave every base unprotected.
				throw new IllegalStateException("Chunk Claims could not load its data", e);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ClaimManager manager = current;
			if (manager != null) {
				manager.tick();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ClaimManager manager = current;
			if (manager != null) {
				manager.shutdown();
				current = null;
			}
		});
	}

	public MinecraftServer server() {
		return server;
	}

	public ClaimRegistry registry() {
		return registry;
	}

	public ClaimsConfig config() {
		return config;
	}

	public PriceTable prices() {
		return prices;
	}

	public Map<UUID, String> knownPlayers() {
		return knownPlayers;
	}

	public String nameOf(UUID id) {
		return knownPlayers.getOrDefault(id, "another player");
	}

	public void rememberPlayer(UUID id, String name) {
		if (!name.equals(knownPlayers.put(id, name))) {
			markDirty();
		}
	}

	public static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}

	public static ClaimPos chunkOf(Level level, BlockPos pos) {
		return new ClaimPos(dimensionId(level), pos.getX() >> 4, pos.getZ() >> 4);
	}

	public void markDirty() {
		dirty = true;
		ticksSinceChange = 0;
	}

	private void tick() {
		if (dirty && ++ticksSinceChange >= SAVE_DELAY_TICKS) {
			dirty = false;
			ClaimStorage.Data snapshot = snapshot();
			io.execute(() -> write(snapshot));
		}
	}

	private ClaimStorage.Data snapshot() {
		return new ClaimStorage.Data(registry.snapshot(), Map.copyOf(knownPlayers));
	}

	private void write(ClaimStorage.Data data) {
		try {
			ClaimStorage.save(claimsFile, data);
		} catch (IOException e) {
			ChunkClaims.LOGGER.error("Failed to save claims to {}", claimsFile, e);
		}
	}

	private void shutdown() {
		io.shutdown();
		try {
			if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
				ChunkClaims.LOGGER.warn("Claim autosave did not finish in time; writing a final save anyway");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		write(snapshot());
	}

	/**
	 * Seeds the player list from the server's username cache so people who joined
	 * before the mod was installed still show up in the permissions menu.
	 */
	private void importUserCache() {
		Path cache = server.getServerDirectory().resolve("usercache.json");
		if (!Files.exists(cache)) {
			return;
		}
		try {
			for (JsonElement element : JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonArray()) {
				JsonObject entry = element.getAsJsonObject();
				UUID id = UUID.fromString(entry.get("uuid").getAsString());
				knownPlayers.putIfAbsent(id, entry.get("name").getAsString());
			}
		} catch (IOException | RuntimeException e) {
			ChunkClaims.LOGGER.warn("Could not read {}; the permissions menu will only list players seen from now on", cache, e);
		}
	}
}
