package com.sandwalls.longbow.client;

import com.sandwalls.longbow.item.ArcfallLongbowItem;
import com.sandwalls.longbow.item.LongbowItems;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** 屏幕下方：蓄力条（持弓时）+ 疾行状态（任何时候只要疾行开启就显示）+ 锁定提示。 */
public final class LongbowHud implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		boolean holdingBow = player.getMainHandItem().getItem() instanceof ArcfallLongbowItem
			|| player.getOffhandItem().getItem() instanceof ArcfallLongbowItem;
		boolean holdingBallad = player.getMainHandItem().is(LongbowItems.ARCFALL_BALLAD)
			|| player.getOffhandItem().is(LongbowItems.ARCFALL_BALLAD);
		if (!holdingBow && !holdingBallad && !ClientDash.isActive()) {
			return;
		}

		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();
		int y0 = height - 66;

		if (LongbowClient.prospectMode()) {
			graphics.centeredText(mc.font,

				Component.literal("探矿模式 · 开").withStyle(ChatFormatting.GREEN),
				width / 2, y0 - 44, 0xFFFFFFFF);
		}

		if (ClientDash.isActive()) {
			String dashLabel = ClientDash.gear() >= 2 ? "疾行 II · 鞘翅速" : "疾行 I · 马速";
			graphics.centeredText(mc.font, Component.literal(dashLabel).withStyle(ChatFormatting.LIGHT_PURPLE),
				width / 2, y0 - 30, 0xFFFFFFFF);
			graphics.fill(width / 2 - 90, y0 - 20, width / 2 + 90, y0 - 18, 0xFFD580FF);
		}

		if (holdingBow) {
			int charge = 0;
			int heldTicks = 0;
			if (player.isUsingItem() && player.getUseItem().getItem() instanceof ArcfallLongbowItem) {
				heldTicks = player.getUseItem().getUseDuration(player) - player.getUseItemRemainingTicks();
				charge = ArcfallLongbowItem.computeCharge(heldTicks);
			}
			boolean overload = ArcfallLongbowItem.isOverloaded(charge);
			int barWidth = 120;
			int x0 = width / 2 - barWidth / 2;
			graphics.fill(x0 - 1, y0 - 1, x0 + barWidth + 1, y0 + 5, 0xA0000000);
			float ratio;
			if (charge <= ArcfallLongbowItem.MAX_NORMAL) {
				ratio = 0.5F * charge / (float) ArcfallLongbowItem.MAX_NORMAL;
			} else {
				ratio = 0.5F + 0.5F * (charge - ArcfallLongbowItem.MAX_NORMAL)
					/ (float) (ArcfallLongbowItem.MAX_OVERLOAD - ArcfallLongbowItem.MAX_NORMAL);
			}
			int fillColor = overload ? 0xFFFF5A1F : 0xFF35E0FF;
			graphics.fill(x0, y0, x0 + (int) (barWidth * ratio), y0 + 4, fillColor);
			int marker = x0 + barWidth / 2;
			graphics.fill(marker, y0 - 2, marker + 1, y0 + 6, 0xFFFFFFFF);

			String label;
			if (overload) {
				label = "超载 " + charge + " / " + ArcfallLongbowItem.MAX_OVERLOAD;
			} else if (ArcfallLongbowItem.isHoldingAtNormal(heldTicks)) {
				label = "满蓄 " + charge + " / " + ArcfallLongbowItem.MAX_NORMAL
					+ " · 再保持 " + String.format("%.1f", ArcfallLongbowItem.overloadWaitRemaining(heldTicks) / 20.0) + " 秒进入超载";
			} else if (ArcfallLongbowItem.isLightShot(charge)) {
				label = "轻击 " + charge + " · 不破坏地形";
			} else {
				label = "蓄能 " + charge + " / " + ArcfallLongbowItem.MAX_NORMAL;
			}
			graphics.centeredText(mc.font,
				Component.literal(label).withStyle(overload ? ChatFormatting.GOLD : ChatFormatting.AQUA),
				width / 2, y0 - 12, 0xFFFFFFFF);

			Entity locked = ClientLock.resolve(mc.level);
			if (locked != null) {
				graphics.centeredText(mc.font,
					Component.literal("已锁定：" + locked.getName().getString()).withStyle(ChatFormatting.WHITE),
					width / 2, y0 + 14, 0xFFFFFFFF);
			}
		}
	}
}
