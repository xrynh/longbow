package com.sandwalls.longbow.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** 客户端远程瞄准：沿视线做 64 格实体射线，突破原版交互距离（3~5 格）的限制。 */
public final class ClientAim {
	public static final double RANGE = 64.0;
	private static Entity aimed;

	private ClientAim() {}

	public static void tick(Minecraft mc) {
		aimed = null;
		Player player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 end = eye.add(look.scale(RANGE));
		AABB box = player.getBoundingBox().expandTowards(look.scale(RANGE)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box,
			entity -> !entity.isSpectator() && entity.canBeHitByProjectile(), RANGE * RANGE);
		if (hit != null) {
			aimed = hit.getEntity();
		}
	}

	public static Entity aimed() {
		return aimed;
	}
}
