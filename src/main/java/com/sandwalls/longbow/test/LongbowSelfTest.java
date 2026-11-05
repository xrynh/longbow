package com.sandwalls.longbow.test;

import com.sandwalls.longbow.item.ArcfallLongbowItem;
import com.sandwalls.longbow.item.LongbowItems;
import com.sandwalls.longbow.logic.ArcfallArrowData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** /longbow selftest：无头逻辑断言，结果写进日志和聊天栏。 */
public final class LongbowSelfTest {
	private LongbowSelfTest() {}

	private static int lastMobId = -1;

	public static int run(CommandSourceStack source) {
		// 无人值守测试时把世界固定成半夜 + 晴天，截图才拍得到发光特效（用控制台权限，绕过单人存档没开作弊）
		if (System.getProperty("longbow.autotest") != null) {
			try {
				var console = source.getServer().createCommandSourceStack();
				source.getServer().getCommands().performPrefixedCommand(console, "gamerule doDaylightCycle false");
				source.getServer().getCommands().performPrefixedCommand(console, "time set midnight");
				source.getServer().getCommands().performPrefixedCommand(console, "weather clear");
			} catch (Throwable ignored) {
				// 拍不到夜景不影响测试结果
			}
		}
		List<String> lines = new ArrayList<>();
		int pass = 0;
		int fail = 0;

		boolean registered = BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath("longbow", "arcfall_longbow")).isPresent()
			&& BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath("longbow", "arcfall_ballad")).isPresent();
		if (registered) {
			pass++;
			lines.add("PASS item-registration");
		} else {
			fail++;
			lines.add("FAIL item-registration");
		}

		int cLight = ArcfallLongbowItem.computeCharge(10);
		int cNormal = ArcfallLongbowItem.computeCharge(40);
		int cPlateau = ArcfallLongbowItem.computeCharge(200);
		int cOverStart = ArcfallLongbowItem.computeCharge(201);
		int cFull = ArcfallLongbowItem.computeCharge(320);
		boolean curveOk = cLight == ArcfallLongbowItem.LIGHT_CHARGE && cNormal == ArcfallLongbowItem.MAX_NORMAL
			&& cPlateau == ArcfallLongbowItem.MAX_NORMAL && cOverStart > ArcfallLongbowItem.MAX_NORMAL
			&& cFull == ArcfallLongbowItem.MAX_OVERLOAD;
		if (curveOk) {
			pass++;
			lines.add("PASS charge-curve 0.5s=100 2s=10000 10s=" + cPlateau + " 16s=" + cFull);
		} else {
			fail++;
			lines.add("FAIL charge-curve " + cLight + "/" + cNormal + "/" + cPlateau + "/" + cOverStart + "/" + cFull);
		}

		int rNormal = ArcfallLongbowItem.radiusForCharge(ArcfallLongbowItem.MAX_NORMAL);
		int rOver = ArcfallLongbowItem.radiusForCharge(ArcfallLongbowItem.MAX_OVERLOAD);
		if (rNormal >= 2 && rOver > rNormal) {
			pass++;
			lines.add("PASS radius normal=" + rNormal + " overload=" + rOver);
		} else {
			fail++;
			lines.add("FAIL radius " + rNormal + "/" + rOver);
		}

		if (source.getLevel() instanceof ServerLevel level) {
			Vec3 base = source.getPosition().add(12.0, 0.0, 0.0);
			BlockPos center = BlockPos.containing(base);
			for (int dx = -2; dx <= 2; dx++) {
				for (int dy = -2; dy <= 2; dy++) {
					for (int dz = -2; dz <= 2; dz++) {
						level.setBlock(center.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 2);
					}
				}
			}
			ArcfallArrowData.destroy(level, Vec3.atCenterOf(center), new Vec3(0.0, -1.0, 0.0), 4000, null);
			for (int i = 0; i < 200 && com.sandwalls.longbow.logic.DestructionQueue.hasWork(); i++) {
				com.sandwalls.longbow.logic.DestructionQueue.tick();
			}
			boolean cleared = level.getBlockState(center).isAir();
			if (cleared) {
				pass++;
				lines.add("PASS destruction-sphere");
			} else {
				fail++;
				lines.add("FAIL destruction-sphere");
			}
		}


		if (source.getEntity() instanceof ServerPlayer sp) {
			sp.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
			int groundY = sp.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, sp.getBlockX(), sp.getBlockZ());
			sp.teleportTo(sp.getX(), groundY + 6.0, sp.getZ());
			sp.getAbilities().mayfly = true;
			sp.getAbilities().flying = true;
			sp.onUpdateAbilities();
			sp.getInventory().add(new ItemStack(LongbowItems.ARCFALL_LONGBOW));
			sp.getInventory().add(new ItemStack(Items.ARROW, 64));
			sp.getInventory().add(new ItemStack(LongbowItems.ARCFALL_BALLAD, 8));
			Vec3 look = sp.getLookAngle();
			Vec3 spawnPos = sp.getEyePosition().add(look.scale(4.5)).add(0.0, -1.2, 0.0);
			if (EntityType.ZOMBIE.create(sp.level(), EntitySpawnReason.COMMAND) instanceof Mob mob) {
				mob.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
				mob.setNoAi(true);
				mob.setPersistenceRequired();
				sp.level().addFreshEntity(mob);
				lastMobId = mob.getId();
				lines.add("INFO spawned dummy mob id=" + mob.getId());
				pass++;
			} else {
				fail++;
				lines.add("FAIL spawn dummy mob");
			}
			sp.getInventory().setSelectedItem(new ItemStack(LongbowItems.ARCFALL_LONGBOW));
			lines.add("INFO inventory prepared");
		}

		boolean hasRecipe = source.getServer().getRecipeManager().byKey(
			net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
				Identifier.fromNamespaceAndPath("longbow", "arcfall_longbow"))).isPresent();
		if (hasRecipe) {
			pass++;
			lines.add("PASS recipe-loaded");
		} else {
			fail++;
			lines.add("FAIL recipe-loaded");
		}


		if (source.getEntity() instanceof ServerPlayer sp) {
			boolean canFly = sp.getAbilities().mayfly;
			if (canFly) {
				pass++;
				lines.add("PASS flying-ability mayfly=true");
			} else {
				fail++;
				lines.add("FAIL flying-ability mayfly=false");
			}
			float digSpeed = sp.getDestroySpeed(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
			if (digSpeed > 100.0F) {
				pass++;
				lines.add("PASS fast-mining destroySpeed=" + digSpeed);
			} else {
				fail++;
				lines.add("FAIL fast-mining destroySpeed=" + digSpeed);
			}
			Entity dummy = lastMobId >= 0 ? sp.level().getEntity(lastMobId) : null;
			if (dummy != null) {
				Vec3 start = sp.position().add(0.0, 6.0, 0.0);
				Arrow arrow = new Arrow(sp.level(), start.x, start.y, start.z, new ItemStack(Items.ARROW), null);
				arrow.setOwner(sp);
				arrow.setDeltaMovement(new Vec3(-2.0, 0.0, 0.0));
				ArcfallArrowData.mark(arrow, 0, dummy);
				double before = arrow.position().distanceTo(dummy.position());
				for (int i = 0; i < 25; i++) {
					arrow.tick();
				}
				double after = arrow.position().distanceTo(dummy.position());
				if (after < before) {
					pass++;
					lines.add("PASS homing " + String.format("%.2f", before) + "->" + String.format("%.2f", after));
				} else {
					fail++;
					lines.add("FAIL homing " + String.format("%.2f", before) + "->" + String.format("%.2f", after));
				}
				arrow.discard();
				if (dummy instanceof net.minecraft.world.entity.LivingEntity le0) {
					le0.setHealth(le0.getMaxHealth());
					le0.invulnerableTime = 0;
				}
				float hpBefore = dummy instanceof net.minecraft.world.entity.LivingEntity le ? le.getHealth() : 0.0F;
				Arrow hitArrow = new Arrow(sp.level(), start.x, start.y, start.z, new ItemStack(Items.ARROW), null);
				hitArrow.setOwner(sp);
				hitArrow.setDeltaMovement(new Vec3(-2.0, 0.0, 0.0));
				ArcfallArrowData.mark(hitArrow, 0, dummy);
				for (int i = 0; i < 120 && !hitArrow.isRemoved(); i++) {
					hitArrow.tick();
				}
				float hpAfter = dummy instanceof net.minecraft.world.entity.LivingEntity le2 ? le2.getHealth() : 0.0F;
				if (hpAfter < hpBefore) {
					pass++;
					lines.add("PASS homing-damage " + hpBefore + "->" + hpAfter);
				} else {
					fail++;
					lines.add("FAIL homing-damage " + hpBefore + "->" + hpAfter);
				}
				hitArrow.discard();
				Arrow pierceArrow = new Arrow(sp.level(), start.x, start.y, start.z, new ItemStack(Items.ARROW), null);
				pierceArrow.setOwner(sp);
				if (pierceArrow instanceof com.sandwalls.longbow.mixin.AbstractArrowAccessor acc) {
					acc.longbow$setPierceLevel((byte) 8);
				}
				Vec3 toTarget = dummy.position().subtract(pierceArrow.position());
				Vec3 flightDir = toTarget.normalize();
				pierceArrow.setDeltaMovement(flightDir.scale(4.0));
				ArcfallArrowData.mark(pierceArrow, ArcfallLongbowItem.MAX_OVERLOAD, dummy);
				boolean crossed = false;
				for (int i = 0; i < 60 && !pierceArrow.isRemoved(); i++) {
					pierceArrow.tick();
					if (pierceArrow.position().subtract(dummy.position()).dot(flightDir) > 0.8) {
						crossed = true;
						break;
					}
				}
				if (crossed) {
					pass++;
					lines.add("PASS pierce-through");
				} else {
					fail++;
					lines.add("FAIL pierce-through removed=" + pierceArrow.isRemoved());
				}
				pierceArrow.discard();


				try {
					sp.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, dummy.position());
				} catch (Throwable ignored) {
				}
			} else {
				fail++;
				lines.add("FAIL homing (no dummy entity)");
			}
		}


			double pdNormal = ArcfallLongbowItem.pierceDistance(ArcfallLongbowItem.MAX_NORMAL);
			double pdMax = ArcfallLongbowItem.pierceDistance(ArcfallLongbowItem.MAX_OVERLOAD);
			if (pdNormal > 0.0 && pdMax > pdNormal) {
				pass++;
				lines.add("PASS pierce-distance normal=" + String.format("%.2f", pdNormal) + " max=" + String.format("%.2f", pdMax));
			} else {
				fail++;
				lines.add("FAIL pierce-distance " + pdNormal + "/" + pdMax);
			}

			if (source.getEntity() instanceof ServerPlayer sp2) {
			Vec3 air = sp2.position().add(0.0, 20.0, 0.0);
			Arrow shortPierce = new Arrow(sp2.level(), air.x, air.y, air.z, new ItemStack(Items.ARROW), null);
			shortPierce.setOwner(sp2);
			shortPierce.setDeltaMovement(4.0, 0.0, 0.0);
			ArcfallArrowData.beginPierce(shortPierce, 2.0);
			shortPierce.tick();
			if (shortPierce.isRemoved()) {
				pass++;
				lines.add("PASS pierce-budget-consumed");
			} else {
				fail++;
				lines.add("FAIL pierce-budget-consumed remain=" + ArcfallArrowData.pierceRemaining(shortPierce));
			}
			shortPierce.discard();

			Arrow longPierce = new Arrow(sp2.level(), air.x, air.y, air.z + 4.0, new ItemStack(Items.ARROW), null);
			longPierce.setOwner(sp2);
			longPierce.setDeltaMovement(4.0, 0.0, 0.0);
			ArcfallArrowData.beginPierce(longPierce, 100.0);
			longPierce.tick();
			if (!longPierce.isRemoved()) {
				pass++;
				lines.add("PASS pierce-budget-holds");
			} else {
				fail++;
				lines.add("FAIL pierce-budget-holds");
			}
			longPierce.discard();
			}


			if (source.getEntity() instanceof ServerPlayer sp3) {
				BlockPos wallPos = BlockPos.containing(sp3.position().add(0.0, 12.0, 0.0));
				sp3.level().setBlock(wallPos, Blocks.STONE.defaultBlockState(), 2);
				Arrow blockArrow = new Arrow(sp3.level(), wallPos.getX() + 0.5, wallPos.getY() + 0.5, wallPos.getZ() + 2.5, new ItemStack(Items.ARROW), null);
				blockArrow.setOwner(sp3);
				blockArrow.setDeltaMovement(0.0, 0.0, -4.0);
				ArcfallArrowData.mark(blockArrow, 0, null);
				for (int i = 0; i < 12 && !blockArrow.isRemoved(); i++) {
					blockArrow.tick();
				}
				if (blockArrow.isRemoved()) {
					pass++;
					lines.add("PASS block-hit-destroys-arrow");
				} else {
					fail++;
					lines.add("FAIL block-hit-destroys-arrow");
				}
				blockArrow.discard();
				sp3.level().setBlock(wallPos, Blocks.AIR.defaultBlockState(), 2);
			}


			if (source.getEntity() instanceof ServerPlayer sp4) {
				double normalR = ArcfallLongbowItem.blastRadius(ArcfallLongbowItem.MAX_NORMAL);
				double normalH = ArcfallLongbowItem.blastHeight(ArcfallLongbowItem.MAX_NORMAL);
				boolean parity = Math.abs(normalR - 8.489796) < 0.01 && Math.abs(normalH - 33.959184) < 0.01;
				if (parity) {
					pass++;
					lines.add("PASS normal-parity r=" + normalR + " h=" + normalH);
				} else {
					fail++;
					lines.add("FAIL normal-parity r=" + normalR + " h=" + normalH);
				}
				double minR = ArcfallLongbowItem.blastRadius(120);
				double minH = ArcfallLongbowItem.blastHeight(120);
				double maxR = ArcfallLongbowItem.blastRadius(ArcfallLongbowItem.MAX_OVERLOAD);
				double maxH = ArcfallLongbowItem.blastHeight(ArcfallLongbowItem.MAX_OVERLOAD);
				if (minR >= 2.0 && minH >= 8.0 && maxR >= 32.0 && maxH >= 128.0) {
					pass++;
					lines.add("PASS blast-cylinder min=" + String.format("%.1f", minR * 2) + "x" + String.format("%.1f", minH)
						+ " max=" + String.format("%.0f", maxR * 2) + "x" + String.format("%.0f", maxH));
				} else {
					fail++;
					lines.add("FAIL blast-cylinder " + minR + "/" + minH + "/" + maxR + "/" + maxH);
				}

				double maxSpeed = ArcfallLongbowItem.speedForCharge(ArcfallLongbowItem.MAX_OVERLOAD);
				double maxGravity = ArcfallLongbowItem.gravityForCharge(ArcfallLongbowItem.MAX_OVERLOAD);
				double theoreticalRange = maxSpeed * maxSpeed / maxGravity;
				if (theoreticalRange >= 1024.0) {
					pass++;
					lines.add("PASS range-theory " + String.format("%.0f", theoreticalRange) + "m (" + String.format("%.0f", theoreticalRange / 16) + " chunks)");
				} else {
					fail++;
					lines.add("FAIL range-theory " + theoreticalRange);
				}

				Vec3 sky = sp4.position().add(0.0, 160.0, 0.0);
				Arrow flat = new Arrow(sp4.level(), sky.x, sky.y, sky.z, new ItemStack(Items.ARROW), null);
				flat.setOwner(sp4);
				ArcfallArrowData.mark(flat, ArcfallLongbowItem.MAX_NORMAL, null);
				flat.shoot(1.0, 0.0, 0.0, ArcfallLongbowItem.speedForCharge(ArcfallLongbowItem.MAX_NORMAL), 0.0F);
				double startX = flat.getX();
				double startY = flat.getY();
				for (int i = 0; i < 60 && flat.getX() - startX < 64.0; i++) {
					flat.tick();
				}
				double travelled = flat.getX() - startX;
				double drop = startY - flat.getY();
				if (travelled >= 60.0 && drop < 2.5) {
					pass++;
					lines.add("PASS flat-shot-64m travelled=" + String.format("%.1f", travelled) + " drop=" + String.format("%.2f", drop));
				} else {
					fail++;
					lines.add("FAIL flat-shot-64m travelled=" + String.format("%.1f", travelled) + " drop=" + String.format("%.2f", drop));
				}
				flat.discard();

				Entity killTarget = lastMobId >= 0 ? sp4.level().getEntity(lastMobId) : null;
				if (killTarget instanceof net.minecraft.world.entity.LivingEntity living) {
					living.setHealth(living.getMaxHealth());
					living.invulnerableTime = 0;
					ArcfallArrowData.destroy(sp4.level(), living.position(), new Vec3(0.0, -1.0, 0.0), 150, null);
					if (!living.isAlive() || living.getHealth() <= 0.0F) {
						pass++;
						lines.add("PASS blast-kills-creatures");
					} else {
						fail++;
						lines.add("FAIL blast-kills-creatures hp=" + living.getHealth());
					}
				}
			}


			// 范围击杀归属：带玩家 owner 的 MAGIC 伤害源必须把击杀记到玩家头上，
			// 否则原版 dropExperience 的 lastHurtByPlayerMemoryTime 条件不满足，经验为 0。
			if (source.getEntity() instanceof ServerPlayer sp7) {
				if (EntityType.ZOMBIE.create(sp7.level(), EntitySpawnReason.COMMAND) instanceof Mob creditMob) {
					creditMob.setPos(sp7.getX() + 2.0, sp7.getY(), sp7.getZ());
					creditMob.setNoAi(true);
					sp7.level().addFreshEntity(creditMob);
					creditMob.invulnerableTime = 0;
					net.minecraft.world.damagesource.DamageSource creditSource =
						sp7.level().damageSources().source(net.minecraft.world.damagesource.DamageTypes.MAGIC, sp7);
					creditMob.hurtServer(sp7.level(), creditSource, 1.0E9F);
					boolean credited = !creditMob.isAlive() && creditMob.getLastHurtByPlayer() == sp7;
					if (credited) {
						pass++;
						lines.add("PASS blast-credit-to-player");
					} else {
						fail++;
						lines.add("FAIL blast-credit-to-player alive=" + creditMob.isAlive()
							+ " credit=" + creditMob.getLastHurtByPlayer());
					}
					creditMob.discard();
				}
			}

			if (source.getEntity() instanceof ServerPlayer sp5) {
				BlockPos feet = BlockPos.containing(sp5.position()).below();
				sp5.level().setBlock(feet, Blocks.STONE.defaultBlockState(), 2);
				Arrow selfArrow = new Arrow(sp5.level(), sp5.getX(), sp5.getY(), sp5.getZ(), new ItemStack(Items.ARROW), null);
				selfArrow.setOwner(sp5);
				ArcfallArrowData.destroy(sp5.level(), sp5.position(), new Vec3(0.0, -1.0, 0.0), 3000, selfArrow);
				for (int i = 0; i < 200 && com.sandwalls.longbow.logic.DestructionQueue.hasWork(); i++) {
					com.sandwalls.longbow.logic.DestructionQueue.tick();
				}
				boolean feetSafe = sp5.level().getBlockState(feet).is(Blocks.STONE);
				if (feetSafe && sp5.isAlive()) {
					pass++;
					lines.add("PASS owner-protected");
				} else {
					fail++;
					lines.add("FAIL owner-protected feet=" + feetSafe + " alive=" + sp5.isAlive());
				}
				selfArrow.discard();
			}


			if (source.getEntity() instanceof ServerPlayer sp6) {
				Vec3 c = sp6.position().add(0.0, 40.0, 0.0);
				BlockPos base = BlockPos.containing(c);
				for (int x = -4; x <= 12; x++) {
					for (int y = -2; y <= 2; y++) {
						for (int z = -2; z <= 2; z++) {
							sp6.level().setBlock(base.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
						}
					}
				}
				ArcfallArrowData.destroy(sp6.level(), c, new Vec3(1.0, 0.0, 0.0), 120, null);
				for (int i = 0; i < 200 && com.sandwalls.longbow.logic.DestructionQueue.hasWork(); i++) {
					com.sandwalls.longbow.logic.DestructionQueue.tick();
				}
				boolean forwardCleared = sp6.level().getBlockState(base.offset(6, 0, 0)).isAir();
				boolean backwardKept = sp6.level().getBlockState(base.offset(-3, 0, 0)).is(Blocks.STONE);
				if (forwardCleared && backwardKept) {
					pass++;
					lines.add("PASS cylinder-along-trajectory");
				} else {
					fail++;
					lines.add("FAIL cylinder-along-trajectory fwd=" + forwardCleared + " back=" + backwardKept);
				}
			}


			if (source.getEntity() instanceof ServerPlayer sp7) {
				sp7.teleportTo(sp7.getX() + 0.5, 200.0, sp7.getZ() + 0.5);
				sp7.setXRot(25.0F);
				sp7.getAbilities().flying = true;
				sp7.onUpdateAbilities();
				Vec3 fxPos = sp7.getEyePosition().add(sp7.getLookAngle().scale(3.5));
				for (int i = 0; i < 10; i++) {
					com.sandwalls.longbow.net.FxBroadcaster.trail(sp7.level(), fxPos.add(i * 0.4, 0.6 + i * 0.05, 0.0), java.awt.Color.HSBtoRGB(i / 10.0F, 0.9F, 1.0F) & 0xFFFFFF);
				}
				lines.add("INFO fx preview spawned");
			}


			boolean keepInventory = source.getServer().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
			if (keepInventory) {
				pass++;
				lines.add("PASS keep-inventory");
			} else {
				fail++;
				lines.add("FAIL keep-inventory");
			}


			if (source.getEntity() instanceof ServerPlayer sp8) {
				sp8.getInventory().setSelectedItem(new ItemStack(LongbowItems.ARCFALL_BALLAD));
				double before = sp8.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
				com.sandwalls.longbow.logic.DashManager.set(sp8, 1);
				double gear1 = sp8.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
				com.sandwalls.longbow.logic.DashManager.set(sp8, 2);
				double gear2 = sp8.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
				if (gear1 > before * 1.5 && gear2 > gear1) {
					pass++;
					lines.add("PASS dash-speed gear1=" + String.format("%.2f", gear1) + " gear2=" + String.format("%.2f", gear2));
				} else {
					fail++;
					lines.add("FAIL dash-speed " + before + "/" + gear1 + "/" + gear2);
				}
				sp8.getInventory().setSelectedItem(new ItemStack(LongbowItems.ARCFALL_LONGBOW));
			}


			ItemStack enchantStack = new ItemStack(LongbowItems.ARCFALL_LONGBOW);
			if (source.getEntity() instanceof ServerPlayer sp9) {
				LongbowItems.ARCFALL_LONGBOW.inventoryTick(enchantStack, (net.minecraft.server.level.ServerLevel) sp9.level(), sp9, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
				int enchCount = enchantStack.getEnchantments().size();
				if (enchCount >= 6) {
					pass++;
					lines.add("PASS auto-enchant count=" + enchCount);
				} else {
					fail++;
					lines.add("FAIL auto-enchant count=" + enchCount);
				}
			}


			if (source.getEntity() instanceof ServerPlayer sp10) {
				sp10.getInventory().setSelectedItem(new ItemStack(LongbowItems.ARCFALL_LONGBOW));
				boolean correct = sp10.hasCorrectToolForDrops(Blocks.STONE.defaultBlockState());
				sp10.getInventory().setSelectedItem(new ItemStack(LongbowItems.ARCFALL_BALLAD));
				boolean balladCorrect = sp10.hasCorrectToolForDrops(Blocks.STONE.defaultBlockState());
				if (correct && !balladCorrect) {
					pass++;
					lines.add("PASS mining-drops");
				} else {
					fail++;
					lines.add("FAIL mining-drops bow=" + correct + " ballad=" + balladCorrect);
				}
			}


			if (source.getEntity() instanceof ServerPlayer sp11) {
				sp11.getInventory().setSelectedItem(new ItemStack(LongbowItems.ARCFALL_LONGBOW));
				com.sandwalls.longbow.logic.DashManager.set(sp11, 1);
				double bowSpeed = sp11.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
				com.sandwalls.longbow.logic.DashManager.set(sp11, 0);
				if (bowSpeed > 0.5) {
					pass++;
					lines.add("PASS dash-with-bow speed=" + String.format("%.2f", bowSpeed));
				} else {
					fail++;
					lines.add("FAIL dash-with-bow speed=" + bowSpeed);
				}
			}


			int lightCharge = ArcfallLongbowItem.computeCharge(5);
			double lightRadius = ArcfallLongbowItem.blastRadius(ArcfallLongbowItem.LIGHT_CHARGE);
			double lightKill = ArcfallLongbowItem.killRadius(ArcfallLongbowItem.LIGHT_CHARGE);
			if (lightCharge == ArcfallLongbowItem.LIGHT_CHARGE && lightRadius == 0.0 && lightKill >= 3.0) {
				pass++;
				lines.add("PASS light-shot charge=" + lightCharge + " blastR=" + lightRadius + " killR=" + lightKill);
			} else {
				fail++;
				lines.add("FAIL light-shot " + lightCharge + "/" + lightRadius + "/" + lightKill);
			}


			if (source.getEntity() instanceof ServerPlayer sp12) {
				// 26.1 的 setBlock 在建筑范围外是静默失败（不报错、方块也不会出现），
				// 所以测试铺方块前必须先把自己安置回合法高度里，否则 stoneGone=true 是
				// "越界本来就是空气" 假阳性、debrisKept=false 是"根本没放下去"。
				int minY = sp12.level().getMinY();
				int maxY = sp12.level().getMaxY();
				double wantY = sp12.position().y + 60.0;
				double safeY = net.minecraft.util.Mth.clamp(wantY, minY + 4.0, maxY - 4.0);
				Vec3 c = new Vec3(sp12.position().x, safeY, sp12.position().z);
				BlockPos base = BlockPos.containing(c);
				lines.add("INFO prospect-mode base=" + base.getX() + "," + base.getY() + "," + base.getZ()
					+ " (want y=" + String.format("%.1f", wantY) + " clamp=" + String.format("%.1f", safeY)
					+ " range=" + minY + ".." + maxY + ")");
				for (int dx = -3; dx <= 3; dx++) {
					for (int dy = -3; dy <= 3; dy++) {
						for (int dz = -3; dz <= 3; dz++) {
							sp12.level().setBlock(base.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 2);
						}
					}
				}
				sp12.level().setBlock(base, Blocks.ANCIENT_DEBRIS.defaultBlockState(), 2);
				com.sandwalls.longbow.logic.MiningModeManager.set(sp12, true);
				Arrow oreArrow = new Arrow(sp12.level(), c.x, c.y, c.z, new ItemStack(Items.ARROW), null);
				oreArrow.setOwner(sp12);
				ArcfallArrowData.destroy(sp12.level(), c, new Vec3(0.0, -1.0, 0.0), 4000, oreArrow);
				for (int i = 0; i < 300 && com.sandwalls.longbow.logic.DestructionQueue.hasWork(); i++) {
					com.sandwalls.longbow.logic.DestructionQueue.tick();
				}
				boolean stoneGone = sp12.level().getBlockState(base.offset(2, 0, 2)).isAir();
				boolean debrisKept = sp12.level().getBlockState(base).is(Blocks.ANCIENT_DEBRIS);
				com.sandwalls.longbow.logic.MiningModeManager.set(sp12, false);
				oreArrow.discard();
				if (stoneGone && debrisKept) {
					pass++;
					lines.add("PASS prospect-mode stoneGone=true debrisKept=true");
				} else {
					fail++;
					lines.add("FAIL prospect-mode stoneGone=" + stoneGone + " debrisKept=" + debrisKept);
				}
			}


			if (source.getEntity() instanceof ServerPlayer sp13) {
				sp13.getInventory().setSelectedItem(new ItemStack(LongbowItems.ARCFALL_LONGBOW));
				// 手持状态原本是 tick 回调里刷的，这里显式刷一次，别依赖上一 tick 手里正好拿着弓
				com.sandwalls.longbow.LongbowMod.applyHeldState(sp13);
				boolean phantomRegistered = com.sandwalls.longbow.LongbowMod.PHANTOM != null;
				boolean hasPhantom = phantomRegistered && sp13.hasEffect(com.sandwalls.longbow.LongbowMod.PHANTOM);
				String effectName = phantomRegistered ? com.sandwalls.longbow.LongbowMod.PHANTOM.value().getDisplayName().getString() : "";
				if (phantomRegistered && hasPhantom && "幻".equals(effectName)) {
					pass++;
					lines.add("PASS phantom-effect");
				} else {
					fail++;
					lines.add("FAIL phantom-effect registered=" + phantomRegistered + " has=" + hasPhantom + " name=" + effectName);
				}
			}


			ItemStack nameStack = new ItemStack(LongbowItems.ARCFALL_LONGBOW);
			net.minecraft.network.chat.Component hover = nameStack.getHoverName();
			var siblings = hover.getSiblings();
			boolean rainbow = siblings.size() >= 6
				&& siblings.get(0).getStyle().getColor() != null
				&& siblings.get(siblings.size() - 1).getStyle().getColor() != null
				&& !siblings.get(0).getStyle().getColor().equals(siblings.get(siblings.size() - 1).getStyle().getColor());
			if (rainbow) {
				pass++;
				lines.add("PASS rainbow-name chars=" + siblings.size());
			} else {
				fail++;
				lines.add("FAIL rainbow-name chars=" + siblings.size());
			}


			if (source.getEntity() instanceof ServerPlayer sp14) {
				sp14.getInventory().setSelectedItem(new ItemStack(LongbowItems.ARCFALL_LONGBOW));
				com.sandwalls.longbow.LongbowMod.applyHeldState(sp14);
				boolean immuneRegistered = com.sandwalls.longbow.LongbowMod.IMMUNE != null;
				boolean hasImmune = immuneRegistered && sp14.hasEffect(com.sandwalls.longbow.LongbowMod.IMMUNE);
				String immuneName = immuneRegistered ? com.sandwalls.longbow.LongbowMod.IMMUNE.value().getDisplayName().getString() : "";
				if (immuneRegistered && hasImmune && "水火不侵".equals(immuneName)) {
					pass++;
					lines.add("PASS immune-effect name=" + immuneName);
				} else {
					fail++;
					lines.add("FAIL immune-effect registered=" + immuneRegistered + " has=" + hasImmune + " name=" + immuneName);
				}
			}


			if (source.getEntity() instanceof ServerPlayer sp15) {
				var ender = net.minecraft.world.entity.EntityType.ENDERMAN.create((net.minecraft.server.level.ServerLevel) sp15.level(),
					net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				if (ender != null) {
					Vec3 base = sp15.position().add(0.0, 3.0, 0.0);
					ender.setPos(base.x, base.y, base.z);
					ender.setNoAi(true);
					ender.setPersistenceRequired();
					sp15.level().addFreshEntity(ender);
					float hpBefore = ender.getMaxHealth();
					Arrow enderArrow = new Arrow((net.minecraft.server.level.ServerLevel) sp15.level(), base.x, base.y + 6.0, base.z,
						new ItemStack(Items.ARROW), null);
					enderArrow.setOwner(sp15);
					enderArrow.setDeltaMovement(0.0, -4.0, 0.0);
					ArcfallArrowData.mark(enderArrow, 0, ender);
					for (int i = 0; i < 40 && !enderArrow.isRemoved(); i++) {
						enderArrow.tick();
					}
					float hpAfter = ender.getHealth();
					if (hpAfter < hpBefore) {
						pass++;
						lines.add("PASS enderman-hit " + hpBefore + "->" + hpAfter);
					} else {
						fail++;
						lines.add("FAIL enderman-hit " + hpBefore + "->" + hpAfter);
					}
					ender.discard();
					enderArrow.discard();
				}
			}

		int total = pass + fail;
		String summary = "longbow selftest: " + pass + "/" + total + " passed";
		Component message = Component.literal(summary + " | " + String.join(" ; ", lines));
		source.sendSuccess(() -> message, false);
		com.sandwalls.longbow.LongbowMod.LOGGER.info(message.getString());
		return pass;
	}
}
