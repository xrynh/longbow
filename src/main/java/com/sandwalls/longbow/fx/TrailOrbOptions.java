package com.sandwalls.longbow.fx;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;

/**
 * 拖尾旁的彩色经验球参数：颜色 + 起始个头 + 寿命 + 提亮量。
 *
 * <p>为什么不用原版 {@code DustParticleOptions}：尘埃粒子的寿命是
 * {@code baseLifetime * scale}（8~40 tick 再乘 scale），想让它活得久就得把 scale 一起放大，
 * 球就变成一张大饼。这里把颜色、大小、寿命拆开，球可以"又小又活得久"。</p>
 *
 * @param color  24 位 RGB，来自箭身当前的彩虹相位
 * @param size   世界尺寸（格），也就是四边形边长，不是半径
 * @param life   寿命（tick），默认 300 = 15 秒 = 拖尾 45 秒的三分之一
 * @param bright 0~1，往白色里混多少，用来让一部分球看起来"更烫"
 */
public record TrailOrbOptions(int color, float size, int life, float bright) implements ParticleOptions {
	public static final MapCodec<TrailOrbOptions> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		ExtraCodecs.RGB_COLOR_CODEC.fieldOf("color").forGetter(TrailOrbOptions::color),
		Codec.FLOAT.fieldOf("size").forGetter(TrailOrbOptions::size),
		Codec.INT.optionalFieldOf("life", 300).forGetter(TrailOrbOptions::life),
		Codec.FLOAT.optionalFieldOf("bright", 0.0F).forGetter(TrailOrbOptions::bright)
	).apply(i, TrailOrbOptions::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrailOrbOptions> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.INT, TrailOrbOptions::color,
		ByteBufCodecs.FLOAT, TrailOrbOptions::size,
		ByteBufCodecs.VAR_INT, TrailOrbOptions::life,
		ByteBufCodecs.FLOAT, TrailOrbOptions::bright,
		TrailOrbOptions::new);

	@Override
	public ParticleType<?> getType() {
		return LongbowParticles.TRAIL_ORB;
	}
}
