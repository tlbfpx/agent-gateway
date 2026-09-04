/**
 * HeroBanner — Dashboard 顶部 AI 感横幅
 *
 * 视觉语言"深空智能舱"：
 *  - 极光渐变底（缓慢流动 auroraShift 动画，15s 循环）
 *  - 星点网格衬底（radial-gradient 平铺，呼应"神经/算力"）
 *  - 玻璃拟态信息卡（backdrop-blur）
 *  - 实时脉冲点（breathe 呼吸）+ 数据流动线（flowRight）
 *
 * 零依赖，纯 CSS。内容左标题右状态，不占过高纵向空间（120px 桌面）。
 */
import type { ReactNode } from 'react';

interface HeroBannerProps {
  title: ReactNode;
  sub?: ReactNode;
  /** 右侧状态区（如实时 Tag / 刷新按钮） */
  status?: ReactNode;
  /** 脉冲点颜色语义 */
  live?: boolean;
}

export function HeroBanner({ title, sub, status, live = true }: HeroBannerProps) {
  return (
    <section className="hero-banner" aria-label="系统概览横幅">
      <div className="hero-aurora" aria-hidden="true" />
      <div className="hero-stars" aria-hidden="true" />
      <div className="hero-flow" aria-hidden="true" />
      <HeroTopology live={live} />

      <div className="hero-body">
        <div className="hero-title-wrap">
          <div className="hero-eyebrow">
            <span className={`hero-pulse-dot ${live ? 'on' : 'off'}`} aria-hidden="true" />
            AGENT GATEWAY · CONTROL DECK
          </div>
          <h1 className="hero-title">{title}</h1>
          {sub && <div className="hero-sub">{sub}</div>}
        </div>
        {status && <div className="hero-status">{status}</div>}
      </div>
    </section>
  );
}

/**
 * HeroTopology — 右侧神经网络拓扑装饰（SVG）。
 * 六边形节点 + 连线 + 流动光点，AI 意象的图形锚点；
 * 低透明度常驻，不与内容争夺注意力。
 */
function HeroTopology({ live }: { live: boolean }) {
  const nodes = [
    { x: 20, y: 40 },
    { x: 55, y: 22 },
    { x: 55, y: 58 },
    { x: 92, y: 40 },
    { x: 38, y: 70 },
  ];
  const edges: [number, number][] = [
    [0, 1], [0, 2], [1, 3], [2, 3], [0, 4], [4, 2],
  ];
  return (
    <svg
      className="hero-topology"
      viewBox="0 0 112 80"
      width="150"
      height="107"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id="topoEdge" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="rgba(56, 189, 248, .55)" />
          <stop offset="100%" stopColor="rgba(139, 92, 246, .45)" />
        </linearGradient>
      </defs>
      {edges.map(([a, b], i) => (
        <line
          key={i}
          x1={nodes[a].x} y1={nodes[a].y} x2={nodes[b].x} y2={nodes[b].y}
          stroke="url(#topoEdge)"
          strokeWidth="0.8"
        />
      ))}
      {nodes.map((n, i) => (
        <circle
          key={i}
          cx={n.x} cy={n.y}
          r={i === 3 ? 3.4 : 2.4}
          fill={i === 3 ? 'rgba(212, 165, 116, .85)' : 'rgba(255, 255, 255, .5)'}
          className={live ? 'hero-topo-node' : undefined}
          style={{ animationDelay: `${i * 0.45}s` }}
        />
      ))}
      {live && (
        <circle r="1.6" fill="#7DD3FC">
          <animateMotion dur="3.8s" repeatCount="indefinite" path="M20,40 L55,22 L92,40" />
        </circle>
      )}
    </svg>
  );
}
