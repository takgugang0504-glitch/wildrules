package kr.wildrules.mixin;

import kr.wildrules.WildRulesMod;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobTargetMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true, require = 0)
    private void wr$ignoreHead(LivingEntity target, CallbackInfo ci) {
        if (target != null && WildRulesMod.ignoresTarget((Mob) (Object) this, target)) ci.cancel();
    }
}
