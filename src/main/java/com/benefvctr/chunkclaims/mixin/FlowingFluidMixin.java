package com.benefvctr.chunkclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.benefvctr.chunkclaims.server.Protection;

/**
 * Stops lava and water from flowing into a claim from outside it. {@code direction}
 * is the flow direction, so the source block is one step back against it.
 */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
	@Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
	private void chunkclaims$noFlowingIntoClaims(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState fluid, CallbackInfo ci) {
		if (!Protection.fluidMayFlow(level, pos.relative(direction.getOpposite()), pos)) {
			ci.cancel();
		}
	}
}
