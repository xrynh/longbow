package com.sandwalls.longbow.client;

import net.minecraft.world.entity.Entity;

/** 客户端记录"已确认锁定"的目标，用于白框渲染与状态提示。 */
public final class ClientLock {
	private static int lockedEntityId = -1;

	private ClientLock() {}

	public static void lock(Entity entity) {
		lockedEntityId = entity == null ? -1 : entity.getId();
	}

	public static void unlock() {
		lockedEntityId = -1;
	}

	public static int lockedId() {
		return lockedEntityId;
	}

	public static Entity resolve(net.minecraft.world.level.Level level) {
		if (lockedEntityId < 0 || level == null) {
			return null;
		}
		Entity entity = level.getEntity(lockedEntityId);
		if (entity == null || !entity.isAlive()) {
			lockedEntityId = -1;
			return null;
		}
		return entity;
	}
}
