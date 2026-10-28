package com.benefvctr.chunkclaims.gametest;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import com.benefvctr.chunkclaims.client.ClientClaimState;
import com.benefvctr.chunkclaims.core.ClaimPos;
import com.benefvctr.chunkclaims.server.ClaimManager;
import com.benefvctr.chunkclaims.server.ClaimService;

/**
 * End-to-end check in a real singleplayer world: buy a chunk with the hotkeys,
 * get refused inside a rival's buffer, open the permissions menu, and throw real
 * griefs (breaking, TNT, lava, hitting a cow) at a rival's claim. Screenshots of
 * each overlay state land in run/screenshots.
 */
public class ChunkClaimsClientGameTest implements FabricClientGameTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID RIVAL = uuid("Rival");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerConnection connection = world.getConnection();
			TestServerContext server = world.getServer();
			connection.waitForChunksRender();

			server.runCommand("gamemode survival @a");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("give @a minecraft:cobblestone 64");

			int[] home = server.computeOnServer(s -> {
				BlockPos pos = player(s).blockPosition();
				return new int[] {pos.getX() >> 4, pos.getZ() >> 4};
			});
			int hx = home[0];
			int hz = home[1];

			// A rival owns two chunks four to the east; chunks 2-3 east of home are their buffer.
			server.runOnServer(s -> {
				ClaimManager manager = ClaimManager.current();
				manager.rememberPlayer(RIVAL, "Rival");
				for (String name : List.of("Alex", "Casey", "Jordan", "Morgan", "Riley", "Robin", "Sam", "Taylor")) {
					manager.rememberPlayer(uuid(name), name);
				}
				manager.registry().claim(new ClaimPos(OVERWORLD, hx + 4, hz), RIVAL, 0);
				manager.registry().claim(new ClaimPos(OVERWORLD, hx + 4, hz + 1), RIVAL, 0);
				ClaimService.resyncAll(manager);
			});
			connection.waitForClientboundPackets();

			// Face east, looking down at the ground ahead.
			context.getInput().lookAt(-90.0F, 50.0F);
			context.getInput().pressKey(InputConstants.KEY_G);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> ClientClaimState.editMode()), "G turns edit mode on");
			context.takeScreenshot("chunkclaims-01-available");

			// Buy the chunk we stand in.
			context.getInput().pressKey(InputConstants.KEY_H);
			connection.waitForServerboundPackets();
			connection.waitForClientboundPackets();
			context.waitTicks(3);
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				check(ClaimManager.current().registry().countOwned(p.getUUID()) == 1, "H claims the current chunk");
				int cobble = count(p, Items.COBBLESTONE.getDefaultInstance());
				check(cobble == 32, "first chunk costs 32 cobblestone, player has " + cobble);
			});
			check(context.computeOnClient(mc -> mc.player.getUUID().equals(ClientClaimState.ownerAt(hx, hz))), "client map shows the new claim");
			context.takeScreenshot("chunkclaims-02-claimed");

			// Walk into the rival's buffer: red, and the claim key is refused.
			teleport(server, (hx + 2) * 16 + 8, hz * 16 + 8);
			connection.waitForChunksRender();
			context.waitTicks(5);
			context.takeScreenshot("chunkclaims-03-buffer");
			context.getInput().pressKey(InputConstants.KEY_H);
			connection.waitForServerboundPackets();
			context.waitTicks(2);
			server.runOnServer(s -> check(ClaimManager.current().registry().countOwned(player(s).getUUID()) == 1, "claiming inside a buffer is refused"));

			// Bird's-eye view straight down: home (blue), buffer edge (faint red), rival (purple).
			server.runCommand("gamemode spectator @a");
			int x = (hx + 2) * 16 + 8;
			int z = hz * 16 + 8;
			int ground = server.computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
			server.runCommand("tp @a " + x + " " + (ground + 60) + " " + z + " -90 90");
			connection.waitForChunksRender();
			context.waitTicks(5);
			context.takeScreenshot("chunkclaims-03b-overview");
			teleport(server, x, z);
			server.runCommand("gamemode survival @a");
			context.waitTicks(2);

			// Griefing the rival's land through the real game code paths.
			int[] lava = server.computeOnServer(s -> griefRival(s, hx, hz));
			// Lava only spreads every 30 ticks; give it several steps.
			context.waitTicks(100);
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockPos inside = new BlockPos(lava[0], lava[1], lava[2]);
				BlockPos source = inside.west();
				BlockPos outside = inside.west(2);
				String seen = "source=" + level.getBlockState(source) + " west=" + level.getBlockState(outside) + " inside=" + level.getBlockState(inside);
				check(level.getFluidState(outside).is(net.minecraft.tags.FluidTags.LAVA), "control: lava spreads on unclaimed ground; " + seen);
				check(!level.getFluidState(inside).is(net.minecraft.tags.FluidTags.LAVA), "lava must not flow into a claim; " + seen);
			});

			// Back home: the permissions menu opens for our cluster.
			teleport(server, hx * 16 + 8, hz * 16 + 8);
			connection.waitForChunksRender();
			context.getInput().pressKey(InputConstants.KEY_J);
			connection.waitForServerboundPackets();
			connection.waitForClientboundPackets();
			context.waitFor(mc -> mc.gui.screen() != null && mc.gui.screen().getClass().getSimpleName().equals("PermissionsScreen"), 100);
			context.takeScreenshot("chunkclaims-04-permissions");

			// Clicking the first row (Alex, sorted by name) trusts Alex in this cluster.
			clickPermissionsRow(context, 0);
			connection.waitForServerboundPackets();
			connection.waitForClientboundPackets();
			context.waitTicks(3);
			server.runOnServer(s -> check(ClaimManager.current().registry().isAllowed(new ClaimPos(OVERWORLD, hx, hz), uuid("Alex")),
					"clicking a row in the permissions menu trusts that player"));
			context.takeScreenshot("chunkclaims-05-trusted");

			// Double-clicking a name (the habit from the server and world lists) still trusts once.
			clickPermissionsRow(context, 1);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			connection.waitForServerboundPackets();
			connection.waitForClientboundPackets();
			context.waitTicks(3);
			server.runOnServer(s -> check(ClaimManager.current().registry().isAllowed(new ClaimPos(OVERWORLD, hx, hz), uuid("Casey")),
					"double-clicking a name trusts that player instead of toggling twice"));
			context.setScreen(() -> null);
		}
	}

	/** Moves the real cursor onto a name in the open permissions menu and left-clicks it. */
	static void clickPermissionsRow(ClientGameTestContext context, int row) {
		double[] cursor = context.computeOnClient(mc -> {
			var window = mc.getWindow();
			// Window frame 252x140, list starts 18 + 18 below its top, rows are 16 tall.
			double guiX = (window.getGuiScaledWidth() - 252) / 2 + 9 + 4 + 40;
			double guiY = (window.getGuiScaledHeight() - 140) / 2 + 18 + 18 + row * 16 + 8;
			double toScreen = (double) window.getScreenWidth() / window.getGuiScaledWidth();
			return new double[] {guiX * toScreen, guiY * toScreen};
		});
		context.getInput().setCursorPos(cursor[0], cursor[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
	}

	/** Runs the grief attempts and returns the lava target position (checked after it has had time to flow). */
	private static int[] griefRival(MinecraftServer server, int hx, int hz) {
		ServerLevel level = server.overworld();
		ServerPlayer player = player(server);
		int rivalX = (hx + 4) * 16;
		int bx = rivalX + 8;
		int bz = hz * 16 + 8;
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);

		// Breaking a block through the player's own game mode (fires Fabric's break event).
		BlockPos target = new BlockPos(bx, top, bz);
		level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
		check(!player.gameMode.destroyBlock(target), "breaking a block in someone else's claim is refused");
		check(level.getBlockState(target).is(Blocks.STONE), "the block is still there");

		// TNT-strength explosion inside a stone cube in the claim. Cubes float in the
		// air so nearby water or terrain cannot change the outcome.
		BlockPos blast = new BlockPos(bx, top + 20, bz);
		int before = stoneCube(level, blast);
		level.explode(null, blast.getX() + 0.5, blast.getY() + 0.5, blast.getZ() + 0.5, 4.0F, Level.ExplosionInteraction.TNT);
		check(solidBlocksAround(level, blast, 2) == before, "explosions leave claimed blocks alone");

		// Control: the same blast in an identical cube on unclaimed land does destroy blocks.
		BlockPos freeBlast = new BlockPos((hx + 2) * 16 + 8, top + 20, bz);
		int freeBefore = stoneCube(level, freeBlast);
		level.explode(null, freeBlast.getX() + 0.5, freeBlast.getY() + 0.5, freeBlast.getZ() + 0.5, 4.0F, Level.ExplosionInteraction.TNT);
		check(solidBlocksAround(level, freeBlast, 2) < freeBefore, "control: explosions still work outside claims");

		// A player hitting a cow inside the claim does nothing.
		Cow cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
		cow.setPos(bx + 0.5, top + 1, bz + 3.5);
		level.addFreshEntity(cow);
		float health = cow.getHealth();
		cow.hurtServer(level, level.damageSources().playerAttack(player), 4.0F);
		check(cow.getHealth() == health, "animals in a claim ignore unauthorized players");

		// Lava poured just outside the rival's west edge, in a walled stone channel high in
		// the air. Lava heads for the nearest drop, so the channel is long and walled: with
		// no drop in reach it spreads both ways, west (unclaimed, control) and east (claim).
		int y = top + 12;
		for (int x = rivalX - 7; x <= rivalX + 6; x++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlockAndUpdate(new BlockPos(x, y - 1, bz + dz), Blocks.STONE.defaultBlockState());
				level.setBlockAndUpdate(new BlockPos(x, y, bz + dz), dz == 0 ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
			}
		}
		level.setBlockAndUpdate(new BlockPos(rivalX - 1, y, bz), Blocks.LAVA.defaultBlockState());

		// Once the rival trusts us, the same kind of break goes through.
		ClaimPos rivalChunk = new ClaimPos(OVERWORLD, hx + 4, hz);
		ClaimManager.current().registry().setTrusted(rivalChunk, RIVAL, player.getUUID(), true);
		BlockPos trustedTarget = target.north(2);
		level.setBlockAndUpdate(trustedTarget, Blocks.STONE.defaultBlockState());
		check(player.gameMode.destroyBlock(trustedTarget), "a trusted player can break blocks in the claim");
		check(level.getBlockState(trustedTarget).isAir(), "the trusted player's break removed the block");
		ClaimManager.current().registry().setTrusted(rivalChunk, RIVAL, player.getUUID(), false);
		return new int[] {rivalX, y, bz};
	}

	/** A hollow-centred 3x3x3 stone cube; returns how many solid blocks are within 2 of the centre. */
	private static int stoneCube(ServerLevel level, BlockPos center) {
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
			level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
		}
		level.setBlockAndUpdate(center, Blocks.AIR.defaultBlockState());
		return solidBlocksAround(level, center, 2);
	}

	private static int solidBlocksAround(ServerLevel level, BlockPos center, int radius) {
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
			if (!level.getBlockState(pos).isAir()) {
				count++;
			}
		}
		return count;
	}

	private static void teleport(TestServerContext server, int x, int z) {
		int y = server.computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
		server.runCommand("tp @a " + x + " " + y + " " + z + " -90 50");
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static int count(ServerPlayer player, ItemStack kind) {
		return player.getInventory().getNonEquipmentItems().stream()
				.filter(s -> s.getItem() == kind.getItem()).mapToInt(ItemStack::getCount).sum();
	}

	private static UUID uuid(String name) {
		return UUID.nameUUIDFromBytes(("chunkclaims-test:" + name).getBytes(StandardCharsets.UTF_8));
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
	}
}
