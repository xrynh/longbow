package com.sandwalls.longbow.mixin;

import com.sandwalls.longbow.item.ArcfallLongbowItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
	/** 持长弓时视为"正确工具"，方块会正常掉落，破坏修正值也从 100 降到 30。 */
	@Inject(method = "hasCorrectToolForDrops", at = @At("HEAD"), cancellable = true)
	private void longbow$correctTool(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (self.getMainHandItem().getItem() instanceof ArcfallLongbowItem) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
	private void longbow$lightningMining(BlockState state, CallbackInfoReturnable<Float> cir) {
		Player self = (Player) (Object) this;
		ItemStack held = self.getMainHandItem();
		if (held.getItem() instanceof ArcfallLongbowItem) {
			float current = cir.getReturnValueF();
			float boosted = Math.max(current, 160.0F);
			cir.setReturnValue(boosted);
		}
	}
}
