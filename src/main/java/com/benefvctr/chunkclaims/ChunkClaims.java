package com.benefvctr.chunkclaims;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.benefvctr.chunkclaims.net.ModPayloads;
import com.benefvctr.chunkclaims.server.ClaimManager;
import com.benefvctr.chunkclaims.server.ClaimService;
import com.benefvctr.chunkclaims.server.Protection;

/**
 * Common entrypoint. Runs on dedicated servers and in singleplayer (where the
 * integrated server uses the same code), so everything server-authoritative
 * lives behind this: storage, economy, permissions and protection.
 */
public final class ChunkClaims implements ModInitializer {
	public static final String MOD_ID = "chunkclaims";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModPayloads.register();
		ClaimManager.registerLifecycle();
		ClaimService.register();
		Protection.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
