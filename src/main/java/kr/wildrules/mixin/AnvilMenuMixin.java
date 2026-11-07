package kr.wildrules.mixin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 모루: "너무 비쌉니다"(40레벨 제한) 해제 + 비용 = 20, 25, 30 ... (사용 횟수마다 +5, 책/인챈트 비용이 더 크면 그 값 유지) */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Shadow @Final private DataSlot cost;

    @ModifyConstant(method = "createResult", constant = @Constant(intValue = 40), require = 0)
    private int wr$noCap(int original) {
        return Integer.MAX_VALUE;
    }

    private static int uses(ItemStack st) {
        Integer rc = st.get(DataComponents.REPAIR_COST);
        if (rc == null || rc <= 0) return 0;
        return 31 - Integer.numberOfLeadingZeros(rc + 1);      // 2^n - 1 = 수리 비용 -> n 번 사용
    }
    private static int penalty(ItemStack st) {
        Integer rc = st.get(DataComponents.REPAIR_COST);
        return rc == null ? 0 : rc;
    }

    @Inject(method = "createResult", at = @At("RETURN"), require = 0)
    private void wr$cost(CallbackInfo ci) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        int vanilla = this.cost.get();
        if (vanilla <= 0 || !self.getSlot(2).hasItem()) return;
        ItemStack left = self.getSlot(0).getItem();
        ItemStack right = self.getSlot(1).getItem();
        int base = Math.max(1, vanilla - penalty(left) - penalty(right));       // 누적 페널티를 뺀 원래 비용
        int fixed = Math.max(base, 20 + 5 * uses(left));
        this.cost.set(fixed);
    }
}
