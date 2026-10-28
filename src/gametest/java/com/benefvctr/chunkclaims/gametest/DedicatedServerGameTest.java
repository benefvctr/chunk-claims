package com.benefvctr.chunkclaims.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.server.level.ServerPlayer;

import com.benefvctr.chunkclaims.ChunkClaims;
import com.benefvctr.chunkclaims.core.ClaimPos;
import com.benefvctr.chunkclaims.server.ClaimManager;
import com.benefvctr.chunkclaims.server.ClaimService;

/**
 * The permissions menu against a real dedicated server on localhost. Singleplayer
 * hands packets over in memory without encoding them, so only a networked
 * connection exercises the payload codecs and the path a real server takes.
 *
 * <p>Skipped unless the run was started with {@code -PacceptMinecraftEula}, since
 * the server refuses to start without an accepted EULA.
 */
public class DedicatedServerGameTest implements FabricClientGameTest {
	private static final UUID FRIEND = UUID.nameUUIDFromBytes("chunkclaims-test:Friend".getBytes(StandardCharsets.UTF_8));

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!Boolean.getBoolean("chunkclaims.gametest.acceptEula")) {
			ChunkClaims.LOGGER.info("Skipping the dedicated server test; run with -PacceptMinecraftEula to include it");
			return;
		}
		try {
			Files.writeString(Path.of("eula.txt"), "eula=true\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();

			ClaimPos home = server.computeOnServer(s -> {
				ClaimManager manager = ClaimManager.current();
				ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
				ClaimPos pos = ClaimManager.chunkOf(player.level(), player.blockPosition());
				manager.rememberPlayer(FRIEND, "Friend");
				manager.registry().claim(pos, player.getUUID(), 0);
				ClaimService.resyncAll(manager);
				return pos;
			});
			connection.waitForClientboundPackets();

			context.getInput().pressKey(InputConstants.KEY_G);
			context.getInput().pressKey(InputConstants.KEY_J);
			connection.waitForServerboundPackets();
			connection.waitForClientboundPackets();
			context.waitFor(mc -> mc.gui.screen() != null && mc.gui.screen().getClass().getSimpleName().equals("PermissionsScreen"), 100);
			context.takeScreenshot("dedicated-01-permissions");

			ChunkClaimsClientGameTest.clickPermissionsRow(context, 0);
			connection.waitForServerboundPackets();
			connection.waitForClientboundPackets();
			context.waitTicks(3);
			context.takeScreenshot("dedicated-02-clicked");
			server.runOnServer(s -> check(ClaimManager.current().registry().isAllowed(home, FRIEND),
					"clicking a name on a dedicated server trusts that player"));
			context.setScreen(() -> null);
		}
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
	}
}
