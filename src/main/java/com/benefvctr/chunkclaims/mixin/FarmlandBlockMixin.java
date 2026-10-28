package com.benefvctr.chunkclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.benefvctr.chunkclaims.server.Protection;

/** Unauthorized players (and their mounts) cannot trample crops in a claim. */
@Mixin(FarmlandBlock.class)
abstract class FarmlandBlockMixin {
	@Inject(method = "turnToBaseBlock", at = @At("HEAD"), cancellable = true)
	private void chunkclaims$noTrampling(Entity entity, BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
		if (!Protection.mayTrample(entity, level, pos)) {
			ci.cancel();
		}
	}
}
