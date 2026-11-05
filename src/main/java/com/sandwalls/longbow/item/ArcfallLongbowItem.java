package com.sandwalls.longbow.item;

import com.sandwalls.longbow.logic.ArcfallArrowData;
import com.sandwalls.longbow.logic.LockManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 长虹陨落之歌：彩虹长弓。
 * 蓄能 0..10000 为常态，超过后进入超载，最高 500000（约 51 万"神力"）。
 */
public class ArcfallLongbowItem extends BowItem {
	public static final int MAX_NORMAL = 10000;
	public static final int MAX_OVERLOAD = 500000;
	/** 轻击平台：0-10 tick（0.5 秒）蓄能固定为 LIGHT_CHARGE，只杀怪不破坏地形。 */
	public static final int LIGHT_TICKS = 10;
	public static final int LIGHT_CHARGE = 100;
	public static final int NORMAL_TICKS = 40;
	/** 满 10000 后需要继续保持的 tick 数，10 秒平台期，之后才开始累积超载。 */
	public static final int OVERLOAD_DELAY_TICKS = 160;
	public static final int OVERLOAD_TICKS = 120;

	public ArcfallLongbowItem(Properties properties) {
		super(properties);
	}

/**
	 * 由拉弓 tick 数换算蓄能值（双端纯函数，不依赖同步状态）。
	 * 时间轴：0-2 秒充到 10000 → 2-10 秒停在 10000（平台期 8 秒）→ 10-16 秒超载到 500000。
	 */
	public static int computeCharge(int ticks) {
		if (ticks <= 0) {
			return 0;
		}
		if (ticks <= LIGHT_TICKS) {
			return LIGHT_CHARGE;
		}
		if (ticks <= NORMAL_TICKS) {
			long span = NORMAL_TICKS - LIGHT_TICKS;
			return LIGHT_CHARGE + (int) ((long) (MAX_NORMAL - LIGHT_CHARGE) * (ticks - LIGHT_TICKS) / span);
		}
		int overloadStart = NORMAL_TICKS + OVERLOAD_DELAY_TICKS;
		if (ticks <= overloadStart) {
			return MAX_NORMAL;
		}
		int extra = Math.min(OVERLOAD_TICKS, ticks - overloadStart);
		return MAX_NORMAL + (int) ((long) (MAX_OVERLOAD - MAX_NORMAL) * extra / OVERLOAD_TICKS);
	}

	/** 是否处于"满蓄但还没开始超载"的平台期。 */
	public static boolean isHoldingAtNormal(int ticks) {
		return ticks > NORMAL_TICKS && ticks <= NORMAL_TICKS + OVERLOAD_DELAY_TICKS;
	}

	/** 平台期还剩多少 tick 开始超载。 */
	public static int overloadWaitRemaining(int ticks) {
		return Math.max(0, NORMAL_TICKS + OVERLOAD_DELAY_TICKS - ticks);
	}

	public static boolean isOverloaded(int charge) {
		return charge > MAX_NORMAL;
	}

	/** 破坏半径：常态 1..3，超载线性到 9。 */
	public static int radiusForCharge(int charge) {
		if (charge <= 0) {
			return 0;
		}
		if (charge <= MAX_NORMAL) {
			return 1 + (int) ((long) (3 - 1) * charge / MAX_NORMAL);
		}
		int over = charge - MAX_NORMAL;
		return 3 + (int) ((long) (9 - 3) * over / (MAX_OVERLOAD - MAX_NORMAL));
	}

	/** 命中实体后可继续穿透的距离（格）：随蓄能线性增长，满蓄能 24 格。 */
	public static double pierceDistance(int charge) {
		if (charge <= 0) {
			return 0.0;
		}
		return 24.0 * Math.min(charge, MAX_OVERLOAD) / MAX_OVERLOAD;
	}

