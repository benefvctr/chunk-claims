package com.benefvctr.chunkclaims.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Paints claim state onto the ground while edit mode is on.
 *
 * <ul>
 *   <li>Own territory: blue strip and fade wall on its outer border.</li>
 *   <li>The own chunk you stand in: a light, subtle blue outline.</li>
 *   <li>Unowned chunk you stand in: green fill if claimable, red if inside a buffer.</li>
 *   <li>Buffer zones in view: a faint red line on their outer edge.</li>
 *   <li>Other players' territory: purple strip and fade wall on its outer border.</li>
 * </ul>
 *
 * <p>Geometry is built on the client tick (only when something changed, or every
 * half second to follow terrain edits) and replayed each frame, so the per-frame
 * cost is just copying vertices.
 */
final class ClaimOverlay {
	private static final int VIEW_RADIUS = 8;
	private static final int REFRESH_TICKS = 10;

	private static final int BLUE = 0x3C8CFF;
	private static final int LIGHT_BLUE = 0xB4DCFF;
	private static final int PURPLE = 0xB04CFF;
	private static final int GREEN = 0x4CFF6A;
	private static final int RED = 0xFF4646;

	private static final float STRIP = 0.16F;
	private static final float INNER_STRIP_OFFSET = 0.22F;
	private static final float WALL = 1.1F;
	private static final float LIFT = 0.02F;

	private static Geometry geometry = Geometry.EMPTY;
	private static int builtVersion = -1;
	private static long builtChunk = Long.MIN_VALUE;
	private static int ticksSinceBuild;

	private ClaimOverlay() {
	}

