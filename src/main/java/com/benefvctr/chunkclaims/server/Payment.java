package com.benefvctr.chunkclaims.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.benefvctr.chunkclaims.core.ItemCost;

/**
 * Takes claim payments from, and pays refunds into, a player's main inventory
 * (hotbar included, armor and offhand never touched).
 */
final class Payment {
	private Payment() {
	}

	/** What the player is still missing; empty means they can pay. */
	static List<ItemCost> missing(ServerPlayer player, List<ItemCost> cost) {
		List<ItemCost> short_ = new ArrayList<>();
		NonNullList<ItemStack> slots = player.getInventory().getNonEquipmentItems();
		for (ItemCost c : cost) {
			Item item = item(c).orElse(null);
			int have = 0;
			if (item != null) {
				for (ItemStack stack : slots) {
					if (stack.getItem() == item) {
						have += stack.getCount();
					}
				}
			}
			if (have < c.count()) {
				short_.add(new ItemCost(c.item(), c.count() - have));
			}
		}
		return short_;
	}

	/** Removes the items. Only call after {@link #missing} came back empty on this tick. */
	static void take(ServerPlayer player, List<ItemCost> cost) {
		NonNullList<ItemStack> slots = player.getInventory().getNonEquipmentItems();
		for (ItemCost c : cost) {
			Item item = item(c).orElseThrow();
			int left = c.count();
			for (int i = 0; i < slots.size() && left > 0; i++) {
				ItemStack stack = slots.get(i);
				if (stack.getItem() == item) {
					int used = Math.min(left, stack.getCount());
					stack.shrink(used);
					left -= used;
				}
			}
		}
		player.getInventory().setChanged();
		player.inventoryMenu.broadcastChanges();
	}

	/** Gives items back; anything that does not fit drops at the player's feet. */
	static void give(ServerPlayer player, List<ItemCost> refund) {
		for (ItemCost c : refund) {
			Item item = item(c).orElse(null);
			if (item == null) {
				continue;
			}
			int left = c.count();
			while (left > 0) {
				ItemStack stack = new ItemStack(item, Math.min(left, item.getDefaultMaxStackSize()));
				left -= stack.getCount();
				if (!player.getInventory().add(stack) && !stack.isEmpty()) {
					ItemEntity dropped = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), stack);
					dropped.setNoPickUpDelay();
					player.level().addFreshEntity(dropped);
				}
			}
		}
		player.inventoryMenu.broadcastChanges();
	}

	/** "16 Iron Ingot, 1 Diamond" with translated item names. */
	static Component describe(List<ItemCost> costs) {
		MutableComponent out = Component.empty();
		for (int i = 0; i < costs.size(); i++) {
			ItemCost c = costs.get(i);
			if (i > 0) {
				out.append(", ");
			}
			out.append(c.count() + " ").append(item(c).map(it -> Component.translatable(it.getDescriptionId())).orElse(Component.literal(c.item())));
		}
		return out;
	}

	static Optional<Item> item(ItemCost cost) {
		Identifier id = Identifier.tryParse(cost.item());
		return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
	}
}
