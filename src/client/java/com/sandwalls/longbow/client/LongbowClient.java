package com.sandwalls.longbow.client;

import com.sandwalls.longbow.item.ArcfallLongbowItem;
import com.sandwalls.longbow.logic.LockPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LongbowClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("longbow-client");
	public static boolean prospectMode() { return prospectMode; }
	private static int autoTicks = 0;
	private static net.minecraft.client.KeyMapping dashKey;
	private static net.minecraft.client.KeyMapping prospectKey;
	private static boolean prospectMode = false;
	private static boolean dashKeyWasDown = false;
	private static boolean wasHoldingBallad = false;
	private static boolean autoRan = false;

	@Override
	public void onInitializeClient() {
		dashKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
			new net.minecraft.client.KeyMapping("key.longbow.dash",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
				org.lwjgl.glfw.GLFW.GLFW_KEY_G,
				net.minecraft.client.KeyMapping.Category.GAMEPLAY));

		LOGGER.info("[longbow] dash key registered: {}", dashKey.getName());
		prospectKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
			new net.minecraft.client.KeyMapping("key.longbow.prospect",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
				org.lwjgl.glfw.GLFW.GLFW_KEY_H,
				net.minecraft.client.KeyMapping.Category.GAMEPLAY));

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("longbow", "charge_hud"), new LongbowHud());
		ClientFxManager.register();

		// 拖尾旁的彩色小球：把 provider 挂到粒子类型上。少了这一步，粒子会被静默丢掉，
		// 日志里只有一句 "Missing particle sprites"（烟尘那轮踩过）。
		net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance().register(
			com.sandwalls.longbow.fx.LongbowParticles.TRAIL_ORB,
			com.sandwalls.longbow.client.particle.TrailOrbParticle.Provider::new);
		LOGGER.info("[longbow] particle provider registered: longbow:trail_orb");
		ClientPreAttackCallback.EVENT.register((client, player, interactionCount) -> {
			if (player == null) {
				return false;
			}
			boolean holding = player.getMainHandItem().getItem() instanceof ArcfallLongbowItem
				|| player.getOffhandItem().getItem() instanceof ArcfallLongbowItem;
			if (!holding) {
				return false;
			}
			Entity aimed = ClientAim.aimed();
			if (aimed == null && client.hitResult instanceof EntityHitResult ehr) {
				aimed = ehr.getEntity();
			}
			if (aimed != null) {
				Entity target = aimed;
				ClientLock.lock(target);
				ClientPlayNetworking.send(new LockPayload(target.getId()));
				return true;
			}
			return false;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Player player = client.player;
			ClientAim.tick(client);
			if (player != null && autoTicks % 40 == 0) {
				Entity aimEntity = ClientAim.aimed();
				LOGGER.info("[longbow] aim={} hitResult={}", aimEntity == null ? "none" : aimEntity.getName().getString(),
					client.hitResult == null ? "null" : client.hitResult.getType());
			}
			if (player != null) {
				if (dashKey.isDown() != dashKeyWasDown) {
					dashKeyWasDown = dashKey.isDown();
					LOGGER.info("[longbow] dash key state: {}", dashKeyWasDown);
				}
				while (prospectKey.consumeClick()) {
					prospectMode = !prospectMode;
					ClientPlayNetworking.send(new com.sandwalls.longbow.net.ProspectPayload(prospectMode));
					LOGGER.info("[longbow] prospect mode -> {}", prospectMode);
				}
				while (dashKey.consumeClick()) {
					int next = (ClientDash.gear() + 1) % 3;
					ClientDash.set(next);
					ClientPlayNetworking.send(new com.sandwalls.longbow.net.DashPayload(next));
					LOGGER.info("[longbow] dash gear -> {}", next);
				}
				boolean holdingBallad = player.getMainHandItem().is(com.sandwalls.longbow.item.LongbowItems.ARCFALL_BALLAD)
					|| player.getOffhandItem().is(com.sandwalls.longbow.item.LongbowItems.ARCFALL_BALLAD)
					|| player.getMainHandItem().getItem() instanceof ArcfallLongbowItem
					|| player.getOffhandItem().getItem() instanceof ArcfallLongbowItem;
				if (wasHoldingBallad && !holdingBallad && ClientDash.isActive()) {
					ClientDash.set(0);
					ClientPlayNetworking.send(new com.sandwalls.longbow.net.DashPayload(0));
					LOGGER.info("[longbow] dash auto-off (switched item)");
				}
				wasHoldingBallad = holdingBallad;
			}
			if (player != null) {
				boolean holding = player.getMainHandItem().getItem() instanceof ArcfallLongbowItem
					|| player.getOffhandItem().getItem() instanceof ArcfallLongbowItem;
				if (!holding && ClientLock.lockedId() >= 0) {
					ClientLock.unlock();
					ClientPlayNetworking.send(new LockPayload(-1));
				}
			}

			if (ClientDash.isActive() && autoTicks % 40 == 0 && player != null) {
				double clientSpeed = player.getAttributeValue(
					net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
				LOGGER.info("[longbow] client speed={} gear={} pos={}", clientSpeed, ClientDash.gear(),
					String.format("%.1f/%.1f/%.1f", player.getX(), player.getY(), player.getZ()));
			}

			if (System.getProperty("longbow.autotest") == null) {
				autoTicks++;
				return;
			}
			autoTicks++;
			if (!autoRan && autoTicks > 120 && player != null && client.level != null) {
				autoRan = true;
				client.player.connection.sendCommand("longbow selftest");
				LOGGER.info("[longbow] autotest commands sent");
			}
			if (autoRan && autoTicks == 160 && client.hitResult instanceof EntityHitResult ehr) {
				ClientLock.lock(ehr.getEntity());
				ClientPlayNetworking.send(new LockPayload(ehr.getEntity().getId()));
				LOGGER.info("[longbow] autotest: locked entity {}", ehr.getEntity().getId());
			}
			if (autoRan && autoTicks == 220) {
				ClientDash.set(1);
				ClientPlayNetworking.send(new com.sandwalls.longbow.net.DashPayload(1));
			}
			if (autoRan && autoTicks == 214) {
				// 成片设置：固定夜景 + 晴，隐藏 HUD（等价 F1），免得聊天栏糊住特效
				if (client.player != null) {
					client.player.connection.sendCommand("gamerule doDaylightCycle false");
					client.player.connection.sendCommand("time set midnight");
					client.player.connection.sendCommand("weather clear");
				}
				client.options.hideGui = true;
			}
			if (autoRan && autoTicks == 470) {
				client.options.hideGui = false;
			}
			if (autoRan && autoTicks == 215) {
				// 真机排障：拖尾点 / 白框 / 星芒贴图三条链路一起验
				ClientFxManager.debugSeedTrail(28);
				ClientFxManager.debugSeedBox();
				ClientFxManager.debugSeedFlare();
			}
			if (autoRan && autoTicks == 217) {
				// 真机排障：seed 点是"新鲜拖尾"，这里截一张确认 star() 能挂在拖尾头上
				try {
					Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(),
						c -> LOGGER.info("[longbow] screenshot-head: {}", c.getString()));
				} catch (Throwable t1) {
					LOGGER.error("[longbow] screenshot failed", t1);
				}
			}
			if (autoRan && autoTicks == 230) {
				try {
					Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), c -> LOGGER.info("[longbow] screenshot-fx: {}", c.getString()));
				} catch (Throwable t2) {
					LOGGER.error("[longbow] screenshot failed", t2);
				}
			}
			if (autoRan && autoTicks == 200 && client.player != null) {
				client.player.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
				LOGGER.info("[longbow] autotest: begin drawing bow");
			}
			if (autoRan && autoTicks == 210 && client.player != null) {
				try {
					Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), c -> LOGGER.info("[longbow] screenshot-lock: {}", c.getString()));
				} catch (Throwable t) {
					LOGGER.error("[longbow] screenshot failed", t);
				}
			}
			if (autoRan && autoTicks == 216) {
				// 球体贴图样品：3 颗等屏幕尺寸的球，40 tick 后自己消失
				ClientFxManager.debugSeedOrbPortrait();
			}
			if (autoRan && autoTicks == 232) {
				try {
					Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), c -> LOGGER.info("[longbow] screenshot-orbportrait: {}", c.getString()));
				} catch (Throwable t5) {
					LOGGER.error("[longbow] screenshot failed", t5);
				}
			}
			if (autoRan && autoTicks == 245) {
				// 球长开之后补一张：judge 个头/颜色/密度用
				try {
					Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), c -> LOGGER.info("[longbow] screenshot-orbs: {}", c.getString()));
				} catch (Throwable t4) {
					LOGGER.error("[longbow] screenshot failed", t4);
				}
			}
			if (autoRan && autoTicks == 420 && client.player != null) {
				client.player.releaseUsingItem();
				LOGGER.info("[longbow] autotest: released bow");
			}
			if (autoRan && autoTicks == 423 && client.player != null) {
				// 镜头转向拖尾正中，免得箭飞出屏幕、截图里什么都看不到
				ClientFxManager.debugLookAtLastTrail(client);
			}
			if (autoRan && autoTicks == 424) {
				// 刚出手的第 4 tick：拖尾还近在眼前，用来肉眼确认"小方片 + 高密度"的手感
				try {
					Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), c -> LOGGER.info("[longbow] screenshot-trail: {}", c.getString()));
				} catch (Throwable t3) {
					LOGGER.error("[longbow] screenshot failed", t3);
				}
			}
			if (autoRan && autoTicks == 450) {
				try {
					Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), c -> LOGGER.info("[longbow] screenshot: {}", c.getString()));
				} catch (Throwable t) {
					LOGGER.error("[longbow] screenshot failed", t);
				}
			}
			if (autoRan && autoTicks > 500) {
				LOGGER.info("[longbow] autotest finished, exiting");
				System.exit(0);
			}
		});

		LOGGER.info("[longbow] client initialized");
	}
}
