package com.benefvctr.chunkclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.benefvctr.chunkclaims.server.Protection;

/** {@code checkBurnOut} is where fire destroys (or ignites) a neighbouring block. */
@Mixin(FireBlock.class)
abstract class FireBlockMixin {
	@Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
	private void chunkclaims$noBurningClaims(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
		if (!Protection.fireMayBurn(level, pos)) {
			ci.cancel();
		}
	}
}
