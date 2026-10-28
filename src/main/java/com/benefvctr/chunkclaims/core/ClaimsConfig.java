package com.benefvctr.chunkclaims.core;

import java.util.List;

/**
 * Server settings, stored as {@code config/chunkclaims.json}.
 *
 * @param bufferChunks        free chunks required between two players' land (Chebyshev distance)
 * @param refundPercent       share of the current tier price returned on unclaim
 * @param claimableDimensions dimension ids where claiming is allowed; empty means all
 * @param prices              the price ladder, cheapest first
 */
public record ClaimsConfig(int bufferChunks, int refundPercent, List<String> claimableDimensions, List<PriceTier> prices) {

	private static final String COBBLESTONE = "minecraft:cobblestone";
	private static final String COAL = "minecraft:coal";
	private static final String IRON = "minecraft:iron_ingot";
	private static final String DIAMOND = "minecraft:diamond";

	public ClaimsConfig {
		claimableDimensions = claimableDimensions == null ? List.of() : List.copyOf(claimableDimensions);
		prices = prices == null ? List.of() : List.copyOf(prices);
	}

	public static ClaimsConfig defaults() {
		return new ClaimsConfig(2, 50, List.of(), List.of(
				PriceTier.upTo(2, new ItemCost(COBBLESTONE, 32)),
				PriceTier.upTo(4, new ItemCost(COAL, 16)),
				PriceTier.upTo(8, new ItemCost(IRON, 16)),
				PriceTier.upTo(12, new ItemCost(IRON, 16), new ItemCost(DIAMOND, 1)),
				PriceTier.upTo(16, new ItemCost(DIAMOND, 2)),
				PriceTier.upTo(24, new ItemCost(DIAMOND, 4)),
				PriceTier.upTo(32, new ItemCost(DIAMOND, 8)),
				PriceTier.upTo(48, new ItemCost(DIAMOND, 16)),
				new PriceTier(0, List.of(new ItemCost(DIAMOND, 32)), 16, 8, 64)));
	}

	public boolean isClaimable(String dimension) {
		return claimableDimensions.isEmpty() || claimableDimensions.contains(dimension);
	}

	/** Fixes values a hand-edited file could break, falling back to defaults. */
	public ClaimsConfig sanitized() {
		ClaimsConfig d = defaults();
		return new ClaimsConfig(
				Math.max(0, Math.min(bufferChunks, 16)),
				Math.max(0, Math.min(refundPercent, 100)),
				claimableDimensions,
				prices.isEmpty() ? d.prices() : prices);
	}
}
