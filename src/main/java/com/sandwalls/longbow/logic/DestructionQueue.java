package com.sandwalls.longbow.logic;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 沿弹道方向的圆柱体破坏：圆柱中心轴 = 箭命中瞬间的速度方向。
 * 半径与长度由蓄能决定，逐 tick 分帧执行；入队瞬间秒杀圆柱内生物。
 */
public final class DestructionQueue {
	private static final Deque<Task> TASKS = new ArrayDeque<>();
	private static final int BLOCKS_PER_TICK = 6000;

	private DestructionQueue() {}

	public static void enqueue(ServerLevel level, Vec3 center, Vec3 direction, double radius, double height, double killRadius, Entity source, boolean preserveOres) {
		Entity owner = source;
		if (source instanceof net.minecraft.world.entity.projectile.Projectile projectile && projectile.getOwner() != null) {
			owner = projectile.getOwner();
		}
		Vec3 dir = direction.lengthSqr() < 1.0E-6 ? new Vec3(0.0, -1.0, 0.0) : direction.normalize();
		killCreatures(level, center, dir, killRadius, height, owner);
		// 轻击档：radius <= 0 只杀怪，不排入方块破坏
		if (radius >= 0.1) {
			TASKS.add(new Task(level, center, dir, radius, height, owner, preserveOres));
		}
	}

	public static void tick() {
		int budget = BLOCKS_PER_TICK;
		while (budget > 0 && !TASKS.isEmpty()) {
			Task task = TASKS.peek();
			budget -= task.step(budget);
			if (task.done) {
				TASKS.poll();
			}
		}
	}

	public static boolean hasWork() {
		return !TASKS.isEmpty();
	}

	/** 判断点是否落在以 center 为起点、沿 dir 长 height、半径 radius 的圆柱内。 */
	public static boolean insideCylinder(Vec3 point, Vec3 center, Vec3 dir, double radius, double height) {
		return insideCylinder(point, center, dir, radius, height, 0.0);
	}

	public static boolean insideCylinder(Vec3 point, Vec3 center, Vec3 dir, double radius, double height, double backMargin) {
		Vec3 rel = point.subtract(center);
		double t = rel.dot(dir);
		if (t < -backMargin || t > height) {
			return false;
		}
		return rel.lengthSqr() - t * t <= radius * radius;
	}

	private static void killCreatures(ServerLevel level, Vec3 center, Vec3 dir, double radius, double height, Entity owner) {
		Vec3 end = center.add(dir.scale(height));
		AABB box = new AABB(center, end).inflate(radius + 1.0);
		List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box);
		// 范围击杀必须记录玩家归属：原版 LivingEntity.dropExperience 只在
		// lastHurtByPlayerMemoryTime > 0 时掉经验，该值由 resolvePlayerResponsibleForDamage
		// 在 DamageSource.getEntity() 是 Player 时写入。纯 magic() 的 getEntity() 为 null，
		// 会导致波及致死的生物不掉经验、击杀统计也不记到玩家头上。
		DamageSource source = owner instanceof net.minecraft.world.entity.player.Player player
			? level.damageSources().source(DamageTypes.MAGIC, player)
			: level.damageSources().magic();
		for (LivingEntity entity : targets) {
			if (entity == owner) {
				continue;
			}
			if (!insideCylinder(entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0), center, dir, radius, height, 1.5)
				&& !insideCylinder(entity.position(), center, dir, radius, height, 1.5)) {
				continue;
			}
			entity.invulnerableTime = 0;
			entity.hurtServer(level, source, 1.0E9F);
		}
	}

	private static final class Task {
		private final ServerLevel level;
		private final Vec3 center;
		private final Vec3 dir;
		private final double radius;
		private final double height;
		private final Entity owner;
		private final boolean preserveOres;
		private final long[] targets;
		private int index = 0;
		private boolean done = false;

		Task(ServerLevel level, Vec3 center, Vec3 dir, double radius, double height, Entity owner, boolean preserveOres) {
			this.level = level;
			this.center = center;
			this.dir = dir;
			this.radius = radius;
			this.height = height;
			this.owner = owner;
			this.preserveOres = preserveOres;
			this.targets = buildTargets(center, dir, radius, height);
		}

		private static long[] buildTargets(Vec3 center, Vec3 dir, double radius, double height) {
			Vec3 end = center.add(dir.scale(height));
			AABB box = new AABB(center, end).inflate(radius + 1.0);
			LongArrayList list = new LongArrayList();
			int minX = (int) Math.floor(box.minX);
			int minY = (int) Math.floor(box.minY);
			int minZ = (int) Math.floor(box.minZ);
			int maxX = (int) Math.floor(box.maxX);
			int maxY = (int) Math.floor(box.maxY);
			int maxZ = (int) Math.floor(box.maxZ);
			double r2 = radius * radius;
			for (int x = minX; x <= maxX; x++) {
				for (int y = minY; y <= maxY; y++) {
					for (int z = minZ; z <= maxZ; z++) {
						Vec3 rel = new Vec3(x + 0.5 - center.x, y + 0.5 - center.y, z + 0.5 - center.z);
						double t = rel.dot(dir);
						if (t < -0.5 || t > height + 0.5) {
							continue;
						}
						if (rel.lengthSqr() - t * t > r2) {
							continue;
						}
						list.add(BlockPos.asLong(x, y, z));
					}
				}
			}
			return list.toLongArray();
		}

		private boolean protectsOwner(BlockPos pos) {
			if (this.owner == null || !this.owner.isAlive()) {
				return false;
			}
			double ox = this.owner.getX();
			double oy = this.owner.getY();
			double oz = this.owner.getZ();
			return Math.abs(pos.getX() + 0.5 - ox) < 1.6
				&& Math.abs(pos.getZ() + 0.5 - oz) < 1.6
				&& pos.getY() >= Math.floor(oy) - 2
				&& pos.getY() <= Math.floor(oy) + 1;
		}

		int step(int budget) {
			int processed = 0;
			while (processed < budget && index < targets.length) {
				BlockPos pos = BlockPos.of(targets[index++]);
				processed++;
				if (this.protectsOwner(pos)) {
					continue;
				}
				BlockState state = level.getBlockState(pos);
				if (this.preserveOres && MiningModeManager.isProtectedOre(state)) {
					continue;
				}
				if (!state.isAir() && state.getDestroySpeed(level, pos) >= 0.0F
					&& !state.is(Blocks.BEDROCK) && !state.is(Blocks.BARRIER)
					&& !state.is(Blocks.COMMAND_BLOCK) && !state.is(Blocks.CHAIN_COMMAND_BLOCK)
					&& !state.is(Blocks.REPEATING_COMMAND_BLOCK) && !state.is(Blocks.END_PORTAL_FRAME)
					&& !state.is(Blocks.END_PORTAL) && !state.is(Blocks.NETHER_PORTAL)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
					if (index % 500 == 0) {
						level.sendParticles(ParticleTypes.EXPLOSION, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
					}
				}
			}
			if (index >= targets.length) {
				done = true;
			}
			return Math.max(1, processed);
		}
	}
}
