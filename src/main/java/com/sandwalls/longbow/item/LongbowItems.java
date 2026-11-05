package com.sandwalls.longbow.item;

import com.sandwalls.longbow.LongbowMod;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import java.util.function.Function;

public final class LongbowItems {
	public static final Item ARCFALL_BALLAD = register("arcfall_ballad",
		ArcfallBalladItem::new, new Item.Properties().rarity(Rarity.UNCOMMON).stacksTo(16));

	public static final Item ARCFALL_LONGBOW = register("arcfall_longbow",
		ArcfallLongbowItem::new, new Item.Properties().rarity(Rarity.EPIC).stacksTo(1).durability(1024).fireResistant());

	private LongbowItems() {}

	private static <T extends Item> T register(String name, Function<Item.Properties, T> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(LongbowMod.MOD_ID, name));
		T item = factory.apply(properties.setId(key));
		Registry.register(BuiltInRegistries.ITEM, key, item);
		return item;
	}

	public static void initialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT)
			.register(tab -> tab.accept(ARCFALL_LONGBOW));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS)
			.register(tab -> tab.accept(ARCFALL_BALLAD));
	}
}
