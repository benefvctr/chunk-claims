package com.benefvctr.chunkclaims.core;

/**
 * An amount of one item, by registry id (e.g. {@code minecraft:diamond}).
 * Kept as a plain string so pricing stays testable without a registry.
 */
public record ItemCost(String item, int count) {
}