	static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (!ClientClaimState.editMode() || player == null || client.level == null) {
			geometry = Geometry.EMPTY;
			builtVersion = -1;
			return;
		}
		long chunk = BlockPos.asLong(player.blockPosition().getX() >> 4, 0, player.blockPosition().getZ() >> 4);
		if (builtVersion != ClientClaimState.version() || builtChunk != chunk || ++ticksSinceBuild >= REFRESH_TICKS) {
			geometry = build(client.level, player);
			builtVersion = ClientClaimState.version();
			builtChunk = chunk;
			ticksSinceBuild = 0;
		}
	}

	static void submit(LevelRenderContext context) {
		Geometry g = geometry;
		if (g.vertexCount == 0) {
			return;
		}
		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack poses = context.poseStack();
		poses.pushPose();
		// Vertices are stored relative to an origin near the player so floats stay precise.
		poses.translate(g.originX - camera.x, g.originY - camera.y, g.originZ - camera.z);
		context.submitNodeCollector().submitCustomGeometry(poses, RenderTypes.debugQuads(), g::emit);
		poses.popPose();
	}

	// ---------------------------------------------------------------- building

	private static Geometry build(ClientLevel level, LocalPlayer player) {
		UUID self = player.getUUID();
		int pcx = player.blockPosition().getX() >> 4;
		int pcz = player.blockPosition().getZ() >> 4;
		Builder out = new Builder(pcx * 16, Math.floor(player.getY()), pcz * 16);
		Ground ground = new Ground(level, player);

		for (int cx = pcx - VIEW_RADIUS; cx <= pcx + VIEW_RADIUS; cx++) {
			for (int cz = pcz - VIEW_RADIUS; cz <= pcz + VIEW_RADIUS; cz++) {
				UUID owner = ClientClaimState.ownerAt(cx, cz);
				if (owner != null) {
					int color = owner.equals(self) ? BLUE : PURPLE;
					for (Direction side : Direction.Plane.HORIZONTAL) {
						if (!owner.equals(ClientClaimState.ownerAt(cx + side.getStepX(), cz + side.getStepZ()))) {
							edge(out, ground, cx, cz, side, color, 0xD0, 0, STRIP, true);
						}
					}
				} else if (inBuffer(cx, cz, self)) {
					for (Direction side : Direction.Plane.HORIZONTAL) {
						int nx = cx + side.getStepX();
						int nz = cz + side.getStepZ();
						if (ClientClaimState.ownerAt(nx, nz) == null && !inBuffer(nx, nz, self)) {
							edge(out, ground, cx, cz, side, RED, 0x60, 0, STRIP * 0.6F, false);
						}
					}
				}
			}
		}

		UUID here = ClientClaimState.ownerAt(pcx, pcz);
		if (self.equals(here)) {
			for (Direction side : Direction.Plane.HORIZONTAL) {
				edge(out, ground, pcx, pcz, side, LIGHT_BLUE, 0x70, INNER_STRIP_OFFSET, STRIP * 0.7F, false);
			}
		} else if (here == null && ClientClaimState.status() != null) {
			boolean claimable = ClientClaimState.dimensionClaimable() && ClientClaimState.blockingOwner(pcx, pcz, self) == null;
			int color = claimable ? GREEN : RED;
			fill(out, ground, pcx, pcz, color, 0x50);
			for (Direction side : Direction.Plane.HORIZONTAL) {
				edge(out, ground, pcx, pcz, side, color, 0xB0, 0, STRIP, false);
			}
		}
		return out.finish();
	}

	private static boolean inBuffer(int cx, int cz, UUID self) {
		return ClientClaimState.blockingOwner(cx, cz, self) != null;
	}

	/**
	 * One side of a chunk: a flat strip on the ground just inside the edge (offset
	 * {@code inset} from it) and optionally a vertical wall that fades upward.
	 */
	private static void edge(Builder out, Ground ground, int cx, int cz, Direction side, int rgb, int alpha, float inset, float width, boolean wall) {
		int x0 = cx * 16;
		int z0 = cz * 16;
		int solid = (alpha << 24) | rgb;
		int wallBottom = ((alpha * 2 / 3) << 24) | rgb;
		int clear = rgb;
		for (int i = 0; i < 16; i++) {
			// Block column next to this edge, and the edge line's fixed coordinate.
			int bx;
			int bz;
			switch (side) {
				case NORTH -> { bx = x0 + i; bz = z0; }
				case SOUTH -> { bx = x0 + i; bz = z0 + 15; }
				case WEST -> { bx = x0; bz = z0 + i; }
				default -> { bx = x0 + 15; bz = z0 + i; }
			}
			double y = ground.at(bx, bz) + LIFT;
			boolean alongX = side == Direction.NORTH || side == Direction.SOUTH;
			// Edge line position and the inward direction (toward the chunk's inside).
			double line = switch (side) {
				case NORTH -> z0;
				case SOUTH -> z0 + 16;
				case WEST -> x0;
				default -> x0 + 16;
			};
			float inward = (side == Direction.NORTH || side == Direction.WEST) ? 1 : -1;
			double a = alongX ? bx : bz;
			double near = line + inward * inset;
			double far = line + inward * (inset + width);
			if (alongX) {
				out.quad(a, y, near, solid, a + 1, y, near, solid, a + 1, y, far, solid, a, y, far, solid);
				if (wall) {
					out.quad(a, y, line, wallBottom, a + 1, y, line, wallBottom, a + 1, y + WALL, line, clear, a, y + WALL, line, clear);
				}
			} else {
				out.quad(near, y, a, solid, near, y, a + 1, solid, far, y, a + 1, solid, far, y, a, solid);
				if (wall) {
					out.quad(line, y, a, wallBottom, line, y, a + 1, wallBottom, line, y + WALL, a + 1, clear, line, y + WALL, a, clear);
				}
			}
		}
	}

	/** Tints the top of every block column in a chunk. */
	private static void fill(Builder out, Ground ground, int cx, int cz, int rgb, int alpha) {
		int color = (alpha << 24) | rgb;
		for (int x = cx * 16; x < cx * 16 + 16; x++) {
			for (int z = cz * 16; z < cz * 16 + 16; z++) {
				double y = ground.at(x, z) + LIFT * 0.5F;
				out.quad(x, y, z, color, x + 1, y, z, color, x + 1, y, z + 1, color, x, y, z + 1, color);
			}
		}
	}

	// ---------------------------------------------------------------- terrain

	/**
	 * Finds the walkable surface of a block column near the player. On the surface
	 * that is the top of the terrain (ignoring leaves and logs so lines do not climb
	 * trees); underground it is the floor of whatever space the player is in.
	 */
	private static final class Ground {
		private static final int SCAN_DEPTH = 48;

		private final ClientLevel level;
		private final int playerY;
		private final boolean underground;
		private final Map<Long, Float> cache = new HashMap<>();
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		Ground(ClientLevel level, LocalPlayer player) {
			this.level = level;
			this.playerY = (int) Math.floor(player.getY());
			this.underground = hasCeiling(player.blockPosition());
		}

		private boolean hasCeiling(BlockPos feet) {
			for (int y = feet.getY() + 2; y < feet.getY() + 32 && y < level.getMaxY(); y++) {
				if (isFloor(level.getBlockState(cursor.set(feet.getX(), y, feet.getZ())))) {
					return true;
				}
			}
			return false;
		}

		float at(int x, int z) {
			return cache.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> find(x, z));
		}

		private float find(int x, int z) {
			if (!level.hasChunk(x >> 4, z >> 4)) {
				return playerY;
			}
			int start = underground ? playerY + 3 : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			for (int y = start; y > start - SCAN_DEPTH && y > level.getMinY(); y--) {
				BlockState state = level.getBlockState(cursor.set(x, y, z));
				if (!isFloor(state)) {
					continue;
				}
				BlockState above = level.getBlockState(cursor.set(x, y + 1, z));
				if (!isFloor(above)) {
					return y + (float) topOf(state, x, y, z);
				}
			}
			return playerY;
		}

		private boolean isFloor(BlockState state) {
			if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
				return false;
			}
			return !state.getFluidState().isEmpty() || state.is(Blocks.SNOW)
					|| !state.getCollisionShape(level, cursor).isEmpty();
		}

		private double topOf(BlockState state, int x, int y, int z) {
			cursor.set(x, y, z);
			if (!state.getFluidState().isEmpty()) {
				return state.getFluidState().getHeight(level, cursor);
			}
			VoxelShape shape = state.is(Blocks.SNOW) ? state.getShape(level, cursor) : state.getCollisionShape(level, cursor);
			return shape.isEmpty() ? 0 : Math.min(1.0, shape.max(Direction.Axis.Y));
		}
	}

	// ---------------------------------------------------------------- vertex storage

	/** Built quads: positions relative to the origin, one ARGB colour per vertex. */
	private record Geometry(double originX, double originY, double originZ, float[] positions, int[] colors, int vertexCount) {
		static final Geometry EMPTY = new Geometry(0, 0, 0, new float[0], new int[0], 0);

		void emit(PoseStack.Pose pose, VertexConsumer vertices) {
			for (int i = 0; i < vertexCount; i++) {
				vertices.addVertex(pose, positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]).setColor(colors[i]);
			}
		}
	}

	private static final class Builder {
		private final double originX;
		private final double originY;
		private final double originZ;
		private float[] positions = new float[4096 * 3];
		private int[] colors = new int[4096];
		private int count;

		Builder(double originX, double originY, double originZ) {
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
		}

		/**
		 * The debug-quads pipeline culls back faces, so winding matters: flat quads are
		 * turned to face up (they are seen from above), vertical walls are emitted
		 * twice so they show from both sides.
		 */
		void quad(double x1, double y1, double z1, int c1, double x2, double y2, double z2, int c2,
				double x3, double y3, double z3, int c3, double x4, double y4, double z4, int c4) {
			// y component of (v2 - v1) x (v3 - v1): the quad's normal, up or down.
			double normalY = (z2 - z1) * (x3 - x1) - (x2 - x1) * (z3 - z1);
			boolean faceUp = normalY > 0;
			boolean vertical = Math.abs(normalY) < 1.0E-9;
			if (faceUp || vertical) {
				vertex(x1, y1, z1, c1);
				vertex(x2, y2, z2, c2);
				vertex(x3, y3, z3, c3);
				vertex(x4, y4, z4, c4);
			}
			if (!faceUp) {
				vertex(x4, y4, z4, c4);
				vertex(x3, y3, z3, c3);
				vertex(x2, y2, z2, c2);
				vertex(x1, y1, z1, c1);
			}
		}

		private void vertex(double x, double y, double z, int color) {
			if (count == colors.length) {
				positions = Arrays.copyOf(positions, positions.length * 2);
				colors = Arrays.copyOf(colors, colors.length * 2);
			}
			positions[count * 3] = (float) (x - originX);
			positions[count * 3 + 1] = (float) (y - originY);
			positions[count * 3 + 2] = (float) (z - originZ);
			colors[count] = color;
			count++;
		}

		Geometry finish() {
			return new Geometry(originX, originY, originZ, positions, colors, count);
		}
	}
}
