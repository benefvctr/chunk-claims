package com.benefvctr.chunkclaims.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.benefvctr.chunkclaims.core.Claim;
import com.benefvctr.chunkclaims.core.ClaimPos;

/**
 * Anti-griefing. Player actions are stopped through Fabric API callbacks; the
 * environmental cases (explosions, pistons, fluids, dispensers, fire, trampling,
 * projectile damage) come in through the mixins, which call the static checks here.
 *
 * <p>Every check is server-side only. Callbacks also fire on the client, but the
 * client does not know who is trusted, so it passes and lets the server decide.
 */
public final class Protection {
	/** Minimum gap between "this land belongs to..." messages per player. */
	private static final long MESSAGE_COOLDOWN_MS = 1500;
	private static final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

	private Protection() {
	}

	public static void register() {
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (mayModify(player, level, pos)) {
				return true;
			}
			deny(player, level, pos);
			return false;
		});

		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (mayModify(player, level, pos)) {
				return InteractionResult.PASS;
			}
			deny(player, level, pos);
			return InteractionResult.FAIL;
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			BlockPos clicked = hit.getBlockPos();
			// Placing goes into the neighbouring block, which may be inside a claim even
			// when the clicked block is not. Only check it when something is in hand,
			// so opening your own chest right at a claim edge keeps working.
			BlockPos placed = clicked.relative(hit.getDirection());
			boolean holding = !player.getItemInHand(hand).isEmpty();
			if (mayModify(player, level, clicked) && (!holding || mayModify(player, level, placed))) {
				return InteractionResult.PASS;
			}
			deny(player, level, mayModify(player, level, clicked) ? placed : clicked);
			return InteractionResult.FAIL;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			Item item = player.getItemInHand(hand).getItem();
			// These items act on a block they ray-trace to themselves (fluids, water
			// surfaces), so they never reach UseBlockCallback.
			if (!(item instanceof BucketItem || item instanceof BoatItem || item instanceof PlaceOnWaterBlockItem)
					|| !(level instanceof ServerLevel)) {
				return InteractionResult.PASS;
			}
			Vec3 eye = player.getEyePosition();
			Vec3 reach = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
			BlockHitResult hit = level.clip(new ClipContext(eye, reach, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
			if (hit.getType() == HitResult.Type.MISS) {
				return InteractionResult.PASS;
			}
			for (BlockPos target : new BlockPos[] {hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection())}) {
				if (!mayModify(player, level, target)) {
					deny(player, level, target);
					return InteractionResult.FAIL;
				}
			}
			return InteractionResult.PASS;
		});

		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (entity instanceof Player || mayModify(player, level, entity.blockPosition())) {
				return InteractionResult.PASS;
			}
			deny(player, level, entity.blockPosition());
			return InteractionResult.FAIL;
		});

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!isShieldedEntity(entity) || mayModify(player, level, entity.blockPosition())) {
				return InteractionResult.PASS;
			}
			deny(player, level, entity.blockPosition());
			return InteractionResult.FAIL;
		});
	}

	// ---------------------------------------------------------------- lookups

	private static Claim claimAt(Level level, BlockPos pos) {
		ClaimManager manager = ClaimManager.current();
		if (manager == null || !(level instanceof ServerLevel)) {
			return null;
		}
		return manager.registry().get(ClaimManager.chunkOf(level, pos));
	}

	/** May this player break, place or interact at {@code pos}? Always true client-side. */
	public static boolean mayModify(Player player, Level level, BlockPos pos) {
		Claim claim = claimAt(level, pos);
		return claim == null || claim.allows(player.getUUID());
	}

	/**
	 * True when something at {@code from} would change a block at {@code to} that sits
	 * in a claim owned by someone other than whoever owns {@code from} (or when
	 * {@code from} is unclaimed). Same-owner moves are always fine.
	 */
	public static boolean crossesIntoForeignClaim(Level level, BlockPos from, BlockPos to) {
		if ((from.getX() >> 4) == (to.getX() >> 4) && (from.getZ() >> 4) == (to.getZ() >> 4)) {
			return false; // same chunk, nothing crosses a border
		}
		Claim target = claimAt(level, to);
		if (target == null) {
			return false;
		}
		Claim source = claimAt(level, from);
		return source == null || !source.owner().equals(target.owner());
	}

	// ---------------------------------------------------------------- mixin hooks

	/**
	 * Explosions keep every block in a claim intact (and do not toggle doors or buttons there).
	 * The result must stay mutable: vanilla shuffles this list in place afterwards.
	 */
	public static List<BlockPos> filterExplosion(ServerLevel level, List<BlockPos> positions) {
		ClaimManager manager = ClaimManager.current();
		if (manager == null || positions.isEmpty()) {
			return positions;
		}
		String dimension = ClaimManager.dimensionId(level);
		List<BlockPos> kept = new ArrayList<>(positions.size());
		for (BlockPos p : positions) {
			if (manager.registry().get(new ClaimPos(dimension, p.getX() >> 4, p.getZ() >> 4)) == null) {
				kept.add(p);
			}
		}
		return kept.size() == positions.size() ? positions : kept;
	}

	/** Animals, villagers, item frames, armor stands, vehicles... but not players or monsters. */
	private static boolean isShieldedEntity(Entity entity) {
		return !(entity instanceof Player) && !(entity instanceof Enemy);
	}

	/**
	 * Called from {@code Entity.isInvulnerableToBase}. Shields non-hostile entities in
	 * claims from explosions and from players without permission, projectiles included.
	 */
	public static boolean shieldsEntity(Entity entity, DamageSource source) {
		if (!isShieldedEntity(entity) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}
		Claim claim = claimAt(entity.level(), entity.blockPosition());
		if (claim == null) {
			return false;
		}
		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			return true;
		}
		return source.getEntity() instanceof Player attacker && !claim.allows(attacker.getUUID());
	}

	/** Pistons may not push, pull or break blocks across into someone else's claim. */
	public static boolean pistonMayMove(Level level, BlockPos piston, BlockPos start, Direction push, List<BlockPos> toPush, List<BlockPos> toDestroy) {
		if (crossesIntoForeignClaim(level, piston, start)) {
			return false;
		}
		for (BlockPos p : toPush) {
			if (crossesIntoForeignClaim(level, piston, p) || crossesIntoForeignClaim(level, piston, p.relative(push))) {
				return false;
			}
		}
		for (BlockPos p : toDestroy) {
			if (crossesIntoForeignClaim(level, piston, p)) {
				return false;
			}
		}
		return true;
	}

	/** Fluids may flow within a claim or out of it, never into one from outside. */
	public static boolean fluidMayFlow(LevelAccessor level, BlockPos from, BlockPos to) {
		return !(level instanceof Level real) || !crossesIntoForeignClaim(real, from, to);
	}

	/** Fire never burns blocks away inside a claim. */
	public static boolean fireMayBurn(Level level, BlockPos pos) {
		return claimAt(level, pos) == null;
	}

	/** Farmland only turns to dirt under players (or their mounts) who may build there. */
	public static boolean mayTrample(Entity entity, Level level, BlockPos pos) {
		Player player = entity instanceof Player p ? p
				: entity != null && entity.getControllingPassenger() instanceof Player rider ? rider : null;
		return player == null || mayModify(player, level, pos);
	}

	// ---------------------------------------------------------------- feedback

	private static void deny(Player player, Level level, BlockPos pos) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		// The client may have predicted the action (a block in hand going down by
		// one, say); resend the inventory so it snaps back to what the server has.
		serverPlayer.inventoryMenu.sendAllDataToRemote();
		serverPlayer.containerMenu.sendAllDataToRemote();

		long now = System.currentTimeMillis();
		Long last = lastMessage.get(player.getUUID());
		if (last != null && now - last < MESSAGE_COOLDOWN_MS) {
			return;
		}
		lastMessage.put(player.getUUID(), now);
		Claim claim = claimAt(level, pos);
		ClaimManager manager = ClaimManager.current();
		if (claim != null && manager != null) {
			serverPlayer.sendSystemMessage(Messages.protectedLand(manager.nameOf(claim.owner())).withStyle(ChatFormatting.RED), true);
		}
	}
}
