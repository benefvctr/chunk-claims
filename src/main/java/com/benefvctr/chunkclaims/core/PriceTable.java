package com.benefvctr.chunkclaims.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns "how many chunks do you own" into "what does the next one cost".
 * Prices depend only on the count, so every player climbs the same ladder.
 */
public final class PriceTable {
	private final List<PriceTier> tiers;

	public PriceTable(List<PriceTier> tiers) {
		if (tiers.isEmpty()) {
			throw new IllegalArgumentException("price ladder needs at least one tier");
		}
		int previous = 0;
		for (int i = 0; i < tiers.size(); i++) {
			PriceTier tier = tiers.get(i);
			boolean last = i == tiers.size() - 1;
			if (tier.unbounded() && !last) {
				throw new IllegalArgumentException("only the last tier may be open-ended (upTo 0)");
			}
			if (!tier.unbounded() && tier.upTo() <= previous) {
				throw new IllegalArgumentException("tier upTo values must increase: " + tier.upTo() + " after " + previous);
			}
			if (tier.cost().isEmpty() || tier.cost().stream().anyMatch(c -> c.count() <= 0)) {
				throw new IllegalArgumentException("every tier needs at least one item with a positive count");
			}
			previous = Math.max(previous, tier.upTo());
		}
		this.tiers = List.copyOf(tiers);
	}

	/** Price of the {@code n}-th chunk a player owns (1-based). */
	public List<ItemCost> priceOfChunk(int n) {
		if (n < 1) {
			throw new IllegalArgumentException("chunk number starts at 1");
		}
		int tierStart = 1;
		for (PriceTier tier : tiers) {
			if (tier.unbounded() || n <= tier.upTo()) {
				return priced(tier, n - tierStart);
			}
			tierStart = tier.upTo() + 1;
		}
		// Past the last bounded tier: keep pricing with it, growth included.
		PriceTier last = tiers.getLast();
		int lastStart = tiers.size() == 1 ? 1 : tiers.get(tiers.size() - 2).upTo() + 1;
		return priced(last, n - lastStart);
	}

	private static List<ItemCost> priced(PriceTier tier, int indexInTier) {
		if (tier.growEvery() <= 0 || tier.growBy() <= 0) {
			return tier.cost();
		}
		int extra = tier.growBy() * (indexInTier / tier.growEvery());
		List<ItemCost> out = new ArrayList<>(tier.cost().size());
		for (ItemCost c : tier.cost()) {
			int count = c.count() + extra;
			if (tier.maxCount() > 0) {
				count = Math.min(count, tier.maxCount());
			}
			out.add(new ItemCost(c.item(), count));
		}
		return out;
	}

	/** What a player who owns {@code owned} chunks pays for their next one. */
	public List<ItemCost> nextCost(int owned) {
		return priceOfChunk(owned + 1);
	}

	/**
	 * What a player who owns {@code owned} chunks gets back for returning one:
	 * {@code percent} of the tier their most recent chunk sits in (the price of
	 * buying it back), rounded down. Items that round to zero are dropped.
	 */
	public List<ItemCost> refund(int owned, int percent) {
		if (owned <= 0) {
			return List.of();
		}
		List<ItemCost> out = new ArrayList<>();
		for (ItemCost c : priceOfChunk(owned)) {
			int back = (int) ((long) c.count() * percent / 100);
			if (back > 0) {
				out.add(new ItemCost(c.item(), back));
			}
		}
		return out;
	}
}
