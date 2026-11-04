package kr.wildrules.mixin;

import kr.wildrules.WildRulesMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EndCrystal.class)
public abstract class EndCrystalMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true, require = 0)
    private void wr$hit(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!WildRulesMod.crystalHit(level, (EndCrystal) (Object) this, source)) cir.setReturnValue(false);
    }
}
