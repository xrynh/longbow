"""生成尾迹光球的贴图（assets/longbow/textures/particle/orb_ball.png）。

就是模组里正在用的那一版（内部代号 v8）。改完直接跑：

    python tools/orb_texture.py src/main/resources/assets/longbow/textures/particle/orb_ball.png

设计要点（改之前先看 docs/实现原理.md §5.7）：

- 粒子贴图乘的是「粒子颜色」，所以这里画**灰阶**，彩虹色由代码染。
- 粒子图集默认**邻近采样**，硬边放到 50px 以上会变成台阶 →
  所以边缘的 alpha 过渡留得很长（约 r 0.45 → 0.98），也不做细亮边。
- 「塑料味」来自 alpha 接近 1 + 明确高光 + 强球面明暗三件套。
  这里是**中心亮核 + 没有高光点 + 长柔边**，粒子侧再乘 0.80 的透明度，看起来才像光。

需要 Pillow + numpy。
"""
import sys

import numpy as np
from PIL import Image

N = 256   # 超采样分辨率
OUT = 64  # 图集里的实际尺寸


def smoothstep(edge0, edge1, x):
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "orb_ball.png"

    yy, xx = np.mgrid[0:N, 0:N].astype(np.float64)
    u = (xx + 0.5) / N * 2.0 - 1.0
    v = (yy + 0.5) / N * 2.0 - 1.0
    r = np.sqrt(u * u + v * v)

    # 半透光球：alpha 在整个盘面上从 0.88 平滑收到 0
    alpha = 0.88 * (1.0 - smoothstep(0.45, 0.98, r))
    # 亮度：整体往外压暗，中心再加一个亮核；没有 specular，所以不发"塑料光"
    core = (1.0 - smoothstep(0.0, 0.40, r)) ** 1.25
    val = 0.48 + 0.34 * (1.0 - smoothstep(0.0, 0.92, r)) + 0.50 * core
    # 极轻的上亮下暗，避免做成一个纯同心圆
    val += 0.07 * (1.0 - smoothstep(0.0, 0.9, r))

    val = np.clip(val, 0.0, 1.0)
    alpha = np.clip(alpha, 0.0, 1.0)
    gray = (val * 255.0).astype(np.uint8)
    a = (alpha * 255.0).astype(np.uint8)
    rgba = np.dstack([gray, gray, gray, a])

    img = Image.fromarray(rgba, "RGBA").resize((OUT, OUT), Image.LANCZOS)
    img.save(out)
    print("wrote", out, img.size)


if __name__ == "__main__":
    main()
