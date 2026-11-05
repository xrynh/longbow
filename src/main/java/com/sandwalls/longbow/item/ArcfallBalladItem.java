package com.sandwalls.longbow.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 「虹」：彩虹色的过渡材料。 */
public class ArcfallBalladItem extends Item {
	public ArcfallBalladItem(Properties properties) {
		super(properties);
	}

	@Override
	public Component getName(ItemStack stack) {
		return RainbowText.of(Component.translatable(this.getDescriptionId()).getString());
	}
}
