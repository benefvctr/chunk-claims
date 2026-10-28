package com.benefvctr.chunkclaims.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.benefvctr.chunkclaims.ChunkClaims;
import com.benefvctr.chunkclaims.net.ClaimActionPayload;
import com.benefvctr.chunkclaims.net.ClaimStatusPayload;
import com.benefvctr.chunkclaims.net.ClaimSyncPayload;
import com.benefvctr.chunkclaims.net.PermissionsDataPayload;

/**
 * Client entrypoint: hotkeys, the ground overlay, the edit-mode HUD and the
 * permissions menu. Never loaded on a dedicated server.
 */
public final class ChunkClaimsClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(ChunkClaims.id("claims"));

	static KeyMapping toggleEditKey;
	static KeyMapping claimKey;
	static KeyMapping permissionsKey;

	@Override
	public void onInitializeClient() {
		toggleEditKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.chunkclaims.toggle_edit", InputConstants.KEY_G, CATEGORY));
		claimKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.chunkclaims.claim", InputConstants.KEY_H, CATEGORY));
		permissionsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.chunkclaims.permissions", InputConstants.KEY_J, CATEGORY));

		ClientPlayNetworking.registerGlobalReceiver(ClaimSyncPayload.TYPE, (payload, context) -> ClientClaimState.apply(payload));
		ClientPlayNetworking.registerGlobalReceiver(ClaimStatusPayload.TYPE, (payload, context) -> ClientClaimState.apply(payload));
		ClientPlayNetworking.registerGlobalReceiver(PermissionsDataPayload.TYPE, (payload, context) -> {
			// The server echoes fresh data after every toggle; refresh an open menu in place.
			if (context.client().gui.screen() instanceof PermissionsScreen open && open.isFor(payload)) {
				open.update(payload);
			} else {
				context.client().gui.setScreen(new PermissionsScreen(payload));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> ClientClaimState.reset());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			handleKeys(client);
			ClaimOverlay.tick(client);
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(ClaimOverlay::submit);
		HudElementRegistry.addLast(ChunkClaims.id("edit_mode"), ClaimHud::extract);
	}

	private static void handleKeys(Minecraft client) {
		if (client.player == null) {
			return;
		}
		while (toggleEditKey.consumeClick()) {
			boolean on = ClientClaimState.toggleEditMode();
			if (on && !ClientPlayNetworking.canSend(ClaimActionPayload.TYPE)) {
				client.player.sendOverlayMessage(Component.translatable("chunkclaims.hud.no_server").withStyle(ChatFormatting.RED));
			} else {
				client.player.sendOverlayMessage(Component.translatable(on ? "chunkclaims.hud.edit_on" : "chunkclaims.hud.edit_off"));
			}
		}
		while (claimKey.consumeClick()) {
			sendAction(ClaimActionPayload.Action.TOGGLE_CLAIM);
		}
		while (permissionsKey.consumeClick()) {
			sendAction(ClaimActionPayload.Action.OPEN_PERMISSIONS);
		}
	}

	/** Claim and permission keys only do something inside edit mode. */
	private static void sendAction(ClaimActionPayload.Action action) {
		if (ClientClaimState.editMode() && ClientPlayNetworking.canSend(ClaimActionPayload.TYPE)) {
			ClientPlayNetworking.send(new ClaimActionPayload(action));
		}
	}
}
