package com.benefvctr.chunkclaims.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PriceTableTest {
	private static final String COBBLE = "minecraft:cobblestone";
	private static final String COAL = "minecraft:coal";
	private static final String IRON = "minecraft:iron_ingot";
	private static final String DIAMOND = "minecraft:diamond";

	private final PriceTable table = new PriceTable(ClaimsConfig.defaults().prices());

	@Test
	void earlyChunksCostStarterResources() {
		assertEquals(List.of(new ItemCost(COBBLE, 32)), table.priceOfChunk(1));
		assertEquals(List.of(new ItemCost(COBBLE, 32)), table.priceOfChunk(2));
		assertEquals(List.of(new ItemCost(COAL, 16)), table.priceOfChunk(3));
		assertEquals(List.of(new ItemCost(IRON, 16)), table.priceOfChunk(8));
	}

	@Test
	void middleTierMixesIronAndDiamond() {
		assertEquals(List.of(new ItemCost(IRON, 16), new ItemCost(DIAMOND, 1)), table.priceOfChunk(9));
	}

	@Test
	void laterChunksCostDiamonds() {
		assertEquals(List.of(new ItemCost(DIAMOND, 2)), table.priceOfChunk(13));
		assertEquals(List.of(new ItemCost(DIAMOND, 4)), table.priceOfChunk(24));
		assertEquals(List.of(new ItemCost(DIAMOND, 16)), table.priceOfChunk(48));
	}

	@Test
	void finalTierGrowsAndCaps() {
		assertEquals(32, table.priceOfChunk(49).getFirst().count());
		assertEquals(32, table.priceOfChunk(64).getFirst().count());
		assertEquals(40, table.priceOfChunk(65).getFirst().count());
		assertEquals(48, table.priceOfChunk(81).getFirst().count());
		assertEquals(64, table.priceOfChunk(500).getFirst().count());
	}

	@Test
	void nextCostIsThePriceOfTheNextChunk() {
		assertEquals(table.priceOfChunk(1), table.nextCost(0));
		assertEquals(table.priceOfChunk(13), table.nextCost(12));
	}

	@Test
	void refundIsHalfOfTheTierYourLastChunkSitsIn() {
		assertEquals(List.of(new ItemCost(COBBLE, 16)), table.refund(1, 50));
		assertEquals(List.of(new ItemCost(DIAMOND, 1)), table.refund(13, 50));
		// 1 diamond halves to zero and is dropped rather than refunded as an empty stack.
		assertEquals(List.of(new ItemCost(IRON, 8)), table.refund(9, 50));
		assertTrue(table.refund(0, 50).isEmpty());
	}

	@Test
	void refundNeverExceedsWhatRebuyingCosts() {
		// Unclaim then reclaim must never be a resource farm.
		for (int owned = 1; owned <= 300; owned++) {
			List<ItemCost> refund = table.refund(owned, 50);
			List<ItemCost> rebuy = table.nextCost(owned - 1);
			for (ItemCost r : refund) {
				int paid = rebuy.stream().filter(c -> c.item().equals(r.item())).mapToInt(ItemCost::count).sum();
				assertTrue(r.count() <= paid, "owned=" + owned + " " + r);
			}
		}
	}

	@Test
	void rejectsLaddersThatAreOutOfOrder() {
		assertThrows(IllegalArgumentException.class, () -> new PriceTable(List.of(
				PriceTier.upTo(10, new ItemCost(COAL, 1)),
				PriceTier.upTo(5, new ItemCost(COAL, 2)))));
		assertThrows(IllegalArgumentException.class, () -> new PriceTable(List.of()));
	}
}
