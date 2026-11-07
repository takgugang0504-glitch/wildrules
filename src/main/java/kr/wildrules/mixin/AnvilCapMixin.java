package kr.wildrules.mixin;

import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** 모루 "너무 비쌉니다": 40레벨 제한 해제 (다른 모루 기능과 분리해서 하나가 실패해도 이것은 적용되도록) */
@Mixin(AnvilMenu.class)
public abstract class AnvilCapMixin {
    @ModifyConstant(method = {"createResult", "updateResult"}, constant = @Constant(intValue = 40), require = 0)
    private int wr$noCap(int original) {
        return Integer.MAX_VALUE;
    }
}
