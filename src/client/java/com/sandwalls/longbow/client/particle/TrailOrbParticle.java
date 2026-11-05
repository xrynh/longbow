package com.sandwalls.longbow.client.particle;

import com.sandwalls.longbow.fx.TrailOrbOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 长虹箭尾迹旁的彩色小球 —— 参照原版经验球的模样，但颜色是彩虹、寿命由参数给。
 *
 * <h2>一条生命曲线（和 {@link TrailOrbOptions} 的三个参数配合）</h2>
 * <ul>
 *   <li><b>淡入</b>：前 0.8% 寿命（约 2.4 tick）alpha 从 0 升到 0.80 —— 几乎瞬现，
 *       2026-10-29 主人反馈"出现时有浮现感"，原来 4%/12 tick 的淡入 + 30 tick 的膨胀都砍掉了；</li>
 *   <li><b>长大</b>：前 1.5%（约 4.5 tick）从 0.80 倍涨到 1.0 倍，只是收个边，不是蓬起来；</li>
 *   <li><b>呼吸</b>：{@code 1 + 0.10*sin(age*0.11 + 相位)}，每个球相位随机，不同频闪；</li>
 *   <li><b>收缩收尾</b>：最后 26% 从 1.0 缩到 0.55，同时 alpha 淡到 0。</li>
 * </ul>
 *
 * <p>尺寸/透明度都在 {@code getQuadSize}/{@code tick} 里按 {@code age} 算，所以这里的
 * {@code partialTick} 也一起参与，50% 渲染插值下不会发抖。</p>
 *
 * <h2>为什么不继承 TexturedQuadParticle</h2>
 * <p>26.1 里这个基类就叫 {@code SingleQuadParticle}（贴图 + 单四边形），
 * {@code Layer.TRANSLUCENT} 会走粒子图集（{@code TextureAtlas.LOCATION_PARTICLES}）
 * 和 Iris 白名单里的 {@code TRANSLUCENT_PARTICLE} 管线 —— 这条链是纯原版粒子通道，
 * 不碰 {@code RenderSetup.withTexture}，所以光影开着也能采到图（贴图直连管线那条路
 * 之前给星芒踩过坑，见 docs/实现原理.md §5.1）。</p>
 */
public class TrailOrbParticle extends SingleQuadParticle {
	/**
	 * 整体透明度。贴图 `orb_ball.png` 本身就是半透光的（球心 alpha 0.90，到 r=0.6 只剩 0.32），
	 * 这里再乘 0.80 → 球心合成 alpha ≈0.72。
	 * 不拉满是为了去"塑料感"：原版经验球的顶点色 alpha 只有 0.5，看着像光不像塑料球。
	 */
	private static final float BASE_ALPHA = 0.80F;
	private static final float TAU = (float) (Math.PI * 2.0);

	private final int maxLife;
	private final float baseSize;
	private final float phase;

	protected TrailOrbParticle(ClientLevel level, double x, double y, double z, TrailOrbOptions options,
			TextureAtlasSprite sprite) {
		super(level, x, y, z, sprite);
		this.maxLife = Math.max(20, options.life());
		this.lifetime = this.maxLife;
		this.friction = 0.93F;
		this.gravity = 0.0F;
		// 球是"光"，不该被方块挡住：关掉碰撞，让它只做直线漂移
		this.hasPhysics = false;
		this.phase = this.random.nextFloat() * TAU;

		Vector3f rgb = ARGB.vector3fFromRGB24(options.color());
		float white = Mth.clamp(options.bright(), 0.0F, 1.0F);
		this.rCol = Mth.lerp(white, rgb.x(), 1.0F);
		this.gCol = Mth.lerp(white, rgb.y(), 1.0F);
		this.bCol = Mth.lerp(white, rgb.z(), 1.0F);

		this.baseSize = Math.max(0.02F, options.size());
		this.quadSize = this.baseSize * 0.80F;
		this.setAlpha(0.0F);
	}

	/** 出生时先给一个初速（上浮 + 一点侧漂），由 {@code ClientFxManager} 撒点的时候算好。 */
	void launch(double xa, double ya, double za) {
		this.setParticleSpeed(xa, ya, za);
	}

	@Override
	protected SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	/** 球是自发光的东西，固定吃满亮度，不然进洞/进阴影整颗球就黑了。 */
	@Override
	protected int getLightCoords(float partialTick) {
		return 0xF000F0;
	}

	@Override
	public float getQuadSize(float partialTick) {
		float age = this.age + partialTick;
		float t = Mth.clamp(age / (float) this.maxLife, 0.0F, 1.0F);
		float grow = t < 0.015F ? Mth.lerp(t / 0.015F, 0.80F, 1.0F) : 1.0F;
		float shrink = t > 0.74F ? Mth.lerp((t - 0.74F) / 0.26F, 1.0F, 0.55F) : 1.0F;
		float breath = 1.0F + 0.10F * Mth.sin(age * 0.11F + this.phase);
		return this.baseSize * grow * shrink * breath;
	}

	@Override
	public void tick() {
		super.tick();
		float t = Mth.clamp(this.age / (float) this.maxLife, 0.0F, 1.0F);
		float alpha;
		if (t < 0.008F) {
			alpha = BASE_ALPHA * (t / 0.008F);
		} else if (t > 0.76F) {
			float s = (t - 0.76F) / 0.24F;
			alpha = BASE_ALPHA * (1.0F - s * s * (3.0F - 2.0F * s));
		} else {
			alpha = BASE_ALPHA;
		}
		this.setAlpha(alpha);
	}

	public static class Provider implements ParticleProvider<TrailOrbOptions> {
		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public @Nullable Particle createParticle(TrailOrbOptions options, ClientLevel level,
				double x, double y, double z, double xa, double ya, double za, RandomSource random) {
			TrailOrbParticle particle = new TrailOrbParticle(level, x, y, z, options, this.sprites.first());
			particle.launch(xa, ya, za);
			return particle;
		}
	}
}
