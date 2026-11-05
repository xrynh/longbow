package com.sandwalls.longbow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sandwalls.longbow.item.ArcfallLongbowItem;
import com.sandwalls.longbow.logic.ArcfallArrowData;
import com.sandwalls.longbow.net.FxPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 客户端特效管理器。
 *
 * <p>接收服务端广播的 {@link FxPayload}（kind=0 拖尾光点 / kind=1 命中六芒星），
 * 维护各自的剩余寿命，再在 {@code LevelRenderEvents.BEFORE_GIZMOS} 里把这些特效
 * 画成"发光半透明面片"。</p>
 *
 * <h2>渲染管线</h2>
 * <p>用的是自建的 {@link #FX_QUADS}（管线 {@code RenderPipelines.LIGHTNING}：顶点格式
 * {@code POSITION_COLOR}、拓扑 {@code QUADS}、加光混合 {@code BlendFunction.LIGHTNING}、
 * 深度测试 {@code LESS_THAN_OR_EQUAL}）。<b>必须用 Iris 白名单里的管线</b>，否
 * 则在光影下会画出满屏蓝色乱块，详见 {@link #FX_QUADS} 的注释。</p>
 * <ul>
 *   <li>特效会被方块和地形正确遮挡（深度测试开）；</li>
 *   <li>几百个半透明面片叠起来就是"发光"的近似效果，不需要真正的 bloom。</li>
 * </ul>
 *
 * <h2>坐标约定（和原版 {@code DrawableGizmoPrimitives} 一致，别改）</h2>
 * <p>在 {@code BEFORE_GIZMOS} 这一刻，{@code poseStack} 是 {@code LevelRenderer} 里那个
 * 新建的恒等 {@code PoseStack}，而着色器用的 {@code ModelViewMat} 只含相机旋转、不含平移，
 * 所以顶点必须写 <b>世界坐标 − 相机坐标</b>（相机坐标取
 * {@code cameraRenderState.pos}）。朝向相机的基向量由
 * {@code cameraRenderState.orientation} 旋转单位轴得到（x→右，y→上，-z→前）。</p>
 *
 * <h2>两个开关</h2>
 * <ul>
 *   <li>{@link #USE_GIZMOS}：改 true 就退回旧的 Gizmos 线框画法（对照 / 排查用）。</li>
 *   <li>{@link #DEBUG_FORCE}：改 true 会在相机正前方 3 格画一个 1×1 白色测试方片，
 *       用来快速确认"面片管线 + 坐标约定"通不通；正式游戏请保持 false。</li>
 * </ul>
 */
public final class ClientFxManager {
	/**
	 * 拖尾小方框寿命：45 秒 = 900 tick（主人要的是"射一箭，彩色小方框拖尾停留很久"）。
	 *
	 * <p>箭飞一趟约 1～3 秒，服务端沿位移每 0.2 格补一个点
	 * （{@code fxCount = min(24, max(8, ceil(delta/0.2)))} → 慢箭 8 点/tick、4 格/tick 的快箭 21 点/tick），
	 * 所以 45 秒满打满算约 600～18900 个框，每个框 12 条棱、每条棱一个四边形，
	 * 靠 {@link #MAX_TRAILS} 封顶兜住极端情况。</p>
	 */
	public static final int TRAIL_LIFE = 900;
	/** 命中六芒星寿命：15 秒 = 300 tick */
	public static final int BURST_LIFE = 100;

	/**
	 * 渲染方式开关。
	 * <ul>
	 *   <li>{@code false}（当前）：用自建 {@link #FX_QUADS} 画发光面片。</li>
	 *   <li>{@code true}：退回 Gizmos 线框 / 实心方块画法——<b>这条路径只用于排障</b>，
	 *       画出来是方块轮廓，而且它走的 {@code debug_filled_box} 管线不在 Iris 白名单里，
	 *       开光影时会变成"满屏蓝色发光小方块、看着像发光线框笼子"（也就是主人报的那个现象）。
	 *       它其实每帧都在画，跟 F3 无关。</li>
	 * </ul>
	 * 改这里要重新 {@code gradle build}。
	 */
	private static final boolean USE_GIZMOS = false;

	/**
	 * 自建 RenderType：管线用 {@code RenderPipelines.LIGHTNING}，<b>不要</b>改回任何
	 * {@code DEBUG_*} 管线。
	 *
	 * <h2>为什么不能用 DEBUG_* 管线（Iris 兼容性）</h2>
	 * <p>Iris 的 {@code MixinShaderManager_Overrides#redirectIrisProgram} 拦截
	 * {@code GlDevice#getOrCompilePipeline}，只对 {@code IrisPipelines} 里那张
	 * coreShaderMap 白名单（ARMOR_* / ENTITY_* / LIGHTNING / LINES / TEXT* / PARTICL* /
	 * BEACON_BEAM* / DRAGON_RAYS … 共 60 余条）做 shader 替换。<b>DEBUG_QUADS、
	 * DEBUG_FILLED_BOX、DEBUG_POINTS、DEBUG_TRIANGLE_FAN 都不在白名单里</b>：开启光影时
	 * 控制台会刷 {@code Missing program minecraft:pipeline/debug_quads in override list.
	 * This is likely an Iris bug!!!}，该管线拿到的程序/顶点属性映射和光影世界不匹配 →
	 * 面片颜色与混合全错，几百个光点叠起来就是"满屏蓝色发光小方块"，同时正常拖尾看不出
	 * 颜色。之前的 Gizmos 线框路线（{@code debug_filled_box}）栽的是同一个坑。</p>
	 *
	 * <p>{@code LIGHTNING} 是白名单里唯一的 {@code DefaultVertexFormat.POSITION_COLOR} +
	 * {@code Mode.QUADS} 管线：顶点格式和本类写顶点的方式完全一致，混合是
	 * {@code BlendFunction.LIGHTNING}（src*alpha + dst，即加光），正好符合"发光光点"。</p>
	 *
	 * <p>也不用 {@code RenderTypes.debugQuads()}：它带 {@code sortOnUpload()}，顶点会先进
	 * 排序缓冲，在 {@code BEFORE_GIZMOS} 里显式 {@code endBatch} 刷不掉。</p>
	 */
	private static final RenderType FX_QUADS = RenderType.create(
		"longbow_fx_quads",
		RenderSetup.builder(RenderPipelines.LIGHTNING).createRenderSetup()
	);

	/*
	 * 箭头星芒<b>不用贴图</b>：ENERGY_SWIRL（Iris 白名单里唯一"带 Sampler0 + 加光混合"的管线）实测能把四边形
	 * 画出来（原版贴图会显示成一个暗方块），但采样我们自己的 128×128 星芒贴图时整片透明、什么都看不到
	 * （细节见 docs/实现原理.md §5.1）。所以星芒改成纯几何 + 顶点色 alpha 渐变，走已验证可用的
	 * {@link #FX_QUADS}（LIGHTNING 管线）批次，见 {@link #star}。
	 */

	private static boolean DEBUG_FORCE = false;

	/**
	 * 拖尾框数量上限，防止长时间挂机把列表撑爆。
	 *
	 * <p>一个框 12 条棱（12 个四边形、48 个顶点），16384 个框约 19.6 万四边形；
	 * 45 秒寿命 + 21 点/tick 的极端情况是 18900 个框，所以这里留了 16384 当硬上限、
	 * 超了就从最老的开始丢（见 {@code add()}），宁可少一截尾巴也不掉帧。</p>
	 */
	private static final int MAX_TRAILS = 20000;
	private static final int MAX_BURSTS = 128;

	/**
	 * 拖尾旁的彩色小球（{@code longbow:trail_orb}）的撒点参数。
	 *
	 * <p>每 {@link #ORB_EVERY} 个拖尾点撒一颗，位置撒在轨迹周围一大圈（侧向 0.70~3.80 格、
	 * 上下 ±2.2 格），是"整条尾迹附近飘着一片球"，不贴着轨迹线走。
	 * 拖尾点间距锁在 0.15 格（见 {@code AbstractArrowMixin}），每个点一颗就是沿轨迹约 0.15 格一颗（一箭能撒出几百颗）。</p>
	 */
	private static final int ORB_EVERY = 1;
	/** 每 tick 最多撒几颗：快箭最多 28 个拖尾点/tick，所以这一档实际不截断，只是兜住异常值 */
	private static final int ORB_PER_TICK = 28;
	/** 球寿命 300 tick = 15 秒，正好是拖尾 45 秒的三分之一 */
	private static final int ORB_LIFE = 300;
	private static final int ORB_LIFE_JITTER = 90;
	/** 撒点范围：侧向最近 / 最远半径、上下振幅（格） */
	private static final double ORB_SPREAD_NEAR = 0.70;
	private static final double ORB_SPREAD_FAR = 3.80;
	private static final double ORB_SPREAD_Y = 2.20;

	/** 撒点节奏：跨 tick 连续计数，保证沿轨迹的密度均匀（不是每 tick 重新从 0 起） */
	private static int orbCursor;
	private static int orbThisTick;
	/** 累计撒了多少颗球，只用于真机日志核对（"拖尾包 → 粒子"这条链有没有真的通） */
	private static int orbsSpawned;

	private static final java.util.concurrent.ThreadLocalRandom RNG =
		java.util.concurrent.ThreadLocalRandom.current();

	/** 记住"已经确认是长虹箭"的实体 id：玩家放箭后换手，白色十字不会立刻消失 */
	private static final Map<Integer, Integer> ARROW_MEMO = new HashMap<>();
	private static final int ARROW_MEMO_LIFE = 600;

	private static final List<Trail> TRAILS = new ArrayList<>();
	private static final List<Burst> BURSTS = new ArrayList<>();

	/** 画圆环用的段数：48 段在 3 格半径下已经看不出棱角 */
	private static final int RING_SEGMENTS = 48;

	private static final double TAU = Math.PI * 2.0;

	private static boolean registered;
	private static boolean loggedFirst;
	private static boolean loggedNoPose;
	private static int tickCounter;
	private static Level lastLevel;

	/** 真机排障：{@code -Dlongbow.autotest=1} 时每 40 帧往日志里打一次渲染诊断 */
	private static final boolean FX_DEBUG = System.getProperty("longbow.autotest") != null;
	private static int frameNo;

	/** 真机排障：{@link #debugSeedBox()} 塞进来的白色线框盒，{@code DEBUG_BOX_TICKS} 归零就清掉 */
	private static AABB DEBUG_BOX;
	private static int DEBUG_BOX_TICKS;
	/** 真机排障：{@link #debugSeedFlare()} 塞进来的星芒位置 */
	/** 真机排障用：{@link #debugSeedFlare()} 放一个星芒在固定位置，确认渲染链路通不通。 */
	private static Vec3 DEBUG_FLARE;
	private static int DEBUG_FLARE_TICKS;

	private ClientFxManager() {}

	/** 注册网络接收、寿命递减与世界渲染。由 {@link LongbowClient#onInitializeClient()} 调用一次。 */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;

		ClientPlayNetworking.registerGlobalReceiver(FxPayload.TYPE, (payload, context) ->
			context.client().execute(() -> add(payload)));

		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());

		LevelRenderEvents.BEFORE_GIZMOS.register(ClientFxManager::render);
	}

	/** 收到一个特效数据包，放进对应的列表里。 */
	private static void add(FxPayload payload) {
		if (!loggedFirst) {
			loggedFirst = true;
			LongbowClient.LOGGER.info("[longbow] 收到第一个特效包 kind={} pos=({}, {}, {})", payload.kind(), payload.x(), payload.y(), payload.z());
		}
		switch (payload.kind()) {
			case FxPayload.TRAIL -> {
				if (TRAILS.size() >= MAX_TRAILS) {
					TRAILS.remove(0);
				}
				TRAILS.add(new Trail(payload.x(), payload.y(), payload.z(), payload.color(),
					Math.max(0.025F, payload.radius()), TRAIL_LIFE, payload.dx() != 0.0));
				// 同一条拖尾点顺手决定"这里要不要长出一颗彩色小球"
				spawnOrbs(payload);
			}
			case FxPayload.BURST, FxPayload.CAST -> {
				// 六芒星已按主人要求移除，收到也忽略
			}
			default -> {
				// 未知类型直接忽略，方便以后加新特效
			}
		}
	}

	/**
	 * 拖尾旁撒一颗彩色小球：位置不落在轨迹线上，而是偏到两侧、上下也抖一点。
	 *
	 * <p>用 {@code addParticle(..., overrideLimiter = true, ...)} 而不是普通重载：
	 * {@code ClientLevel.doAddParticle} 对普通粒子有"离相机 &gt; 32 格就不生成"的硬限制
	 * （{@code distanceToSqr > 1024.0}），而长虹箭经常飞到几十格开外，45 秒寿命的尾迹
	 * 又留得很久 —— 不绕开这个限制，箭头一远球就整片不冒了，还不会报错。</p>
	 */
	private static void spawnOrbs(FxPayload payload) {
		if (++orbCursor % ORB_EVERY != 0 || orbThisTick >= ORB_PER_TICK) {
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		orbThisTick++;
		double angle = RNG.nextDouble() * TAU;
		// 半径用 sqrt 撒，圆周上分布才均匀（不然会挤在近处）
		double radius = Math.sqrt(RNG.nextDouble()) * (ORB_SPREAD_FAR - ORB_SPREAD_NEAR) + ORB_SPREAD_NEAR;
		double x = payload.x() + Math.cos(angle) * radius;
		double z = payload.z() + Math.sin(angle) * radius;
		double y = payload.y() + (RNG.nextDouble() - 0.5) * 2.0 * ORB_SPREAD_Y;
		spawnOrb(level, x, y, z, payload.color());
	}

	/** 单颗球：随机个头 / 寿命 / 上浮初速，少数"更烫"的会往白色里混一点。 */
	private static void spawnOrb(ClientLevel level, double x, double y, double z, int color) {
		// 基准 0.16~0.27 格被主人嫌大两轮，2026-10-29 按「半径减半」砍到 ≈0.080~0.135 格
		// （取了主人给的上限那一档；再嫌大就把除数改成 3.0F，正好是 1/3）
		float size = (0.16F + RNG.nextFloat() * 0.11F) / 2.0F;
		int life = ORB_LIFE - RNG.nextInt(ORB_LIFE_JITTER);
		float bright = RNG.nextFloat() < 0.18F ? 0.30F + RNG.nextFloat() * 0.25F : 0.0F;
		spawnOrb(level, x, y, z, color, size, life, bright);
	}

	/** 指定个头 / 寿命的球，排障用的样品走这个重载。 */
	private static void spawnOrb(ClientLevel level, double x, double y, double z, int color,
			float size, int life, float bright) {
		double vx = (RNG.nextDouble() - 0.5) * 0.012;
		double vy = 0.004 + RNG.nextDouble() * 0.014;
		double vz = (RNG.nextDouble() - 0.5) * 0.012;
		addOrbParticle(level, x, y, z, color, size, life, bright, vx, vy, vz);
	}

	private static void addOrbParticle(ClientLevel level, double x, double y, double z, int color,
			float size, int life, float bright, double vx, double vy, double vz) {
		orbsSpawned++;
		level.addParticle(new com.sandwalls.longbow.fx.TrailOrbOptions(color, size, life, bright),
			true, false, x, y, z, vx, vy, vz);
	}

	/**
	 * 真机排障用：在相机正前方摆 3 颗 <b>屏幕尺寸相同</b> 的球（4 / 8 / 16 格），
	 * 用来肉眼检查球体贴图的质感（塑料感 / 边缘 / 亮度），寿命只给 40 tick，不污染正式截图。
	 */
	public static void debugSeedOrbPortrait() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition(1.0F);
		Vec3 fwd = mc.player.getViewVector(1.0F).normalize();
		Vec3 right = fwd.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
		Vec3 up = right.cross(fwd).normalize();
		double[] dists = {4.0, 8.0, 16.0};
		int[] colors = {0xFF5050, 0x50F080, 0x6080FF};
		for (int i = 0; i < dists.length; i++) {
			double d = dists[i];
			Vec3 p = eye.add(fwd.scale(d)).add(right.scale((i - 1) * 1.9)).add(up.scale(-0.9));
			// 世界尺寸 = 0.075 × 距离 → 屏幕上恒定约 37 像素，三颗一样大才好比质感
			float size = (float) (0.075 * d);
			spawnOrb(mc.level, p.x, p.y, p.z, colors[i], size, 40, 0.0F);
		}
		LongbowClient.LOGGER.info("[longbow] debugSeedOrbPortrait: 3 颗（4/8/16 格）");
	}

	/**
	 * 真机排障用：把相机转到最新一批拖尾点的正中，好在截图里看清「小方框 + 烟尘」。
	 *
	 * <p>自测世界里那支箭常常笔直飞进屏幕外（或者岩浆里），截图里根本看不见拖尾，
	 * 靠肉眼判断就成了猜。这里直接取 TRAILS 队列中间那个点转镜头，截图就有据可依。
	 */
	public static void debugLookAtLastTrail(Minecraft mc) {
		if (TRAILS.isEmpty() || mc.player == null) {
			return;
		}
		Trail mid = TRAILS.get(TRAILS.size() / 2);
		Vec3 eye = mc.player.getEyePosition(1.0F);
		double dx = mid.x - eye.x;
		double dy = mid.y - eye.y;
		double dz = mid.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz < 1.0E-4) {
			return;
		}
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
		mc.player.setYRot(yaw);
		mc.player.setYBodyRot(yaw);
		mc.player.setYHeadRot(yaw);
		mc.player.setXRot(pitch);
		LongbowClient.LOGGER.info("[longbow] debugLookAtLastTrail: target=({}, {}, {}) yaw={} pitch={}",
			mid.x, mid.y, mid.z, yaw, pitch);
	}

	/**
	 * 真机排障用：在相机右边一字排开一串彩虹拖尾光点，用来确认
	 * "管线 → 顶点 → 颜色 → 深度"这条链路是不是通的（不依赖真的射箭）。
	 *
	 * <p>放在准星右侧而不是正前方，是为了避开聊天栏和准星（截图时看得清）。
	 * 只在 {@code -Dlongbow.autotest=1} 的自测流程里被调用，正常游戏不会走到。</p>
	 */
	public static void debugSeedTrail(int count) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition(1.0F);
		Vec3 fwd = mc.player.getViewVector(1.0F);
		Vec3 right = fwd.cross(new Vec3(0.0, 1.0, 0.0));
		if (right.lengthSqr() < 1.0E-6) {
			right = new Vec3(1.0, 0.0, 0.0);
		}
		right = right.normalize();
		Vec3 up = right.cross(fwd).normalize();
		Vec3 base = eye.add(fwd.scale(10.0)).add(up.scale(-1.5)).add(right.scale(1.5));
		for (int i = 0; i < count; i++) {
			double t = count <= 1 ? 0.0 : i / (double) (count - 1);
			Vec3 p = base.add(right.scale(t * 5.5));
			int rgb = java.awt.Color.HSBtoRGB((float) t, 0.9F, 1.0F) & 0xFFFFFF;
			if (TRAILS.size() >= MAX_TRAILS) {
				TRAILS.remove(0);
			}
			// 只有链尾标 head，一跑自测就能看到"星芒挂在拖尾头部"
			TRAILS.add(new Trail(p.x, p.y, p.z, rgb, 0.045F, TRAIL_LIFE, i == count - 1));
		}
		LongbowClient.LOGGER.info("[longbow] debugSeedTrail: {} 个点，eye=({}, {}, {}) fwd=({}, {}, {})",
			count, eye.x, eye.y, eye.z, fwd.x, fwd.y, fwd.z);
		// 顺带撒一片球：截图里要能同时看到「小方框链」和「周围飘着的彩色球」。
		// 走的是真实的 spawnOrb（和收包时同一条路径），不是另外伪造一份。
		if (mc.level != null) {
			int seeded = 0;
			for (int i = 0; i < count; i++) {
				double t = count <= 1 ? 0.0 : i / (double) (count - 1);
				Vec3 p = base.add(right.scale(t * 5.5));
				int rgb = java.awt.Color.HSBtoRGB((float) t, 0.9F, 1.0F) & 0xFFFFFF;
				double ang = RNG.nextDouble() * TAU;
				double rad = Math.sqrt(RNG.nextDouble()) * (ORB_SPREAD_FAR - ORB_SPREAD_NEAR) + ORB_SPREAD_NEAR;
				spawnOrb(mc.level, p.x + Math.cos(ang) * rad, p.y + (RNG.nextDouble() - 0.5) * 2.0 * ORB_SPREAD_Y,
					p.z + Math.sin(ang) * rad, rgb);
				seeded++;
			}
			LongbowClient.LOGGER.info("[longbow] debugSeedTrail: 另撒 {} 颗 trail_orb", seeded);
		}
	}

	/**
	 * 真机排障用：在准星右侧画一个边长 1 格的白色线框盒，验证
	 * {@link #boxWire} 的 12 条棱在光影开关下都画得出来、而且朝向正确。
	 *
	 * <p>位置跟 {@link #debugSeedTrail} 的彩虹点错开，避免叠在一起看不清。</p>
	 */
	/** 真机排障用：在准星右上方挂一个星芒，验证贴图 / 管线 / 顶点格式通不通。 */
	public static void debugSeedFlare() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition(1.0F);
		Vec3 fwd = mc.player.getViewVector(1.0F).normalize();
		Vec3 right = fwd.cross(new Vec3(0.0, 1.0, 0.0));
		if (right.lengthSqr() < 1.0E-6) {
			right = new Vec3(1.0, 0.0, 0.0);
		}
		right = right.normalize();
		Vec3 up = right.cross(fwd).normalize();
		DEBUG_FLARE = eye.add(fwd.scale(9.0)).add(right.scale(2.5)).add(up.scale(2.0));
		DEBUG_FLARE_TICKS = 400;
		LongbowClient.LOGGER.info("[longbow] debugSeedFlare: pos=({}, {}, {})", DEBUG_FLARE.x, DEBUG_FLARE.y, DEBUG_FLARE.z);
	}

	public static void debugSeedBox() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition(1.0F);
		Vec3 fwd = mc.player.getViewVector(1.0F);
		Vec3 right = fwd.cross(new Vec3(0.0, 1.0, 0.0));
		if (right.lengthSqr() < 1.0E-6) {
			right = new Vec3(1.0, 0.0, 0.0);
		}
		right = right.normalize();
		Vec3 up = right.cross(fwd).normalize();
		Vec3 c = eye.add(fwd.scale(11.0)).add(up.scale(1.8)).add(right.scale(3.0));
		DEBUG_BOX = new AABB(c.x - 0.5, c.y - 0.5, c.z - 0.5, c.x + 0.5, c.y + 0.5, c.z + 0.5);
		DEBUG_BOX_TICKS = 600;
		LongbowClient.LOGGER.info("[longbow] debugSeedBox: center=({}, {}, {})", c.x, c.y, c.z);
	}

	/** 每个客户端 tick 递减一次寿命，过期的移除。 */
	private static void tick() {
		tickCounter++;
		orbThisTick = 0;
		if (DEBUG_BOX_TICKS > 0 && --DEBUG_BOX_TICKS == 0) {
			DEBUG_BOX = null;
		}
		if (DEBUG_FLARE_TICKS > 0 && --DEBUG_FLARE_TICKS == 0) {
			DEBUG_FLARE = null;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			lastLevel = null;
			loggedFirst = false;
			TRAILS.clear();
			BURSTS.clear();
			ARROW_MEMO.clear();
			return;
		}
		if (mc.level != lastLevel) {
			// 换维度 / 换世界：上一个世界的特效不该跟过来
			lastLevel = mc.level;
			loggedFirst = false;
			TRAILS.clear();
			BURSTS.clear();
			ARROW_MEMO.clear();
			orbCursor = 0;
			return;
		}
		for (Iterator<Trail> it = TRAILS.iterator(); it.hasNext(); ) {
			if (--it.next().remaining <= 0) {
				it.remove();
			}
		}
		for (Iterator<Burst> it = BURSTS.iterator(); it.hasNext(); ) {
			if (--it.next().remaining <= 0) {
				it.remove();
			}
		}
		if (!ARROW_MEMO.isEmpty()) {
			ARROW_MEMO.entrySet().removeIf(entry -> tickCounter - entry.getValue() > ARROW_MEMO_LIFE);
		}
	}

	// ==================================================================
	// 世界渲染入口
	// ==================================================================

	private static void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		if (USE_GIZMOS) {
			renderWithGizmos(mc, level);
			return;
		}
		renderWithQuads(context, mc, level);
	}

	/**
	 * 主渲染路径：一个 RenderType（{@link #FX_QUADS}）搞定全部特效，写完统一 {@code endBatch}。
	 *
	 * <p>注意：顶点写完之后 <b>必须显式调用 {@code bufferSource.endBatch(renderType)}</b>，
	 * 否则这批顶点会一直留在 BufferBuilder 里，直到这一帧后面某个不确定的时刻才被顺带刷掉，
	 * 表现就是"写了但看不见"。</p>
	 */
	private static void renderWithQuads(LevelRenderContext context, Minecraft mc, ClientLevel level) {
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource buffers = context.bufferSource();
		if (poseStack == null || buffers == null) {
			// 理论上不该发生（Fabric 会在 LevelRenderer 新建 PoseStack 时塞进来）。
			// 真遇上了就退回 Gizmos，至少特效还在。
			if (!loggedNoPose) {
				loggedNoPose = true;
				LongbowClient.LOGGER.warn("[longbow] BEFORE_GIZMOS 拿不到 poseStack/bufferSource，特效退回 Gizmos 线框渲染");
			}
			renderWithGizmos(mc, level);
			return;
		}

		RenderType renderType = FX_QUADS;
		VertexConsumer vc = buffers.getBuffer(renderType);
		PoseStack.Pose pose = poseStack.last();

		CameraRenderState camera = context.levelState().cameraRenderState;
		Vec3 cam = camera.pos;
		// 相机局部轴 → 世界方向：x 向右、y 向上、-z 为视线方向（见 Camera#setRotation）
		Vector3f right = new Vector3f(1.0F, 0.0F, 0.0F).rotate(camera.orientation);
		Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(camera.orientation);

		if (FX_DEBUG && (++frameNo % 40 == 0)) {
			Vec3 mainCam = mc.gameRenderer.getMainCamera().position();
			LongbowClient.LOGGER.info("[longbow][fxdbg] frame={} trails={} orbs={} camState=({}, {}, {}) mainCam=({}, {}, {}) poseT=({}, {}, {})",
				frameNo, TRAILS.size(), orbsSpawned, cam.x, cam.y, cam.z, mainCam.x, mainCam.y, mainCam.z,
				pose.pose().m03(), pose.pose().m13(), pose.pose().m23());
		}

		submitTrails(vc, pose, cam);
		submitBursts(vc, pose, cam);
		submitLockBoxes(vc, pose, cam, mc);
		if (DEBUG_BOX != null) {
			boxWire(vc, pose, cam, DEBUG_BOX, 0xFFFFFFFF, 0.020);
		}
		if (DEBUG_FLARE != null) {
			star(vc, pose, cam, DEBUG_FLARE, 0.9F, 0xFFFFFF, right, up);
		}

		if (DEBUG_FORCE) {
			Vector3f forward = new Vector3f(0.0F, 0.0F, -1.0F).rotate(camera.orientation);
			double cx = cam.x + forward.x() * 3.0;
			double cy = cam.y + forward.y() * 3.0;
			double cz = cam.z + forward.z() * 3.0;
			billboard(vc, pose, cam, cx, cy, cz, 0.5F, 0.5F, 0xCCFFFFFF, right, up);
		}

		submitArrowHeads(vc, pose, cam, mc, right, up);

		// 必须显式提交，见方法注释
		buffers.endBatch(renderType);
	}

	// ==================================================================
	// 拖尾光点
	// ==================================================================

	/**
	 * 拖尾：每个点画一个<b>空心小方框</b>（12 条发光棱，没有填充面）。
	 *
	 * <p>主人要的就是这个形态 —— 像按 F3 时那些"只有几个边的小方框"，而不是实心方片。
	 * 每条棱一个面朝相机的细带（见 {@link #bladeFacing}），加光混合下自然发光；
	 * 方框随寿命淡出，颜色是服务端按 tick 递进的彩虹色。</p>
	 */
	private static void submitTrails(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam) {
		for (Trail trail : TRAILS) {
			float curve = fadeCurve(trail.remaining, TRAIL_LIFE);
			if (curve <= 0.01F) {
				continue;
			}
			// 方框半边长：payload 的 radius 是 0.03，乘 1.2 得到边长约 0.072 格的小方框，
			// 配合服务端 0.15 格的补点间距，一串框会连成一条连续的细彩带
			float half = Mth.clamp(trail.size * 1.2F, 0.03F, 0.15F);
			int color = argb(curve, trail.color & 0xFFFFFF);
			Vec3 center = new Vec3(trail.x, trail.y, trail.z);
			// 棱粗：近处按框边长的 13% 走（最早 18% 太粗、压到 7% 又太细，主人 10-29 说要粗一些），
			// 远处置信 dist*0.0011 那档 —— 面片在世界里的厚度会随距离缩成亚像素而整条消失，
			// 按"1 像素约等于 dist*0.0011 格"补到 1 像素多一点，远处才还看得见框
			double dist = center.distanceTo(cam);
			double thick = Math.min(0.05, Math.max(half * 0.13, dist * 0.0011));
			boxWire(vc, pose, cam, new AABB(
				center.x - half, center.y - half, center.z - half,
				center.x + half, center.y + half, center.z + half), color, thick);
		}
	}

	// ==================================================================
	// 命中六芒星
	// ==================================================================

	/**
	 * 命中六芒星：两个相反放置的等边三角形（半透明白色<b>填充面</b>，不是线框）
	 * + 一个同心、略大的白色半透明圆环，全部平铺在命中点的水平面上。
	 *
	 * <p>两个三角形重叠出的中心六边形 alpha 更高，看起来中心更亮；
	 * 边缘再压一条很细的亮线，让形状在暗处也有轮廓。</p>
	 */
	private static void submitBursts(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam) {
	}

	// ==================================================================
	// 长虹箭的白色十字
	// ==================================================================

	/**
	 * 长虹箭的白色十字：三条都过箭中心、互相垂直的白色发光"叶片"，白色半透明叠加 ——
	 * 交叠处自然更亮，像一颗白星。
	 *
	 * <p>三条叶片分别是：沿 X 的横臂（厚度在 Y，落在 XY 平面）、沿 Y 的竖臂
	 * （厚度在 X，也落在 XY 平面）、沿 Z 的纵深臂（厚度在 X，落在 XZ 平面）。
	 * 正面看是一个白色加号，侧面/俯视还能看到第三条，所以从任何角度看都是个十字。
	 * 三条都是白色，绘制顺序不影响观感（不像拖尾那样需要按深度错开）。</p>
	 *
	 * <p>服务端的实体 tag（{@code ArcfallArrowData.isLongbowArrow}）在 26.1 里不会同步给客户端，
	 * 所以这里额外用"箭的 owner 是不是正拿着长弓的玩家"来兜底，并把认出来的箭 id 记住 30 秒。</p>
	 */
	/**
	 * 给"飞行中的长虹箭"画星芒。
	 *
	 * <p><b>为什么不遍历实体</b>：实体列表只在箭真的存在于客户端世界时才给得到它（自测世界里那支箭
	 * 一落地就被移除了，`entitiesForRendering()` 里数了一遍一直是 0 支），而拖尾包是每 tick 都到、
	 * 且 192 格内一定收得到。所以这里改用最新的拖尾点当"箭头位置"：服务端每 tick 沿箭头轨迹补点，
	 * 最后一个点就是箭头当前所在位置，误差不到 0.2 格。</p>
	 *
	 * <p>只取最近 2 tick 的点（`remaining >= TRAIL_LIFE - 2`），按 0.36 格聚类去重，
	 * 这样同时飞着几支箭时每支各得一个星芒，而一条拖尾不会刷出一串星星。</p>
	 */
	private static void submitArrowHeads(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam,
			Minecraft mc, Vector3f right, Vector3f up) {
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		List<Vec3> heads = new ArrayList<>(4);
		List<Integer> headColors = new ArrayList<>(4);
		int limit = Math.max(0, TRAILS.size() - 120);
		for (int i = TRAILS.size() - 1; i >= limit; i--) {
			Trail trail = TRAILS.get(i);
			if (trail.remaining < TRAIL_LIFE - 2) {
				break;
			}
			if (!trail.head) {
				continue;
			}
			heads.add(new Vec3(trail.x, trail.y, trail.z));
			headColors.add(trail.color);
		}
		for (int h = 0; h < heads.size(); h++) {
			Vec3 p = heads.get(h);
			int trailColor = headColors.get(h);
			double dist = p.distanceTo(cam);
			if (dist > 256.0) {
				continue;
			}
			// 屏幕尺寸大致恒定：世界半边长 ≈ 0.045 × 距离（近处给下限，远处封顶）
			float half = Mth.clamp((float) (dist * 0.045), 0.18F, 2.6F);
			// 呼吸：0.35 rad/tick → 约 18 tick 一个来回，慢慢闪不刺眼
			half *= 1.0F + 0.12F * Mth.sin((mc.player == null ? 0 : mc.player.tickCount) + partial);
			star(vc, pose, cam, p, half, mixWhite(trailColor, 0.62F), right, up);
		}
	}

	/**
	 * 画一个正对相机的星芒：细长的 4 主刺 + 4 短斜刺 + 很淡的圆晕 + 一颗小白核。
	 *
	 * <p>层与层之间沿"朝相机"方向错开 0.01 格，避免同深度打架（加法混合叠在一起会闪）。
	 * 主刺之所以画成四边形条带而不是三角形，是 {@code LIGHTNING} 管线只吃 {@code QUADS}。</p>
	 */
	private static void star(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 center, float half,
			int rgb, Vector3f right, Vector3f up) {
		Vec3 r = new Vec3(right.x, right.y, right.z);
		Vec3 u = new Vec3(up.x, up.y, up.z);
		Vec3 toCam = towardCamera(cam, center);
		Vec3 coreBase = center.add(toCam.scale(0.01));
		Vec3 spikeBase = center.add(toCam.scale(0.02));

		// 1) 圆晕：两层同心环，内环亮、外环收到 0 —— 圆心是点状累加，所以中心自然最亮
		int seg = 20;
		double innerR = half * 0.20;
		double halo = half * 0.62;
		int hotC = argb(0.82F, rgb);
		int midC = argb(0.26F, rgb);
		int cuffC = argb(0.0F, rgb);
		for (int i = 0; i < seg; i++) {
			double a0 = Math.PI * 2.0 * i / seg;
			double a1 = Math.PI * 2.0 * (i + 1) / seg;
			Vec3 i0 = coreBase.add(r.scale(Math.cos(a0) * innerR)).add(u.scale(Math.sin(a0) * innerR));
			Vec3 i1 = coreBase.add(r.scale(Math.cos(a1) * innerR)).add(u.scale(Math.sin(a1) * innerR));
			Vec3 o0 = coreBase.add(r.scale(Math.cos(a0) * halo)).add(u.scale(Math.sin(a0) * halo));
			Vec3 o1 = coreBase.add(r.scale(Math.cos(a1) * halo)).add(u.scale(Math.sin(a1) * halo));
			tri(vc, pose, cam, coreBase, i0, i1, hotC, midC, midC);
			tri(vc, pose, cam, coreBase, o0, o1, midC, cuffC, cuffC);
		}

		// 2) 4 根主刺：长 2.0 × half、根宽 0.035 × half，越往尖越淡
		Vec3[] dirs = {r, u, r.scale(-1.0), u.scale(-1.0)};
		for (Vec3 dir : dirs) {
			needle(vc, pose, cam, spikeBase, dir, perpOf(dir, r, u), half * 2.0, half * 0.045, rgb);
		}

		// 3) 4 根斜刺：短一半、更淡，做出 8 道光芒
		for (int k = 0; k < 4; k++) {
			double a = Math.PI * 0.25 + k * Math.PI * 0.5;
			Vec3 dir = r.scale(Math.cos(a)).add(u.scale(Math.sin(a))).normalize();
			needle(vc, pose, cam, spikeBase, dir, r.scale(-Math.sin(a)).add(u.scale(Math.cos(a))),
				half * 0.95, half * 0.022, argb(0.55F, mixWhite(rgb, 0.4F)) & 0xFFFFFF);
		}

	}

	/** 一根渐细的针：沿 dir 分 3 段，宽度和透明度一起收到 0。 */
	private static void needle(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 base, Vec3 dir, Vec3 perp,
			double len, double width, int rgb) {
		double[] t = {0.0, 0.30, 0.62, 1.0};
		double[] w = {1.0, 0.62, 0.28, 0.02};
		float[] a = {0.95F, 0.62F, 0.28F, 0.0F};
		Vec3[] p = new Vec3[t.length];
		for (int i = 0; i < t.length; i++) {
			p[i] = base.add(dir.scale(len * t[i]));
		}
		for (int i = 0; i < t.length - 1; i++) {
			double w0 = width * w[i];
			double w1 = width * w[i + 1];
			int c0 = argb(a[i], rgb);
			int c1 = argb(a[i + 1], rgb);
			Vec3 a0 = p[i].add(perp.scale(w0));
			Vec3 a1 = p[i].add(perp.scale(-w0));
			Vec3 b0 = p[i + 1].add(perp.scale(-w1));
			Vec3 b1 = p[i + 1].add(perp.scale(w1));
			vertex(vc, pose, cam, a0, c0);
			vertex(vc, pose, cam, a1, c0);
			vertex(vc, pose, cam, b0, c1);
			vertex(vc, pose, cam, b1, c1);
		}
	}

	/** 垂直于 {@code dir} 且在 r/u 平面内的单位向量（dir 写成 a·r + b·u，垂线就是 -b·r + a·u）。 */
	private static Vec3 perpOf(Vec3 dir, Vec3 r, Vec3 u) {
		double a = dir.dot(r);
		double b = dir.dot(u);
		Vec3 candidate = r.scale(-b).add(u.scale(a));
		double len = candidate.length();
		return len < 1.0E-6 ? u : candidate.scale(1.0 / len);
	}

	/** 一根尖刺：从 base 沿 dir 到尖端，宽度从 width 收到 0，颜色从根部渐隐到尖端的 tipColor。 */
	private static void spike(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 base, Vec3 dir, Vec3 perp,
			double len, double width, int baseColor, int midColor, int tipColor) {
		Vec3 tip = base.add(dir.scale(len));
		Vec3 bl = base.add(perp.scale(width)).add(dir.scale(width * 0.5));
		Vec3 br = base.add(perp.scale(-width)).add(dir.scale(width * 0.5));
		// 两段三角：根部→中段（宽），中段→尖端（窄），中段颜色用 midColor 衔接
		Vec3 ml = base.add(perp.scale(width * 0.42)).add(dir.scale(len * 0.45));
		Vec3 mr = base.add(perp.scale(-width * 0.42)).add(dir.scale(len * 0.45));
		Vec3 m = base.add(dir.scale(len * 0.45));
		tri(vc, pose, cam, base, bl, br, baseColor, midColor, midColor);
		tri(vc, pose, cam, m, ml, mr, midColor, midColor, midColor);
		tri(vc, pose, cam, m, mr, tip, midColor, midColor, tipColor);
		tri(vc, pose, cam, m, tip, ml, midColor, tipColor, midColor);
	}

	/** 退化四边形表达的三角形（LIGHTNING 管线只吃 QUADS）。 */
	private static void tri(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 a, Vec3 b, Vec3 c,
			int ca, int cb, int cc) {
		vertex(vc, pose, cam, a, ca);
		vertex(vc, pose, cam, b, cb);
		vertex(vc, pose, cam, c, cc);
		vertex(vc, pose, cam, c, cc);
	}

	private static void blade(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 center,
			double ux, double uy, double uz, double nx, double ny, double nz, int color) {
		Vec3 u = new Vec3(ux, uy, uz);
		Vec3 n = new Vec3(nx, ny, nz);
		quad(vc, pose, cam,
			center.add(u).add(n),
			center.add(u).subtract(n),
			center.subtract(u).subtract(n),
			center.subtract(u).add(n),
			color);
	}

	/**
	 * 只发<b>一个</b>朝向相机的叶片：先把厚度向量 {@code rawN} 摆正，这片的正面才朝相机。
	 *
	 * <p>{@link #quad} 的顶点顺序是 {@code (c+u+n, c+u-n, c-u-n, c-u+n)}，
	 * 正面法线方向为 {@code n × u}；管线开了背面剔除，所以必须让
	 * {@code (n × u) · 指向相机} 为正，否则这一条棱会整个消失。</p>
	 */
	private static void bladeFacing(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 center,
			Vec3 u, Vec3 rawN, int color) {
		if (rawN.lengthSqr() < 1.0E-8 || u.lengthSqr() < 1.0E-8) {
			return;
		}
		Vec3 toCam = towardCamera(cam, center);
		Vec3 n = rawN;
		if (n.cross(u).dot(toCam) < 0.0) {
			n = n.scale(-1.0);
		}
		blade(vc, pose, cam, center, u.x, u.y, u.z, n.x, n.y, n.z, color);
	}

	// ==================================================================
	// 锁定 / 瞄准白框
	// ==================================================================

	/**
	 * 锁定白框：把 {@link LockOnRenderer} 算出来的实体 AABB 画成 12 条棱的 3D 线框。
	 *
	 * <p>这些框以前是 {@link LockOnRenderer} 自己用 {@code Gizmos.cuboid(...)} 画的，
	 * 但 gizmo 走的 {@code debug_filled_box} / {@code debug_lines} 管线不在 Iris 白名单里，
	 * 开光影时会画成乱块，所以挪进这个面片批次里。</p>
	 */
	private static void submitLockBoxes(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Minecraft mc) {
		if (!LockOnRenderer.isHolding(mc)) {
			return;
		}
		AABB aimed = LockOnRenderer.aimedBox(mc);
		if (aimed != null) {
			boxWire(vc, pose, cam, aimed, argb(0.80F, 0xFFFFFF), 0.020);
		}
		AABB locked = LockOnRenderer.lockedBox(mc);
		if (locked != null) {
			boxWire(vc, pose, cam, locked, 0xFFFFFFFF, 0.034);
		}
	}

	/**
	 * 把 AABB 的 12 条棱画成"面朝相机的细带"。
	 *
	 * <p>每条棱：沿棱方向取半长向量 {@code u}，厚度方向取 {@code normalize(cross(u, 指向相机))}，
	 * 两者张开的四边形正好朝着相机，不容易被侧着看成一条缝。管线默认剔除背面，
	 * 所以每条棱只发<b>一个</b>朝向相机的面（{@link #bladeFacing}），比正反各发一遍省一半顶点。</p>
	 */
	private static void boxWire(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, AABB box, int color, double halfThick) {
		Vec3[] c = new Vec3[] {
			new Vec3(box.minX, box.minY, box.minZ),
			new Vec3(box.maxX, box.minY, box.minZ),
			new Vec3(box.minX, box.maxY, box.minZ),
			new Vec3(box.maxX, box.maxY, box.minZ),
			new Vec3(box.minX, box.minY, box.maxZ),
			new Vec3(box.maxX, box.minY, box.maxZ),
			new Vec3(box.minX, box.maxY, box.maxZ),
			new Vec3(box.maxX, box.maxY, box.maxZ)
		};
		int[][] edges = new int[][] {
			{0, 1}, {1, 3}, {3, 2}, {2, 0},
			{4, 5}, {5, 7}, {7, 6}, {6, 4},
			{0, 4}, {1, 5}, {2, 6}, {3, 7}
		};
		for (int[] e : edges) {
			Vec3 p = c[e[0]];
			Vec3 q = c[e[1]];
			Vec3 center = p.add(q).scale(0.5);
			Vec3 u = q.subtract(p).scale(0.5);
			Vec3 dir = u.normalize();
			Vec3 n = dir.cross(towardCamera(cam, center));
			if (n.length() < 1.0E-4) {
				n = dir.cross(new Vec3(0.0, 1.0, 0.0));
			}
			if (n.length() < 1.0E-4) {
				continue;
			}
			bladeFacing(vc, pose, cam, center, u, n.normalize().scale(halfThick), color);
		}
	}

	// ==================================================================
	// 旧的 Gizmos 画法（USE_GIZMOS = true 时的回退路径）
	// ==================================================================

	/** 回退路径：用 Gizmos 画方块 / 六芒星 / 十字线，已验证可见、带深度遮挡。 */
	private static void renderWithGizmos(Minecraft mc, ClientLevel level) {
		for (Trail trail : TRAILS) {
			float curve = fadeCurve(trail.remaining, TRAIL_LIFE);
			if (curve <= 0.01F) {
				continue;
			}
			Vec3 pos = new Vec3(trail.x, trail.y, trail.z);
			int rgb = trail.color & 0xFFFFFF;
			double core = Math.max(0.016, trail.size * 0.34);
			int glow = argb(curve * 0.16F, rgb);
			int body = argb(curve * 0.95F, rgb);
			double outer = core * 1.55;
			Gizmos.cuboid(new AABB(pos.x - outer, pos.y - outer, pos.z - outer, pos.x + outer, pos.y + outer, pos.z + outer),
				GizmoStyle.strokeAndFill(glow, 1.0F, glow));
			Gizmos.cuboid(new AABB(pos.x - core, pos.y - core, pos.z - core, pos.x + core, pos.y + core, pos.z + core),
				GizmoStyle.strokeAndFill(body, 1.0F, body));
		}

		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		double maxSq = 256.0 * 256.0;
		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof AbstractArrow arrow)) {
				continue;
			}
			if (arrow.distanceToSqr(cam) > maxSq) {
				continue;
			}
			if (!isLongbowArrow(arrow)) {
				continue;
			}
			Vec3 p = arrow.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			Gizmos.line(p.add(-0.26, 0.0, 0.0), p.add(0.26, 0.0, 0.0), 0xFFFFFFFF, 2.0F);
			Gizmos.line(p.add(0.0, -0.26, 0.0), p.add(0.0, 0.26, 0.0), 0xFFFFFFFF, 2.0F);
			Gizmos.line(p.add(0.0, 0.0, -0.26), p.add(0.0, 0.0, 0.26), 0xFFFFFFFF, 2.0F);
		}

		// 瞄准 / 锁定白框：回退路径下也用 gizmo 画，行为跟面片路径一致
		if (LockOnRenderer.isHolding(mc)) {
			AABB aimed = LockOnRenderer.aimedBox(mc);
			if (aimed != null) {
				Gizmos.cuboid(aimed, GizmoStyle.stroke(0xCCFFFFFF, 1.4F));
			}
			AABB locked = LockOnRenderer.lockedBox(mc);
			if (locked != null) {
				Gizmos.cuboid(locked, GizmoStyle.stroke(0xFFFFFFFF, 2.6F));
			}
		}
	}

	/** 客户端判断"这是不是长虹箭"。 */
	private static boolean isLongbowArrow(Entity entity) {
		int id = entity.getId();
		Integer memo = ARROW_MEMO.get(id);
		if (memo != null && tickCounter - memo <= ARROW_MEMO_LIFE) {
			return true;
		}
		if (ArcfallArrowData.isLongbowArrow(entity)) {
			ARROW_MEMO.put(id, tickCounter);
			return true;
		}
		if (entity instanceof AbstractArrow arrow && arrow.getOwner() instanceof Player owner) {
			boolean holding = owner.getMainHandItem().getItem() instanceof ArcfallLongbowItem
				|| owner.getOffhandItem().getItem() instanceof ArcfallLongbowItem;
			if (holding) {
				ARROW_MEMO.put(id, tickCounter);
				return true;
			}
		}
		return false;
	}

	// ==================================================================
	// 画几何的小工具（全部按"世界坐标 − 相机坐标"写顶点）
	// ==================================================================

	/** 面朝相机的方片：中心在 (x, y, z)，半宽 halfW、半高 halfH，颜色 argb。 */
	private static void billboard(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, double x, double y, double z,
			float halfW, float halfH, int color, Vector3f right, Vector3f up) {
		float cx = (float) (x - cam.x);
		float cy = (float) (y - cam.y);
		float cz = (float) (z - cam.z);
		float rx = right.x() * halfW;
		float ry = right.y() * halfW;
		float rz = right.z() * halfW;
		float ux = up.x() * halfH;
		float uy = up.y() * halfH;
		float uz = up.z() * halfH;
		vertex(vc, pose, cx - rx - ux, cy - ry - uy, cz - rz - uz, color);
		vertex(vc, pose, cx + rx - ux, cy + ry - uy, cz + rz - uz, color);
		vertex(vc, pose, cx + rx + ux, cy + ry + uy, cz + rz + uz, color);
		vertex(vc, pose, cx - rx + ux, cy - ry + uy, cz - rz + uz, color);
	}

	/** 用四个世界坐标点画一个四边形（顶点顺序绕一圈即可，不剔除背面所以正反都看得见）。 */
	private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
		vertex(vc, pose, cam, a, color);
		vertex(vc, pose, cam, b, color);
		vertex(vc, pose, cam, c, color);
		vertex(vc, pose, cam, d, color);
	}

	/** 水平面上从 p 到 q 的一条细带（用四边形近似线段），用于六芒星描边。 */
	private static void edgeQuad(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 p, Vec3 q, double halfWidth, int color) {
		double dx = q.x - p.x;
		double dz = q.z - p.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0E-5) {
			return;
		}
		double nx = -dz / len * halfWidth;
		double nz = dx / len * halfWidth;
		quad(vc, pose, cam,
			new Vec3(p.x + nx, p.y, p.z + nz),
			new Vec3(q.x + nx, q.y, q.z + nz),
			new Vec3(q.x - nx, q.y, q.z - nz),
			new Vec3(p.x - nx, p.y, p.z - nz),
			color);
	}

	/** 水平面上的圆环（内径 inner、外径 outer），用 {@link #RING_SEGMENTS} 段四边形拼出来。 */
	private static void annulus(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam,
			double cx, double cy, double cz, double inner, double outer, int color) {
		for (int i = 0; i < RING_SEGMENTS; i++) {
			double a0 = i * TAU / RING_SEGMENTS;
			double a1 = (i + 1) * TAU / RING_SEGMENTS;
			quad(vc, pose, cam,
				flatPoint(cx, cy, cz, inner, a0),
				flatPoint(cx, cy, cz, outer, a0),
				flatPoint(cx, cy, cz, outer, a1),
				flatPoint(cx, cy, cz, inner, a1),
				color);
		}
	}

	/** 水平面上以 (cx, cy, cz) 为心、半径 radius、角度 angle 处的点。 */
	private static Vec3 flatPoint(double cx, double cy, double cz, double radius, double angle) {
		return new Vec3(cx + Math.cos(angle) * radius, cy, cz + Math.sin(angle) * radius);
	}

	/** 从 center 指向相机的单位向量；两者重合时退回零向量。 */
	private static Vec3 towardCamera(Vec3 cam, Vec3 center) {
		Vec3 dir = cam.subtract(center);
		double len = dir.length();
		if (len < 1.0E-4) {
			return Vec3.ZERO;
		}
		return dir.scale(1.0 / len);
	}

	/** 世界坐标顶点 → "世界坐标 − 相机坐标" 后写进缓冲区。 */
	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 p, int color) {
		vertex(vc, pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), color);
	}

	/** 已经减过相机坐标的顶点。 */
	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, int color) {
		vc.addVertex(pose, x, y, z).setColor(color);
	}

	/** 剩余寿命 → 透明度系数：开头足、后段慢慢淡出（平方根曲线，比直线更耐看）。 */
	private static float fadeCurve(int remaining, int life) {
		float t = Math.max(0.0F, Math.min(1.0F, remaining / (float) life));
		return (float) Math.sqrt(t);
	}

	private static int argb(float alpha, int rgb) {
		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	private static int mixWhite(int rgb, float amount) {
		float t = Math.max(0.0F, Math.min(1.0F, amount));
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		r = (int) Mth.lerp(t, r, 255.0F);
		g = (int) Mth.lerp(t, g, 255.0F);
		b = (int) Mth.lerp(t, b, 255.0F);
		return (r << 16) | (g << 8) | b;
	}

	/** 一个拖尾光点。 */
	private static final class Trail {
		private final double x;
		private final double y;
		private final double z;
		private final int color;
		private final float size;
		private int remaining;
		/** 服务端把"这一 tick 的最后一个点"标成 head，它才是箭头当前位置，星芒只挂在它上面。 */
		private final boolean head;

		private Trail(double x, double y, double z, int color, float size, int remaining, boolean head) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.color = color;
			this.size = size;
			this.remaining = remaining;
			this.head = head;
		}
	}

	/** 一个命中六芒星。 */
	private static final class Burst {
		private final double x;
		private final double y;
		private final double z;
		private final double dirX;
		private final double dirY;
		private final double dirZ;
		private final float radius;
		private final int color;
		private int remaining;

		private Burst(double x, double y, double z, double dirX, double dirY, double dirZ, float radius, int color, int remaining) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.dirX = dirX;
			this.dirY = dirY;
			this.dirZ = dirZ;
			this.radius = radius;
			this.color = color;
			this.remaining = remaining;
		}
	}
}
