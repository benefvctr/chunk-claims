package com.benefvctr.chunkclaims.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.benefvctr.chunkclaims.ChunkClaims;

/**
 * Server to client: everything the permissions menu shows for one cluster.
 * The anchor chunk identifies the cluster in later {@link SetPermissionPayload}s.
 */
public record PermissionsDataPayload(String dimension, int anchorX, int anchorZ, int clusterSize, List<Row> rows)
		implements CustomPacketPayload {

	public static final Type<PermissionsDataPayload> TYPE = new Type<>(ChunkClaims.id("permissions"));
	public static final StreamCodec<FriendlyByteBuf, PermissionsDataPayload> CODEC = StreamCodec.ofMember(PermissionsDataPayload::write, PermissionsDataPayload::read);

	public record Row(UUID id, String name, boolean allowed, boolean online) {
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(dimension);
		buf.writeVarInt(anchorX);
		buf.writeVarInt(anchorZ);
		buf.writeVarInt(clusterSize);
		buf.writeVarInt(rows.size());
		for (Row r : rows) {
			buf.writeUUID(r.id());
			buf.writeUtf(r.name(), 64);
			buf.writeBoolean(r.allowed());
			buf.writeBoolean(r.online());
		}
	}

	private static PermissionsDataPayload read(FriendlyByteBuf buf) {
		String dimension = buf.readUtf();
		int x = buf.readVarInt();
		int z = buf.readVarInt();
		int size = buf.readVarInt();
		int count = buf.readVarInt();
		List<Row> rows = new ArrayList<>(Math.min(count, 4096));
		for (int i = 0; i < count; i++) {
			rows.add(new Row(buf.readUUID(), buf.readUtf(64), buf.readBoolean(), buf.readBoolean()));
		}
		return new PermissionsDataPayload(dimension, x, z, size, rows);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
