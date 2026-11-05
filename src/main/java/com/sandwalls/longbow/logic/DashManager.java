package com.sandwalls.longbow.logic;

import com.sandwalls.longbow.item.ArcfallLongbowItem;
import com.sandwalls.longbow.item.LongbowItems;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「虹」的疾行档位：0=关闭，1=马速（0.1→0.5 格/tick），2=鞘翅速（0.1→1.2 格/tick）。
 * 按一次升一档，到 2 档后再按回 0 档；收起「虹」自动回 0 档。
 */
public final class DashManager {
	public static final int GEAR_OFF = 0;
	public static final int GEAR_HORSE = 1;
	public static final int GEAR_ELYTRA = 2;
	private static final Identifier MODIFIER_ID = Identifier.fromNamespaceAndPath("longbow", "ballad_dash");
	/** 每档对 MOVEMENT_SPEED 的加成倍率（ADD_MULTIPLIED_BASE，基础 0.1）。 */
	private static final double[] GEAR_MULTIPLIER = {0.0, 4.0, 11.0};
	private static final Map<UUID, Integer> GEARS = new HashMap<>();

	private DashManager() {}

	public static void set(ServerPlayer player, int gear) {
		gear = Math.max(GEAR_OFF, Math.min(GEAR_ELYTRA, gear));
		UUID id = player.getUUID();
		if (gear > GEAR_OFF && !canDash(player)) {
			com.sandwalls.longbow.LongbowMod.LOGGER.info("[longbow] dash rejected: main hand = {}",
				player.getMainHandItem().getItem());
			return;
		}
		GEARS.put(id, gear);
		apply(player, gear);
		com.sandwalls.longbow.LongbowMod.LOGGER.info("[longbow] dash set gear={} hand={} speed={}",
			gear, player.getMainHandItem().getItem(), player.getAttributeValue(Attributes.MOVEMENT_SPEED));
	}

	public static int gear(ServerPlayer player) {
		return GEARS.getOrDefault(player.getUUID(), GEAR_OFF);
	}

	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();
			int gear = GEARS.getOrDefault(id, GEAR_OFF);
			if (gear > GEAR_OFF && !canDash(player)) {
				GEARS.put(id, GEAR_OFF);
				apply(player, GEAR_OFF);
			}
		}
	}

	/** 主手或副手持有「虹」或长弓时都允许疾行。 */
	private static boolean canDash(ServerPlayer player) {
		return player.getMainHandItem().is(LongbowItems.ARCFALL_BALLAD)
			|| player.getOffhandItem().is(LongbowItems.ARCFALL_BALLAD)
			|| player.getMainHandItem().getItem() instanceof ArcfallLongbowItem
			|| player.getOffhandItem().getItem() instanceof ArcfallLongbowItem;
	}

	private static void apply(ServerPlayer player, int gear) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		speed.removeModifier(MODIFIER_ID);
		if (gear > GEAR_OFF) {
			speed.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER_ID, GEAR_MULTIPLIER[gear],
				AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
		}
		// 飞行状态下的速度由 flyingSpeed 决定，不看 MOVEMENT_SPEED，这里一起调
		float baseFlying = 0.05F;
		player.getAbilities().setFlyingSpeed(gear == GEAR_OFF ? baseFlying
			: (float) (baseFlying * (1.0 + GEAR_MULTIPLIER[gear])));
		player.onUpdateAbilities();
	}
}
