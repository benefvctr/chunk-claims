package com.benefvctr.chunkclaims.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import com.benefvctr.chunkclaims.core.ItemCost;
import com.benefvctr.chunkclaims.net.ClaimStatusPayload;

/**
 * The small panel in the top-left corner while edit mode is on: what the chunk
 * under you is, what claiming or unclaiming it costs or returns, and the keys.
 */
final class ClaimHud {
	private static final int PAD = 5;
	private static final int LINE = 11;
	private static final int ICON_ROW = 18;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA8A8A8;
	private static final int BLUE = 0xFF6FA8FF;
	private static final int GREEN = 0xFF6BFF84;
	private static final int RED = 0xFFFF6B6B;
	private static final int PURPLE = 0xFFC88CFF;

	private ClaimHud() {
	}

	/** One line of the panel: text, optionally followed by item icons. */
	private record Line(Component text, int color, List<ItemCost> items) {
		Line(Component text, int color) {
			this(text, color, List.of());
		}

		int height() {
			return items.isEmpty() ? LINE : ICON_ROW;
		}
	}

	static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!ClientClaimState.editMode() || mc.player == null) {
			return;
		}
		List<Line> lines = new ArrayList<>();
		int accent = describe(mc, lines);
		draw(graphics, mc.font, accent, lines);
	}

	/** Fills {@code lines} for the chunk the player stands in and returns the accent colour. */
	private static int describe(Minecraft mc, List<Line> lines) {
		lines.add(new Line(Component.translatable("chunkclaims.hud.title").append("  ")
				.append(Component.translatable("chunkclaims.hud.exit", key(ChunkClaimsClient.toggleEditKey.getTranslatedKeyMessage()))), GRAY));

		ClaimStatusPayload status = ClientClaimState.status();
		if (status == null) {
			lines.add(new Line(Component.translatable("chunkclaims.hud.no_server"), RED));
			return RED;
		}

		UUID self = mc.player.getUUID();
		int cx = mc.player.blockPosition().getX() >> 4;
		int cz = mc.player.blockPosition().getZ() >> 4;
		UUID owner = ClientClaimState.ownerAt(cx, cz);
		Component claimKey = key(ChunkClaimsClient.claimKey.getTranslatedKeyMessage());
		Component owned = plural("chunkclaims.hud.owned", status.owned());
		int accent;

		if (self.equals(owner)) {
			accent = BLUE;
			lines.add(new Line(plural("chunkclaims.hud.own", ClientClaimState.clusterSize(cx, cz)), BLUE));
			lines.add(status.refund().isEmpty()
					? new Line(Component.translatable("chunkclaims.hud.unclaim_free", claimKey), WHITE)
					: new Line(Component.translatable("chunkclaims.hud.unclaim_for", claimKey), WHITE, status.refund()));
			lines.add(new Line(Component.translatable("chunkclaims.hud.permissions", key(ChunkClaimsClient.permissionsKey.getTranslatedKeyMessage())), WHITE));
		} else if (owner != null) {
			accent = PURPLE;
			lines.add(new Line(Component.translatable("chunkclaims.hud.foreign", ClientClaimState.nameOf(owner)), PURPLE));
			lines.add(new Line(Component.translatable("chunkclaims.hud.foreign_hint"), GRAY));
		} else if (!ClientClaimState.dimensionClaimable()) {
			accent = RED;
			lines.add(new Line(Component.translatable("chunkclaims.hud.disabled"), RED));
		} else {
			UUID blocker = ClientClaimState.blockingOwner(cx, cz, self);
			if (blocker != null) {
				accent = RED;
				lines.add(new Line(Component.translatable("chunkclaims.hud.buffer", ClientClaimState.nameOf(blocker)), RED));
				lines.add(new Line(Component.translatable("chunkclaims.hud.buffer_hint", status.buffer()), GRAY));
			} else {
				accent = GREEN;
				lines.add(new Line(Component.translatable("chunkclaims.hud.available"), GREEN));
				lines.add(new Line(Component.translatable("chunkclaims.hud.claim_for", claimKey), WHITE, status.nextCost()));
			}
		}
		lines.add(new Line(owned, GRAY));
		return accent;
	}

	/** Picks the {@code _one} variant of a key for a count of exactly one. */
	static Component plural(String key, int count) {
		return Component.translatable(count == 1 ? key + "_one" : key, count);
	}

	private static Component key(Component name) {
		return Component.literal("[").append(name).append("]");
	}

	private static void draw(GuiGraphicsExtractor graphics, Font font, int accent, List<Line> lines) {
		int width = 0;
		int height = 0;
		for (Line line : lines) {
			width = Math.max(width, font.width(line.text()) + (line.items().isEmpty() ? 0 : 4 + line.items().size() * 18));
			height += line.height();
		}
		int x = 4;
		int y = 4;
		graphics.fill(x, y, x + width + PAD * 2 + 2, y + height + PAD * 2 - 2, 0xB0101014);
		graphics.fill(x, y, x + 2, y + height + PAD * 2 - 2, accent);

		int cursorY = y + PAD;
		for (Line line : lines) {
			int textY = cursorY + (line.height() - 8) / 2;
			graphics.text(font, line.text(), x + PAD + 2, textY, line.color(), true);
			int iconX = x + PAD + 2 + font.width(line.text()) + 4;
			for (ItemCost cost : line.items()) {
				ItemStack stack = stackOf(cost);
				graphics.item(stack, iconX, cursorY + 1);
				graphics.itemDecorations(font, stack, iconX, cursorY + 1, String.valueOf(cost.count()));
				iconX += 18;
			}
			cursorY += line.height();
		}
	}

	static ItemStack stackOf(ItemCost cost) {
		Identifier id = Identifier.tryParse(cost.item());
		return id == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
	}
}
