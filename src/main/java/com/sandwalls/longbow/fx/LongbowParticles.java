package com.sandwalls.longbow.fx;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * 本模组的自定义粒子类型。
 *
 * <p>只有 {@link #TRAIL_ORB}：拖尾旁那些彩色小球的"球体"。贴图走粒子图集
 * （{@code assets/longbow/particles/trail_orb.json} → {@code textures/particle/orb_ball.png}），
 * 客户端渲染由 {@code client/particle/TrailOrbParticle} 负责。</p>
 *
 * <p>三件套缺一不可（之前烟尘踩过）：① 这里注册 {@code ParticleType}；
 * ② {@code assets/longbow/particles/<name>.json} 给精灵集；③ 客户端
 * {@code ParticleProviderRegistry} 注册 provider。少任何一环粒子会被静默丢掉，
 * 日志里只有 {@code Missing particle sprites} 或者什么都没有。</p>
 */
public final class LongbowParticles {
	private LongbowParticles() {
	}

	/** 拖尾旁的彩色小球（长虹箭尾迹两侧漂浮的那种）。 */
	public static final ParticleType<TrailOrbOptions> TRAIL_ORB = FabricParticleTypes.complex(
		TrailOrbOptions.CODEC, TrailOrbOptions.STREAM_CODEC);

	public static void initialize() {
		Registry.register(BuiltInRegistries.PARTICLE_TYPE,
			Identifier.fromNamespaceAndPath("longbow", "trail_orb"), TRAIL_ORB);
	}
}