	/** 初速（格/tick）：4.0 → 8.0。45° 弹道在最大蓄力时射程约 1280 格（80 区块）。 */
	public static float speedForCharge(int charge) {
		return 4.0F + 4.0F * Math.min(charge, MAX_OVERLOAD) / (float) MAX_OVERLOAD;
	}

	/** 重力（格/tick²）：小蓄力压到 0.005 让 64 格内几乎无下坠，满蓄力回到 0.05 形成抛物线。 */
	public static double gravityForCharge(int charge) {
		return 0.005 + 0.045 * Math.min(charge, MAX_OVERLOAD) / (double) MAX_OVERLOAD;
	}

	/** 常态满蓄（10000）的半径：等于上一版曲线在 100000 处的值（≈8.49）。 */
	public static final double NORMAL_BLAST_RADIUS = 8.489796;
	/** 常态满蓄（10000）的高度：等于上一版曲线在 100000 处的值（≈33.96）。 */
	public static final double NORMAL_BLAST_HEIGHT = 33.959184;
	public static final double MAX_BLAST_RADIUS = 32.0;
	public static final double MAX_BLAST_HEIGHT = 128.0;

	/** 圆柱破坏半径：0→2，常态满蓄 8.49（对标上一版 100000），满蓄力 32（4 区块）。 */
	public static double blastRadius(int charge) {
		if (charge <= LIGHT_CHARGE) {
			return 0.0;
		}
		double c = Math.min(charge, MAX_OVERLOAD);
		if (c <= MAX_NORMAL) {
			return 2.0 + (NORMAL_BLAST_RADIUS - 2.0) * c / MAX_NORMAL;
		}
		return NORMAL_BLAST_RADIUS + (MAX_BLAST_RADIUS - NORMAL_BLAST_RADIUS) * (c - MAX_NORMAL) / (MAX_OVERLOAD - MAX_NORMAL);
	}

	/** 圆柱长度：0→8，常态满蓄 33.96（对标上一版 100000），满蓄力 128（8 区块）。 */
	/** 轻击档（0.5 秒内松手）：只杀怪、不破坏地形。 */
	public static boolean isLightShot(int charge) {
		return charge <= LIGHT_CHARGE;
	}

	/** 轻击时用来判定秒杀的范围（半径 3 格），与地形破坏半径解耦。 */
	public static double killRadius(int charge) {
		return isLightShot(charge) ? 3.0 : Math.max(3.0, blastRadius(charge));
	}

	public static double blastHeight(int charge) {
		double c = Math.min(charge, MAX_OVERLOAD);
		if (c <= MAX_NORMAL) {
			return 8.0 + (NORMAL_BLAST_HEIGHT - 8.0) * c / MAX_NORMAL;
		}
		return NORMAL_BLAST_HEIGHT + (MAX_BLAST_HEIGHT - NORMAL_BLAST_HEIGHT) * (c - MAX_NORMAL) / (MAX_OVERLOAD - MAX_NORMAL);
	}

