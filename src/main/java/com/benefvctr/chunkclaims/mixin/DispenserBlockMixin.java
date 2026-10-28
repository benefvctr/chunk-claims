package com.benefvctr.chunkclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.benefvctr.chunkclaims.server.Protection;

/**
 * A dispenser outside a claim cannot fire into it (lava buckets, flint and steel,
 * TNT). Droppers override this method and are left alone: dropping items into
 * someone's claim gives them things rather than taking them.
 */
@Mixin(DispenserBlock.class)
abstract class DispenserBlockMixin {
	@Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
	private void chunkclaims$noFiringIntoClaims(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
		if (Protection.crossesIntoForeignClaim(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) {
			ci.cancel();
		}
	}
}
