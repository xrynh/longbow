package com.sandwalls.longbow.mixin;

import com.sandwalls.longbow.item.ArcfallLongbowItem;
import com.sandwalls.longbow.logic.ArcfallArrowData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
	private static final double LONGBOW_TRACK_STRENGTH = 0.45;

	@Unique
	private Vec3 longbow$prevPos;

	@Unique
	private Vec3 longbow$lastDir = new Vec3(0.0, -1.0, 0.0);

	@Inject(method = "getDefaultGravity", at = @At("HEAD"), cancellable = true)
	private void longbow$gravity(CallbackInfoReturnable<Double> cir) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (!ArcfallArrowData.isLongbowArrow(self)) {
			return;
		}
		cir.setReturnValue(com.sandwalls.longbow.item.ArcfallLongbowItem.gravityForCharge(ArcfallArrowData.charge(self)));
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void longbow$recordPos(CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		this.longbow$prevPos = self.position();
		if (self.getDeltaMovement().lengthSqr() > 1.0E-6) {
			this.longbow$lastDir = self.getDeltaMovement().normalize();
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void longbow$trackTarget(CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}
		if (!ArcfallArrowData.isLongbowArrow(self)) {
			return;
		}
		int lockId = ArcfallArrowData.lockId(self);
		if (lockId < 0) {
			return;
		}
		Entity target = self.level().getEntity(lockId);
		if (target == null || !target.isAlive()) {
			return;
		}
		Vec3 center = target.getBoundingBox().getCenter();
		Vec3 current = self.getDeltaMovement();
		double speed = Math.max(current.length(), 1.2);
		Vec3 desired = center.subtract(self.position()).normalize().scale(speed);
		self.setDeltaMovement(current.add(desired.subtract(current).scale(LONGBOW_TRACK_STRENGTH)));
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void longbow$noDrag(CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (self.isInWater() || self.isInLava()) {
			return;
		}
		if (!ArcfallArrowData.isLongbowArrow(self)) {
			return;
		}
		Vec3 motion = self.getDeltaMovement();
		if (motion.lengthSqr() > 144.0) {
			return;
		}
		self.setDeltaMovement(motion.scale(1.0 / 0.99));
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void longbow$consumePierce(CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (self.level().isClientSide() || !ArcfallArrowData.isPiercing(self)) {
			return;
		}
		if (this.longbow$prevPos == null) {
			return;
		}
		double moved = self.position().distanceTo(this.longbow$prevPos);
		double remain = ArcfallArrowData.pierceRemaining(self) - moved;
		if (remain <= 0.0) {
			ArcfallArrowData.setPierceRemaining(self, 0.0);
			self.discard();
		} else {
			ArcfallArrowData.setPierceRemaining(self, remain);
		}
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void longbow$trail(CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (self.level().isClientSide() || !ArcfallArrowData.isLongbowArrow(self)) {
			return;
		}
		if (!(self.level() instanceof ServerLevel level)) {
			return;
		}
		if (self.isInLava() || self.isInWater()) {
			return;
		}
		Vec3 pos = self.position();
		Vec3 prev = this.longbow$prevPos == null ? pos : this.longbow$prevPos;
		Vec3 delta = pos.subtract(prev);
		double speed = self.getDeltaMovement().length();
		int hue = (self.tickCount * 24) & 0xFFFFFF;
		int color = java.awt.Color.HSBtoRGB(hue / 255.0F, 0.9F, 1.0F) & 0xFFFFFF;
		// 沿上一 tick 到当前位置补点：主笔尘埃，速度再快尾迹也连续。
		// 彩色烟尘（自定义粒子 longbow:trail_smoke）已按主人要求下线，这里只留原版尘埃；
		// 想加回来的话见 HANDOVER.md §7「烟尘为什么走客户端」——服务端 sendParticles
		// 只发 32 格内的粒子（ServerLevel.java:1364），45 秒的拖尾大半会被丢掉。
		int steps = Math.min(64, Math.max(4, (int) Math.ceil(delta.length() / 0.15)));
		DustParticleOptions trailDust = new DustParticleOptions(color, 1.3F);
		for (int i = 1; i <= steps; i++) {
			Vec3 point = prev.add(delta.scale(i / (double) steps));
			level.sendParticles(trailDust, point.x, point.y, point.z, 1, 0.02, 0.02, 0.02, 0.0);
		}
		// 客户端拖尾点：每 tick 沿位移补点，间距锁 0.15 格（箭 3 格/tick 时约 20 点，
		// 慢速也至少 10 点），这样 45 秒的拖尾是连成一条细亮线而不是一串方片。
		int fxCount = Math.min(28, Math.max(10, (int) Math.ceil(delta.length() / 0.15)));
		for (int i = 1; i <= fxCount; i++) {
			Vec3 point = prev.add(delta.scale(i / (double) fxCount));
			// 最后一个点就是箭头当前位置，标成 head，客户端只在这种点上画星芒
			com.sandwalls.longbow.net.FxBroadcaster.trail(level, point, color, i == fxCount);
		}
		level.sendParticles(new DustParticleOptions(color, 0.8F), pos.x, pos.y - 0.25, pos.z, 1, 0.08, 0.02, 0.08, 0.0);
		if (speed > 2.4) {
			for (int i = 0; i < 4; i++) {
				double angle = i * Math.PI / 2.0 + self.tickCount * 0.15;
				level.sendParticles(ParticleTypes.END_ROD,
					pos.x + Math.cos(angle) * 0.18, pos.y, pos.z + Math.sin(angle) * 0.18,
					1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	@Inject(method = "onHitBlock", at = @At("TAIL"))
	private void longbow$onHitBlock(BlockHitResult result, CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (self.level().isClientSide() || !ArcfallArrowData.isLongbowArrow(self)) {
			return;
		}
		if (ArcfallArrowData.isSpent(self)) {
			self.discard();
			return;
		}
		ArcfallArrowData.markSpent(self);
		int charge = ArcfallArrowData.charge(self);
		ArcfallArrowData.destroy(self.level(), result.getLocation(), this.longbow$lastDir, charge, self);
		self.discard();
	}

	/** 末影人：原版对弹射物会连传 64 次、伤害不结算。这里改用玩家攻击伤害源（非弹射物），它会正常吃伤害且不传送。 */
	@Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
	private void longbow$enderman(EntityHitResult result, CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (self.level().isClientSide() || !ArcfallArrowData.isLongbowArrow(self)) {
			return;
		}
		if (!(result.getEntity() instanceof EnderMan enderman) || !(self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		Entity owner = self.getOwner();
		DamageSource source = owner instanceof net.minecraft.world.entity.player.Player player
			? serverLevel.damageSources().playerAttack(player)
			: serverLevel.damageSources().magic();
		float damage = (float) Math.max(2.0, 2.0 + ArcfallArrowData.charge(self) / 20000.0);
		enderman.hurtServer(serverLevel, source, damage);
		int charge = ArcfallArrowData.charge(self);
		ArcfallArrowData.destroy(serverLevel, enderman.getBoundingBox().getCenter(), this.longbow$lastDir, charge, self);
		ArcfallArrowData.beginPierce(self, com.sandwalls.longbow.item.ArcfallLongbowItem.pierceDistance(charge));
		self.discard();
		ci.cancel();
	}

	@Inject(method = "onHitEntity", at = @At("TAIL"))
	private void longbow$onHitEntity(EntityHitResult result, CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (self.level().isClientSide() || !ArcfallArrowData.isLongbowArrow(self)) {
			return;
		}
		if (ArcfallArrowData.isSpent(self)) {
			return;
		}
		Entity victim = result.getEntity();
		String hitTag = "longbow_hit_" + victim.getId();
		if (self.entityTags().contains(hitTag)) {
			return;
		}
		self.addTag(hitTag);
		Vec3 point = victim.getBoundingBox().getCenter();
		int charge = ArcfallArrowData.charge(self);
		ArcfallArrowData.destroy(self.level(), point, this.longbow$lastDir, charge, self);
		ArcfallArrowData.beginPierce(self, com.sandwalls.longbow.item.ArcfallLongbowItem.pierceDistance(charge));
		ArcfallArrowData.clearLock(self);
	}

	@SuppressWarnings("unused")
	private static boolean longbow$isHoldingBow(Entity owner) {
		if (!(owner instanceof Player player)) {
			return false;
		}
		return player.getMainHandItem().getItem() instanceof ArcfallLongbowItem
			|| player.getOffhandItem().getItem() instanceof ArcfallLongbowItem;
	}
}
