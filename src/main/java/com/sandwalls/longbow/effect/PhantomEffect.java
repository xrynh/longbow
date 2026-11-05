package com.sandwalls.longbow.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** 「幻」：持有长虹陨落之歌时的常驻状态，纯展示用（像中毒那样带图标，但无害且无限期）。 */
public class PhantomEffect extends MobEffect {
	public PhantomEffect() {
		super(MobEffectCategory.NEUTRAL, 0x66E0FF);
	}

	@Override
	public net.minecraft.network.chat.Component getDisplayName() {
		return com.sandwalls.longbow.item.RainbowText.of(
			net.minecraft.network.chat.Component.translatable(this.getDescriptionId()).getString());
	}
}
