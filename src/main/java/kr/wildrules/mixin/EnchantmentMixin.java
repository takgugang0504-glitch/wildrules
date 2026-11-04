package kr.wildrules.mixin;

import kr.wildrules.WildRulesMod;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Enchantment.class)
public abstract class EnchantmentMixin {
    @Inject(method = "getMaxLevel", at = @At("HEAD"), cancellable = true, require = 0)
    private void wr$maxLevel(CallbackInfoReturnable<Integer> cir) {
        Enchantment self = (Enchantment) (Object) this;
        if (self.description().getContents() instanceof TranslatableContents tc) {
            Integer v = WildRulesMod.MAX_LEVELS.get(tc.getKey());
            if (v != null) cir.setReturnValue(v);
        }
    }
}
