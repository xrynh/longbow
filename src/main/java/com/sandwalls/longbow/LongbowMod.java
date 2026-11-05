package com.sandwalls.longbow;

import com.sandwalls.longbow.item.ArcfallLongbowItem;
import com.sandwalls.longbow.item.LongbowItems;
import com.sandwalls.longbow.logic.LockManager;
import com.sandwalls.longbow.logic.LockPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LongbowMod implements ModInitializer {
	public static final String MOD_ID = "longbow";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> PHANTOM;
	public static net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> IMMUNE;

	@Override
	public void onInitialize() {
		LongbowItems.initialize();
		// 拖尾旁的彩色小球：粒子类型必须在客户端注册 provider 之前进注册表
		com.sandwalls.longbow.fx.LongbowParticles.initialize();
		com.sandwalls.longbow.effect.PhantomEffect phantom = net.minecraft.core.Registry.register(
			net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "phantom"),
			new com.sandwalls.longbow.effect.PhantomEffect());
		PHANTOM = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder(phantom);
		com.sandwalls.longbow.effect.ImmuneEffect immune = net.minecraft.core.Registry.register(
			net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "immune"),
			new com.sandwalls.longbow.effect.ImmuneEffect());
		IMMUNE = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder(immune);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(net.minecraft.commands.Commands.literal("longbow").then(net.minecraft.commands.Commands.literal("selftest")
				.executes(ctx -> com.sandwalls.longbow.test.LongbowSelfTest.run(ctx.getSource())))));
		PayloadTypeRegistry.serverboundPlay().register(LockPayload.TYPE, LockPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.sandwalls.longbow.net.FxPayload.TYPE, com.sandwalls.longbow.net.FxPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.sandwalls.longbow.net.DashPayload.TYPE, com.sandwalls.longbow.net.DashPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(com.sandwalls.longbow.net.DashPayload.TYPE, (payload, context) ->
			context.server().execute(() -> com.sandwalls.longbow.logic.DashManager.set(context.player(), payload.gear())));
		PayloadTypeRegistry.serverboundPlay().register(com.sandwalls.longbow.net.ProspectPayload.TYPE, com.sandwalls.longbow.net.ProspectPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(com.sandwalls.longbow.net.ProspectPayload.TYPE, (payload, context) ->
			context.server().execute(() -> com.sandwalls.longbow.logic.MiningModeManager.set(context.player(), payload.enabled())));
		ServerPlayNetworking.registerGlobalReceiver(LockPayload.TYPE, (payload, context) ->
			context.server().execute(() -> LockManager.setServerTarget(context.player(), payload.entityId())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LockManager.clearServerTarget(handler.player));

		// 死亡不掉落：世界启动时强制开启 keepInventory
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY, true, server);
			LOGGER.info("[longbow] keepInventory forced on");
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			com.sandwalls.longbow.logic.DestructionQueue.tick();
			com.sandwalls.longbow.logic.DashManager.tick(server);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				applyHeldState(player);
			}
		});
		LOGGER.info("[longbow] initialized (Minecraft 26.1 / Fabric)");
	}

	/**
	 * 按「手上有没有拿长弓」刷新一个玩家的状态：飞行能力、{@code 幻} / {@code 水火不侵}、
	 * 以及清掉服务端锁定目标。
	 *
	 * <p>从 tick 回调里抽出来是为了让自测能确定性地触发一次。原来的自测是
	 * 「先把弓塞进手里、立刻查有没有效果」，靠的是上一 tick 玩家手里正好拿着弓——
	 * 存档里的手持物品一变就假失败（{@code has=false}），和模组本身没关系。</p>
	 */
	public static void applyHeldState(ServerPlayer player) {
		boolean holding = isLongbow(player.getMainHandItem()) || isLongbow(player.getOffhandItem());
		Abilities abilities = player.getAbilities();
		if (holding) {
			if (!abilities.mayfly) {
				abilities.mayfly = true;
				player.onUpdateAbilities();
			}
			if (!player.hasEffect(PHANTOM)) {
				player.addEffect(new net.minecraft.world.effect.MobEffectInstance(PHANTOM,
					net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION, 0, false, false));
			}
			if (!player.hasEffect(IMMUNE)) {
				player.addEffect(new net.minecraft.world.effect.MobEffectInstance(IMMUNE,
					net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION, 0, false, false));
			}
		} else {
			player.removeEffect(PHANTOM);
			player.removeEffect(IMMUNE);
			if (abilities.mayfly && !player.isCreative() && !player.isSpectator()) {
				abilities.mayfly = false;
				if (abilities.flying) {
					abilities.flying = false;
				}
				player.onUpdateAbilities();
			}
		}
		if (!holding) {
			LockManager.clearServerTarget(player);
		}
	}

	private static boolean isLongbow(ItemStack stack) {
		return stack.getItem() instanceof ArcfallLongbowItem;
	}
}
