package com.benefvctr.chunkclaims.mixin;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.benefvctr.chunkclaims.server.Protection;

/**
 * After vanilla has worked out which blocks a piston would move or break, refuse
 * the move if any of them (or where they would land) is in someone else's claim.
 * A refused resolve looks to vanilla exactly like an immovable block.
 */
@Mixin(PistonStructureResolver.class)
abstract class PistonStructureResolverMixin {
	@Shadow @Final private Level level;
	@Shadow @Final private BlockPos pistonPos;
	@Shadow @Final private BlockPos startPos;
	@Shadow @Final private Direction pushDirection;
	@Shadow @Final private List<BlockPos> toPush;
	@Shadow @Final private List<BlockPos> toDestroy;

	@Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
	private void chunkclaims$stayOutOfClaims(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !Protection.pistonMayMove(level, pistonPos, startPos, pushDirection, toPush, toDestroy)) {
			cir.setReturnValue(false);
		}
	}
}
