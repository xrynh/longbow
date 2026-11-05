package com.sandwalls.longbow.logic;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 服务端保存每个玩家当前的锁定目标（实体 id），客户端只保存准星目标用于渲染。 */
public final class LockManager {
	private static final Map<UUID, Integer> SERVER_TARGETS = new HashMap<>();

	private LockManager() {}

	public static void setServerTarget(Player player, int entityId) {
		if (entityId < 0) {
			SERVER_TARGETS.remove(player.getUUID());
		} else {
			SERVER_TARGETS.put(player.getUUID(), entityId);
		}
	}

	public static void clearServerTarget(Player player) {
		SERVER_TARGETS.remove(player.getUUID());
	}

	public static Entity getServerTarget(Player player) {
		Integer id = SERVER_TARGETS.get(player.getUUID());
		if (id == null) {
			return null;
		}
		Level level = player.level();
		Entity entity = level.getEntity(id);
		if (entity == null || !entity.isAlive()) {
			SERVER_TARGETS.remove(player.getUUID());
			return null;
		}
		if (entity.distanceToSqr(player) > 128.0 * 128.0) {
			SERVER_TARGETS.remove(player.getUUID());
			return null;
		}
		return entity;
	}
}
