package com.benefvctr.chunkclaims.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.benefvctr.chunkclaims.server.Protection;

/**
 * {@code isInvulnerableToBase} is the shared check every entity's damage path runs
 * through (living entities, item frames, paintings, armor stands, vehicles), so one
 * hook covers melee, arrows, tridents and explosions alike.
 */
@Mixin(Entity.class)
abstract class EntityMixin {
	@Inject(method = "isInvulnerableToBase", at = @At("HEAD"), cancellable = true)
	private void chunkclaims$shieldClaimedEntities(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		if (Protection.shieldsEntity((Entity) (Object) this, source)) {
			cir.setReturnValue(true);
		}
	}
}
