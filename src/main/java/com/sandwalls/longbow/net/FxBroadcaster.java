package com.sandwalls.longbow.net;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class FxBroadcaster {
	private FxBroadcaster() {}

	public static void trail(ServerLevel level, Vec3 pos, int color) {
		trail(level, pos, color, false);
	}

	/**
	 * 发一个拖尾点。
	 *
	 * @param head 是否是"这一 tick 的最后一个点"；客户端只在这种点上画箭头星芒，
	 *             因为沿轨迹补的中间点不是箭头当前位置
	 */
	public static void trail(ServerLevel level, Vec3 pos, int color, boolean head) {
		send(level, new FxPayload(FxPayload.TRAIL, pos.x, pos.y, pos.z, head ? 1.0 : 0.0, 0.0, 0.0, color, 0.03F), pos, 192.0);
	}

	public static void burst(ServerLevel level, Vec3 pos, float radius) {
		send(level, new FxPayload(FxPayload.BURST, pos.x, pos.y, pos.z, 0.0, 1.0, 0.0, 0xFFFFFF, radius), pos, 192.0);
	}

	public static void cast(ServerLevel level, Vec3 pos, Vec3 dir, float radius) {
		send(level, new FxPayload(FxPayload.CAST, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, 0xFFFFFF, radius), pos, 192.0);
	}

	private static void send(ServerLevel level, FxPayload payload, Vec3 pos, double range) {
		double rangeSq = range * range;
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (player.level() == level && player.distanceToSqr(pos) <= rangeSq) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}
}