	public static int getStoredCharge(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return 0;
		}
		return data.copyTag().getIntOr("LongbowCharge", 0);
	}

	public static void setStoredCharge(ItemStack stack, int charge) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt("LongbowCharge", charge));
	}

	/** 自动附上弓箭全套顶级附魔（无限与经验修补原版互斥，这里强制共存）。 */
	@Override
	public void inventoryTick(ItemStack stack, net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity owner,
							  net.minecraft.world.entity.EquipmentSlot slot) {
		if (!stack.getEnchantments().isEmpty()) {
			return;
		}
		com.sandwalls.longbow.LongbowMod.LOGGER.info("[longbow] auto-enchant running on {}", stack);
		var lookup = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
		net.minecraft.world.item.enchantment.ItemEnchantments.Mutable mutable =
			new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
		mutable.set(lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.POWER), 5);
		mutable.set(lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.PUNCH), 2);
		mutable.set(lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FLAME), 1);
		mutable.set(lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.INFINITY), 1);
		mutable.set(lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING), 3);
		mutable.set(lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING), 1);
		stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
		com.sandwalls.longbow.LongbowMod.LOGGER.info("[longbow] auto-enchant applied size={}", stack.getEnchantments().size());
	}

	/** 物品名显示为彩虹渐变色。 */
	@Override
	public net.minecraft.network.chat.Component getName(ItemStack stack) {
		return RainbowText.of(net.minecraft.network.chat.Component.translatable(this.getDescriptionId()).getString());
	}

	/** 始终带附魔光罩（淡紫色 glint）。 */
	@Override
	public boolean isFoil(ItemStack stack) {
		return true;
	}

	@Override
	public int getUseDuration(ItemStack itemStack, LivingEntity user) {
		return 72000;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		boolean hasAmmo = player.hasInfiniteMaterials() || !player.getProjectile(stack).isEmpty();
		if (!hasAmmo) {
			return InteractionResult.FAIL;
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remainingTime) {
		if (!(entity instanceof Player player)) {
			return false;
		}
		int heldTicks = this.getUseDuration(stack, entity) - remainingTime;
		int charge = computeCharge(heldTicks);
		// 只看按住时长，不再用蓄能阈值卡最低发射（否则轻点右键会没反应）
		if (heldTicks < 1) {
			return false;
		}
		ItemStack ammo = player.getProjectile(stack);
		if (ammo.isEmpty() && !player.hasInfiniteMaterials()) {
			return false;
		}
		if (level instanceof ServerLevel serverLevel) {
			this.fire(serverLevel, player, stack, ammo, charge);
		}
		level.playSound(
			null,
			player.getX(),
			player.getY(),
			player.getZ(),
			SoundEvents.ARROW_SHOOT,
			SoundSource.PLAYERS,
			1.0F,
			isOverloaded(charge) ? 0.7F : 1.0F
		);
		player.awardStat(Stats.ITEM_USED.get(this));
		stack.hurtAndBreak(1, player, player.getUsedItemHand());
		return true;
	}

	private void fire(ServerLevel level, Player player, ItemStack weapon, ItemStack ammo, int charge) {
		boolean hasInfinity = false;
		ItemStack projectile = ammo.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.ARROW) : ammo.copyWithCount(1);
		Arrow arrow = new Arrow(level, player.getX(), player.getEyeY() - 0.1, player.getZ(), projectile, weapon);
		arrow.setOwner(player);

		Entity locked = LockManager.getServerTarget(player);
		Vec3 direction;
		if (locked != null && locked.isAlive()) {
			direction = locked.getBoundingBox().getCenter().subtract(arrow.position()).normalize();
		} else {
			direction = player.getLookAngle();
		}
		float speed = speedForCharge(charge);
		arrow.shoot(direction.x, direction.y, direction.z, speed, 0.0F);
		var lookup = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
		int power = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
			lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.POWER), weapon);
		int flame = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
			lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FLAME), weapon);
		int infinity = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
			lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.INFINITY), weapon);
		arrow.setBaseDamage((2.0 + charge / 20000.0) * (1.0 + 0.25 * (power + 1)));
		if (flame > 0) {
			arrow.igniteForSeconds(5.0F);
		}
		if (infinity > 0) {
			hasInfinity = true;
		}

		if (arrow instanceof com.sandwalls.longbow.mixin.AbstractArrowAccessor accessor) {
			accessor.longbow$setPierceLevel((byte) 64);
		}
		ArcfallArrowData.mark(arrow, charge, locked);
		int flash = isOverloaded(charge) ? 40 : 16;
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, player.getX(), player.getEyeY() - 0.2, player.getZ(), flash, 0.35, 0.35, 0.35, 0.02);
		level.addFreshEntity(arrow);

		if (!player.hasInfiniteMaterials() && !hasInfinity) {
			ammo.shrink(1);
		}
	}
}
