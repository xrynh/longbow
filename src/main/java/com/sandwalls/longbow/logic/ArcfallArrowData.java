package com.sandwalls.longbow.logic;

import com.sandwalls.longbow.item.ArcfallLongbowItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** 用实体 tag 标记"长虹箭"，记录蓄能与锁定目标 ID。 */
public final class ArcfallArrowData {
	public static final String TAG = "longbow_arrow";
	private static final String CHARGE_PREFIX = "longbow_charge_";
	private static final String LOCK_PREFIX = "longbow_lock_";
	private static final String PIERCE_PREFIX = "longbow_pierce_";
	private static final String PIERCE_FLAG = "longbow_piercing";
	private static final String SPENT_FLAG = "longbow_spent";

	private ArcfallArrowData() {}

	public static void mark(Entity arrow, int charge, Entity lock) {
		arrow.addTag(TAG);
		arrow.addTag(CHARGE_PREFIX + charge);
		if (lock != null) {
			arrow.addTag(LOCK_PREFIX + lock.getId());
		}
	}

	public static void markSpent(Entity entity) {
		entity.addTag(SPENT_FLAG);
	}

	public static boolean isSpent(Entity entity) {
		return entity.entityTags().contains(SPENT_FLAG);
	}

	public static void beginPierce(Entity entity, double distance) {
		if (entity.entityTags().contains(PIERCE_FLAG)) {
			return;
		}
		entity.addTag(PIERCE_FLAG);
		setPierceRemaining(entity, Math.max(0.0, distance));
	}

	public static boolean isPiercing(Entity entity) {
		return entity.entityTags().contains(PIERCE_FLAG);
	}

	public static void setPierceRemaining(Entity entity, double distance) {
		java.util.List<String> old = new java.util.ArrayList<>();
		for (String tag : entity.entityTags()) {
			if (tag.startsWith(PIERCE_PREFIX)) {
				old.add(tag);
			}
		}
		for (String tag : old) {
			entity.removeTag(tag);
		}
		entity.addTag(PIERCE_PREFIX + distance);
	}

	public static double pierceRemaining(Entity entity) {
		for (String tag : entity.entityTags()) {
			if (tag.startsWith(PIERCE_PREFIX)) {
				try {
					return Double.parseDouble(tag.substring(PIERCE_PREFIX.length()));
				} catch (NumberFormatException ignored) {
					return 0.0;
				}
			}
		}
		return 0.0;
	}

	public static void clearLock(Entity entity) {
		java.util.List<String> toRemove = new java.util.ArrayList<>();
		for (String tag : entity.entityTags()) {
			if (tag.startsWith(LOCK_PREFIX)) {
				toRemove.add(tag);
			}
		}
		for (String tag : toRemove) {
			entity.removeTag(tag);
		}
	}

	public static boolean isLongbowArrow(Entity entity) {
		return entity.entityTags().contains(TAG);
	}

	public static int charge(Entity entity) {
		for (String tag : entity.entityTags()) {
			if (tag.startsWith(CHARGE_PREFIX)) {
				try {
					return Integer.parseInt(tag.substring(CHARGE_PREFIX.length()));
				} catch (NumberFormatException ignored) {
					return 0;
				}
			}
		}
		return 0;
	}

	public static int lockId(Entity entity) {
		for (String tag : entity.entityTags()) {
			if (tag.startsWith(LOCK_PREFIX)) {
				try {
					return Integer.parseInt(tag.substring(LOCK_PREFIX.length()));
				} catch (NumberFormatException ignored) {
					return -1;
				}
			}
		}
		return -1;
	}

	/** 按蓄能值生成圆柱体破坏（半径与高度同时随蓄能增长），并秒杀范围内生物。 */
	public static void destroy(Level level, Vec3 center, Vec3 direction, int charge, Entity arrow) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		double radius = ArcfallLongbowItem.blastRadius(charge);
		double height = ArcfallLongbowItem.blastHeight(charge);
		double killRadius = ArcfallLongbowItem.killRadius(charge);
		boolean preserveOres = arrow instanceof net.minecraft.world.entity.projectile.Projectile projectile
			&& projectile.getOwner() instanceof net.minecraft.world.entity.player.Player shooter
			&& MiningModeManager.isEnabled(shooter);
		DestructionQueue.enqueue(serverLevel, center, direction, radius, height, killRadius, arrow, preserveOres);
		int bursts = (int) Math.min(40, 4 + radius * 1.5);
		serverLevel.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, bursts,
			radius * 0.35, height * 0.25, radius * 0.35, 0.0);
		for (int i = 0; i < 40; i++) {
			double a = serverLevel.getRandom().nextDouble() * Math.PI * 2;
			double rr = serverLevel.getRandom().nextDouble() * radius;
			int color = java.awt.Color.HSBtoRGB(serverLevel.getRandom().nextFloat(), 0.85F, 1.0F) & 0xFFFFFF;
			serverLevel.sendParticles(new net.minecraft.core.particles.DustParticleOptions(color, 1.4F),
				center.x + Math.cos(a) * rr, center.y + (serverLevel.getRandom().nextDouble() - 0.5) * height,
				center.z + Math.sin(a) * rr, 1, 0.0, 0.1, 0.0, 0.0);
		}
	}
}
