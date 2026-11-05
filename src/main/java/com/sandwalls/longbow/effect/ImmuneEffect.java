package com.sandwalls.longbow.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「水火不侵」：无限期的抗火 + 水下呼吸 + 水下速掘。
 * 每 tick 把三个原版效果以无限时长挂到玩家身上（隐藏图标，只保留本状态）。
 */
public class ImmuneEffect extends MobEffect {
	public ImmuneEffect() {
		super(MobEffectCategory.BENEFICIAL, 0x33AADD);
	}

	@Override
	public boolean applyEffectTick(ServerLevel level, LivingEntity mob, int amplifier) {
		mob.setRemainingFireTicks(0);
		mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,
			MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
		mob.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING,
			MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
		mob.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER,
			MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
		return true;
	}

	@Override
	public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
		return true;
	}

	@Override
	public net.minecraft.network.chat.Component getDisplayName() {
		return com.sandwalls.longbow.item.RainbowText.of(
			net.minecraft.network.chat.Component.translatable(this.getDescriptionId()).getString());
	}
}
