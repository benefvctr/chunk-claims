package com.benefvctr.chunkclaims.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.benefvctr.chunkclaims.server.Protection;

/**
 * Removes claimed blocks from an explosion's block list before it is used, so
 * blocks there are neither destroyed, set on fire, nor toggled (doors, buttons).
 * Entity damage is handled separately in {@link EntityMixin}.
 */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {
	@ModifyExpressionValue(method = "explode", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/ServerExplosion;calculateExplodedPositions()Ljava/util/List;"))
	private List<BlockPos> chunkclaims$spareClaimedBlocks(List<BlockPos> positions) {
		return Protection.filterExplosion(((ServerExplosion) (Object) this).level(), positions);
	}
}
