package com.benefvctr.chunkclaims.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.benefvctr.chunkclaims.core.Claim;
import com.benefvctr.chunkclaims.core.ClaimPos;
import com.benefvctr.chunkclaims.core.ClaimRegistry;
import com.benefvctr.chunkclaims.core.ItemCost;
import com.benefvctr.chunkclaims.net.ClaimActionPayload;
import com.benefvctr.chunkclaims.net.ClaimStatusPayload;
import com.benefvctr.chunkclaims.net.ClaimSyncPayload;
import com.benefvctr.chunkclaims.net.PermissionsDataPayload;
import com.benefvctr.chunkclaims.net.SetPermissionPayload;

/**
 * Server-side handling of everything a player can ask for: claiming, unclaiming,
 * the permissions menu, and keeping each client's claim map in sync.
 *
 * <p>Payload handlers run on the server thread (Fabric's default for play
 * payloads), so the check-pay-claim sequence cannot interleave with another
 * player's request.
 */
public final class ClaimService {
	private ClaimService() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(ClaimActionPayload.TYPE, (payload, context) -> {
			ClaimManager manager = ClaimManager.current();
			if (manager == null) {
				return;
			}
			switch (payload.action()) {
				case TOGGLE_CLAIM -> toggleClaim(manager, context.player());
				case OPEN_PERMISSIONS -> openPermissions(manager, context.player());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(SetPermissionPayload.TYPE, (payload, context) -> {
			ClaimManager manager = ClaimManager.current();
			if (manager != null) {
				setPermission(manager, context.player(), payload);
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ClaimManager manager = ClaimManager.current();
			if (manager == null) {
				return;
			}
			ServerPlayer player = handler.getPlayer();
			manager.rememberPlayer(player.getUUID(), player.getGameProfile().name());
			sendStatus(manager, player);
			sendFullSync(manager, player);
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			ClaimManager manager = ClaimManager.current();
			if (manager != null) {
				sendFullSync(manager, player);
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			ClaimManager manager = ClaimManager.current();
			if (manager != null) {
				sendFullSync(manager, newPlayer);
			}
		});
	}

	// ---------------------------------------------------------------- claiming

	private static void toggleClaim(ClaimManager manager, ServerPlayer player) {
		ClaimPos pos = ClaimManager.chunkOf(player.level(), player.blockPosition());
		Claim existing = manager.registry().get(pos);
		if (existing != null && existing.owner().equals(player.getUUID())) {
			unclaim(manager, player, pos);
		} else {
			claim(manager, player, pos);
		}
	}

	private static void claim(ClaimManager manager, ServerPlayer player, ClaimPos pos) {
		UUID me = player.getUUID();
		int buffer = manager.config().bufferChunks();
		if (!manager.config().isClaimable(pos.dimension())) {
			fail(player, Messages.dimensionDisabled());
			return;
		}

		ClaimRegistry.PlacementCheck check = manager.registry().check(pos, me, buffer);
		if (!explainIfBlocked(manager, player, check)) {
			return;
		}

		int owned = manager.registry().countOwned(me);
		List<ItemCost> cost = manager.prices().nextCost(owned);
		boolean free = player.isCreative();
		if (!free) {
			List<ItemCost> missing = Payment.missing(player, cost);
			if (!missing.isEmpty()) {
				fail(player, Messages.cannotAfford(Payment.describe(cost), Payment.describe(missing)));
				return;
			}
		}

		// Same thread, same tick as the check above, so this cannot lose a race,
		// but the registry re-validates under its own lock anyway.
		check = manager.registry().claim(pos, me, buffer);
		if (!explainIfBlocked(manager, player, check)) {
			return;
		}
		if (!free) {
			Payment.take(player, cost);
		}
		manager.markDirty();
		broadcastChange(manager, pos, me, false);
		sendStatus(manager, player);
		player.sendSystemMessage(Messages.claimed(pos.x(), pos.z(),
				free ? Messages.free() : Payment.describe(cost)).withStyle(ChatFormatting.GREEN), true);
	}

	/** Sends the reason a placement failed. Returns true when the placement is OK. */
	private static boolean explainIfBlocked(ClaimManager manager, ServerPlayer player, ClaimRegistry.PlacementCheck check) {
		switch (check.placement()) {
			case OK -> {
				return true;
			}
			case ALREADY_YOURS -> fail(player, Messages.alreadyYours());
			case TAKEN -> fail(player, Messages.taken(manager.nameOf(check.blockingOwner())));
			case TOO_CLOSE -> fail(player, Messages.tooClose(
					manager.nameOf(check.blockingOwner()), manager.config().bufferChunks()));
		}
		return false;
	}

	private static void unclaim(ClaimManager manager, ServerPlayer player, ClaimPos pos) {
		UUID me = player.getUUID();
		int owned = manager.registry().countOwned(me);
		List<ItemCost> refund = manager.prices().refund(owned, manager.config().refundPercent());
		if (!manager.registry().unclaim(pos, me)) {
			fail(player, Messages.notYours());
			return;
		}
		if (!player.isCreative()) {
			Payment.give(player, refund);
		}
		manager.markDirty();
		broadcastChange(manager, pos, me, true);
		sendStatus(manager, player);
		Component paid = refund.isEmpty() || player.isCreative()
				? Messages.nothing()
				: Payment.describe(refund);
		player.sendSystemMessage(Messages.unclaimed(pos.x(), pos.z(), paid).withStyle(ChatFormatting.YELLOW), true);
	}

	// ---------------------------------------------------------------- permissions

	private static void openPermissions(ClaimManager manager, ServerPlayer player) {
		ClaimPos pos = ClaimManager.chunkOf(player.level(), player.blockPosition());
		Claim claim = manager.registry().get(pos);
		if (claim == null || !claim.owner().equals(player.getUUID())) {
			fail(player, Messages.standInOwn());
			return;
		}
		sendPermissions(manager, player, pos);
	}

	private static void sendPermissions(ClaimManager manager, ServerPlayer player, ClaimPos anchor) {
		Claim claim = manager.registry().get(anchor);
		if (claim == null) {
			return;
		}
		Set<ClaimPos> cluster = manager.registry().cluster(anchor);
		List<PermissionsDataPayload.Row> rows = new ArrayList<>();
		manager.knownPlayers().forEach((id, name) -> {
			if (!id.equals(claim.owner())) {
				boolean online = manager.server().getPlayerList().getPlayer(id) != null;
				rows.add(new PermissionsDataPayload.Row(id, name, claim.trusted().contains(id), online));
			}
		});
		rows.sort(Comparator.comparing(PermissionsDataPayload.Row::name, String.CASE_INSENSITIVE_ORDER));
		if (ServerPlayNetworking.canSend(player, PermissionsDataPayload.TYPE)) {
			ServerPlayNetworking.send(player, new PermissionsDataPayload(anchor.dimension(), anchor.x(), anchor.z(), cluster.size(), rows));
		}
	}

	private static void setPermission(ClaimManager manager, ServerPlayer player, SetPermissionPayload payload) {
		ClaimPos anchor = new ClaimPos(payload.dimension(), payload.anchorX(), payload.anchorZ());
		if (!anchor.dimension().equals(ClaimManager.dimensionId(player.level()))
				|| !manager.knownPlayers().containsKey(payload.target())) {
			return;
		}
		int changed = manager.registry().setTrusted(anchor, player.getUUID(), payload.target(), payload.allowed());
		if (changed == 0) {
			fail(player, Messages.notYours());
			return;
		}
		manager.markDirty();
		String name = manager.nameOf(payload.target());
		player.sendSystemMessage((payload.allowed() ? Messages.trusted(name, changed) : Messages.untrusted(name, changed))
				.withStyle(payload.allowed() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
		// Echo the server's view back so the open menu always shows the truth.
		sendPermissions(manager, player, anchor);
	}

	// ---------------------------------------------------------------- sync

	/** Re-sends claims and status to everyone, for changes made outside the normal hotkey flow. */
	public static void resyncAll(ClaimManager manager) {
		for (ServerPlayer player : manager.server().getPlayerList().getPlayers()) {
			sendStatus(manager, player);
			sendFullSync(manager, player);
		}
	}

	static void sendStatus(ClaimManager manager, ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, ClaimStatusPayload.TYPE)) {
			return;
		}
		int owned = manager.registry().countOwned(player.getUUID());
		ServerPlayNetworking.send(player, new ClaimStatusPayload(
				owned,
				manager.prices().nextCost(owned),
				manager.prices().refund(owned, manager.config().refundPercent()),
				manager.config().bufferChunks(),
				manager.config().claimableDimensions()));
	}

	/** Replaces the client's claim map with every claim in the player's current dimension. */
	static void sendFullSync(ClaimManager manager, ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, ClaimSyncPayload.TYPE)) {
			return;
		}
		String dimension = ClaimManager.dimensionId(player.level());
		List<Map.Entry<Long, Claim>> all = new ArrayList<>(manager.registry().claimsIn(dimension).entrySet());
		int start = 0;
		do {
			List<Map.Entry<Long, Claim>> batch = all.subList(start, Math.min(all.size(), start + ClaimSyncPayload.MAX_ENTRIES));
			Map<UUID, Integer> ownerIndex = new HashMap<>();
			List<ClaimSyncPayload.Owner> owners = new ArrayList<>();
			List<ClaimSyncPayload.Entry> entries = new ArrayList<>(batch.size());
			for (Map.Entry<Long, Claim> e : batch) {
				UUID owner = e.getValue().owner();
				int index = ownerIndex.computeIfAbsent(owner, id -> {
					owners.add(new ClaimSyncPayload.Owner(id, manager.nameOf(id)));
					return owners.size() - 1;
				});
				entries.add(new ClaimSyncPayload.Entry(ClaimPos.unpackX(e.getKey()), ClaimPos.unpackZ(e.getKey()), index));
			}
			ServerPlayNetworking.send(player, new ClaimSyncPayload(dimension, start == 0, owners, entries, List.of()));
			start += ClaimSyncPayload.MAX_ENTRIES;
		} while (start < all.size());
	}

	/** Tells every modded player in that dimension about one claim or unclaim. */
	private static void broadcastChange(ClaimManager manager, ClaimPos pos, UUID owner, boolean removed) {
		ClaimSyncPayload delta = removed
				? new ClaimSyncPayload(pos.dimension(), false, List.of(), List.of(), List.of(pos.packed()))
				: new ClaimSyncPayload(pos.dimension(), false,
						List.of(new ClaimSyncPayload.Owner(owner, manager.nameOf(owner))),
						List.of(new ClaimSyncPayload.Entry(pos.x(), pos.z(), 0)), List.of());
		for (ServerPlayer p : manager.server().getPlayerList().getPlayers()) {
			if (ClaimManager.dimensionId(p.level()).equals(pos.dimension()) && ServerPlayNetworking.canSend(p, ClaimSyncPayload.TYPE)) {
				ServerPlayNetworking.send(p, delta);
			}
		}
	}

	static void fail(ServerPlayer player, Component message) {
		player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED), true);
	}
}
