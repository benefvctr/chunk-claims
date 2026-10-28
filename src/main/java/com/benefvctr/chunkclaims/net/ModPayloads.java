package com.benefvctr.chunkclaims.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Registers every payload type. Runs on both sides from the common initializer. */
public final class ModPayloads {
	private ModPayloads() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(ClaimSyncPayload.TYPE, ClaimSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ClaimStatusPayload.TYPE, ClaimStatusPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PermissionsDataPayload.TYPE, PermissionsDataPayload.CODEC);

		PayloadTypeRegistry.serverboundPlay().register(ClaimActionPayload.TYPE, ClaimActionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetPermissionPayload.TYPE, SetPermissionPayload.CODEC);
	}
}
