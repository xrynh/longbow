package com.sandwalls.longbow.logic;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 探矿模式：开着的时候圆形破坏会跳过矿物方块，只清石头类。 */
public final class MiningModeManager {
	private static final Set<UUID> ENABLED = new HashSet<>();

	private MiningModeManager() {}

	public static void set(Player player, boolean on) {
		if (on) {
			ENABLED.add(player.getUUID());
		} else {
			ENABLED.remove(player.getUUID());
		}
	}

	public static boolean isEnabled(Player player) {
		return player != null && ENABLED.contains(player.getUUID());
	}

	/** 判断方块是不是"要保下来的矿物"（含远古残骸，它不在任何 ores 标签里）。 */
	public static boolean isProtectedOre(BlockState state) {
		return state.is(BlockTags.COAL_ORES)
			|| state.is(BlockTags.IRON_ORES)
			|| state.is(BlockTags.COPPER_ORES)
			|| state.is(BlockTags.GOLD_ORES)
			|| state.is(BlockTags.REDSTONE_ORES)
			|| state.is(BlockTags.EMERALD_ORES)
			|| state.is(BlockTags.LAPIS_ORES)
			|| state.is(BlockTags.DIAMOND_ORES)
			|| state.is(Blocks.ANCIENT_DEBRIS)
			|| state.is(Blocks.NETHER_QUARTZ_ORE)
			|| state.is(Blocks.NETHER_GOLD_ORE);
	}
}
