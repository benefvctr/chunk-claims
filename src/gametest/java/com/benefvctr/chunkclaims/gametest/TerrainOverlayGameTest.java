package com.benefvctr.chunkclaims.gametest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import com.benefvctr.chunkclaims.client.ClientClaimState;
import com.benefvctr.chunkclaims.core.ClaimPos;
import com.benefvctr.chunkclaims.server.ClaimManager;
import com.benefvctr.chunkclaims.server.ClaimService;

/**
 * The overlay on real terrain: a normally generated world (fixed seed) instead of
 * the flat test default. Trees, a pond and a cave room are placed across claim
 * borders so every case of the ground-finding code shows up in the screenshots.
 *
 * <p>Layout (chunks, x to the east): the player owns a 2x2 block at spawn, a rival
 * owns a 2x2 block three chunks further east.
 */
public class TerrainOverlayGameTest implements FabricClientGameTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String SEED = "chunkclaims";
	private static final UUID RIVAL = UUID.nameUUIDFromBytes("chunkclaims-test:TerrainRival".getBytes(StandardCharsets.UTF_8));

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(settings -> settings.setSeed(SEED))
				.create()) {
			TestServerConnection connection = world.getConnection();
			TestServerContext server = world.getServer();
			connection.waitForChunksRender();

			server.runCommand("gamemode creative @a");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("gamerule advance_time false");

			int[] spawn = server.computeOnServer(s -> {
				BlockPos pos = s.getPlayerList().getPlayers().getFirst().blockPosition();
				return new int[] {pos.getX() >> 4, pos.getZ() >> 4};
			});
			int sx = spawn[0];
			int sz = spawn[1];
			int west = sx * 16;
			int east = (sx + 2) * 16;
			int north = sz * 16;
			int south = (sz + 2) * 16;

			server.runOnServer(s -> {
				ClaimManager manager = ClaimManager.current();
				UUID self = s.getPlayerList().getPlayers().getFirst().getUUID();
				manager.rememberPlayer(RIVAL, "Rival");
				for (int dx = 0; dx < 2; dx++) {
					for (int dz = 0; dz < 2; dz++) {
						manager.registry().claim(new ClaimPos(OVERWORLD, sx + dx, sz + dz), self, 0);
						manager.registry().claim(new ClaimPos(OVERWORLD, sx + 4 + dx, sz + dz), RIVAL, 0);
					}
				}
				ClaimService.resyncAll(manager);
				decorate(s, west, north, south, east);
			});
			// Trees straddling the west border, so leaves hang over the line.
			for (int dz : new int[] {4, 11}) {
				int x = west;
				int z = north + dz;
				int y = server.computeOnServer(s -> ground(s.overworld(), x, z) + 1);
				server.runCommand("place feature minecraft:oak " + x + " " + y + " " + z);
			}
			connection.waitForClientboundPackets();

			context.getInput().pressKey(InputConstants.KEY_G);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> ClientClaimState.editMode()), "edit mode is on");

			// 1. From above: both territories, the buffer edge, trees and the pond.
			server.runCommand("gamemode spectator @a");
			lookFrom(server, connection, context, (sx + 3) * 16, north + 16, 70, -90, 90);
			context.takeScreenshot("terrain-01-overview");

			// 2. Standing inside at eye height, looking down-west at the border under the trees.
			lookFrom(server, connection, context, west + 4, north + 8, 0, 90, 40);
			context.takeScreenshot("terrain-02-trees");
			withOverlayOff(context, "terrain-02-trees-off");

			// 3. Looking south at the border crossing the pond.
			lookFrom(server, connection, context, west + 7, south - 7, 0, 0, 40);
			context.takeScreenshot("terrain-03-water");
			withOverlayOff(context, "terrain-03-water-off");

			// 4. Underground, in a room that straddles the east border.
			int roomFloor = server.computeOnServer(s -> caveRoom(s.overworld(), east, north + 8));
			server.runCommand("effect give @a minecraft:night_vision infinite 0 true");
			server.runCommand("gamemode creative @a");
			server.runCommand("tp @a " + (east - 4) + " " + roomFloor + " " + (north + 8) + " -90 30");
			connection.waitForChunksRender();
			context.waitTicks(15);
			context.takeScreenshot("terrain-04-cave");
		}
	}

	/** The same view with edit mode off, so a diff against the "on" shot isolates the overlay. */
	private static void withOverlayOff(ClientGameTestContext context, String name) {
		context.getInput().pressKey(InputConstants.KEY_G);
		context.waitTicks(3);
		context.takeScreenshot(name);
		context.getInput().pressKey(InputConstants.KEY_G);
		context.waitTicks(3);
	}

	/** Teleports to (x, ground + height, z) facing yaw/pitch and lets the overlay rebuild. */
	private static void lookFrom(TestServerContext server, TestServerConnection connection, ClientGameTestContext context,
			int x, int z, int height, float yaw, float pitch) {
		int y = server.computeOnServer(s -> ground(s.overworld(), x, z) + 1 + height);
		server.runCommand("tp @a " + x + " " + y + " " + z + " " + yaw + " " + pitch);
		connection.waitForChunksRender();
		context.waitTicks(15);
	}

	/** A pond across the south border: the top block of each column becomes water. */
	private static void decorate(MinecraftServer server, int west, int north, int south, int east) {
		ServerLevel level = server.overworld();
		for (int x = west + 4; x <= west + 11; x++) {
			for (int z = south - 4; z <= south + 3; z++) {
				int top = ground(level, x, z);
				level.setBlockAndUpdate(new BlockPos(x, top, z), Blocks.WATER.defaultBlockState());
				level.setBlockAndUpdate(new BlockPos(x, top + 1, z), Blocks.AIR.defaultBlockState());
			}
		}
	}

	/**
	 * Carves an 11x4x9 room 20 blocks below the surface, centred on the east border
	 * line, with a stone floor. Returns the floor's walking height.
	 */
	private static int caveRoom(ServerLevel level, int borderX, int centerZ) {
		int floor = ground(level, borderX, centerZ) - 20;
		for (int x = borderX - 6; x <= borderX + 5; x++) {
			for (int z = centerZ - 4; z <= centerZ + 4; z++) {
				level.setBlockAndUpdate(new BlockPos(x, floor - 1, z), Blocks.STONE.defaultBlockState());
				for (int y = floor; y < floor + 4; y++) {
					level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
				}
				level.setBlockAndUpdate(new BlockPos(x, floor + 4, z), Blocks.STONE.defaultBlockState());
			}
		}
		return floor;
	}

	/** Y of the highest ground block in a column, looking through tree trunks and leaves. */
	private static int ground(ServerLevel level, int x, int z) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
		while (y > level.getMinY()) {
			BlockState state = level.getBlockState(pos.setY(y));
			if (!state.isAir() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
				break;
			}
			y--;
		}
		return y;
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
	}
}
