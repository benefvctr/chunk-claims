package com.benefvctr.chunkclaims.core;

import java.util.List;

/**
 * One step of the price ladder.
 *
 * @param upTo      last chunk number (1-based, inclusive) this tier prices; 0 means "and beyond"
 * @param cost      items charged for each chunk in this tier
 * @param growEvery optional: every this many chunks past the tier start, add {@code growBy}
 *                  to every item count (0 disables growth)
 * @param growBy    amount added per growth step
 * @param maxCount  optional cap for grown counts (0 means no cap)
 */
public record PriceTier(int upTo, List<ItemCost> cost, int growEvery, int growBy, int maxCount) {

	public PriceTier {
		cost = List.copyOf(cost);
	}

	public static PriceTier upTo(int upTo, ItemCost... cost) {
		return new PriceTier(upTo, List.of(cost), 0, 0, 0);
	}

	public boolean unbounded() {
		return upTo <= 0;
	}
}
