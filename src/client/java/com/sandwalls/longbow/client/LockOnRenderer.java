package com.sandwalls.longbow.client;

import com.sandwalls.longbow.item.ArcfallLongbowItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 世界空间白框的目标查询：准星指向的实体显示细白框，已锁定的实体显示粗白框。
 *
 * <p>这里只算出"要框哪个 {@link AABB}"，<b>不负责画</b>——画的部分在
 * {@link ClientFxManager} 里统一走自建的 FX 批次（和拖尾、箭十字同一个 RenderType）。</p>
 *
 * <p>原先这里是自己注册 {@code LevelRenderEvents.BEFORE_GIZMOS} 然后用
 * {@code Gizmos.cuboid(...)} 画的。gizmo 走的是 {@code debug_filled_box} /
 * {@code debug_lines} 管线，这两条都不在 Iris 的 shader 覆盖白名单里，开光影时
 * 控制台会刷 {@code Missing program ... in override list}，框会画成满屏蓝色乱块。
 * 所以改成走面片管线，光影开关下表现一致。</p>
 */
public final class LockOnRenderer {
	private LockOnRenderer() {}

	/** 手里是不是拿着长虹弓（主手或副手）。没拿就不画框。 */
	public static boolean isHolding(Minecraft mc) {
		Player player = mc.player;
		if (player == null) {
			return false;
		}
		return player.getMainHandItem().getItem() instanceof ArcfallLongbowItem
			|| player.getOffhandItem().getItem() instanceof ArcfallLongbowItem;
	}

	/** 准星指向的实体的框；没有目标返回 {@code null}。 */
	public static AABB aimedBox(Minecraft mc) {
		Entity aimed = ClientAim.aimed();
		if (aimed == null && mc.hitResult instanceof EntityHitResult ehr) {
			aimed = ehr.getEntity();
		}
		return aimed == null ? null : aimed.getBoundingBox().inflate(0.03);
	}

	/** 已锁定的实体的框；没有锁定返回 {@code null}。 */
	public static AABB lockedBox(Minecraft mc) {
		Entity locked = ClientLock.resolve(mc.level);
		return locked == null ? null : locked.getBoundingBox().inflate(0.12);
	}
}
