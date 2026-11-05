package com.sandwalls.longbow.item;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** 把一段文字逐字染成彩虹色（用于物品名）。 */
public final class RainbowText {
	private RainbowText() {}

	public static MutableComponent of(String text) {
		MutableComponent out = Component.empty();
		int length = Math.max(1, text.length());
		for (int i = 0; i < text.length(); i++) {
			int color = java.awt.Color.HSBtoRGB(i / (float) length, 0.85F, 1.0F) & 0xFFFFFF;
			out.append(Component.literal(String.valueOf(text.charAt(i)))
				.withStyle(style -> style.withColor(color)));
		}
		return out;
	}
}
