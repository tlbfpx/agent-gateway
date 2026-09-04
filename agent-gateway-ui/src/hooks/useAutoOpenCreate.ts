/**
 * useAutoOpenCreate — 支持 ?action=create 查询参数自动打开创建抽屉
 *
 * 背景：⌘K Quick Actions 导航到 /models?action=create 等路径，
 * 但各页面从未读取该参数——功能承诺未兑现。
 *
 * 用法（页面组件内，在 openCreate 定义之后）：
 *   useAutoOpenCreate(openCreate);
 *
 * 行为：
 *   - mount 时检查 location.search 中的 action=create → 触发回调
 *   - 触发后清除 URL 参数（replace，不产生历史记录）
 *   - 同一路由再次带参数进入（Quick Action 二次点击）也能触发
 */
import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

export function useAutoOpenCreate(openCreate: () => void) {
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    if (params.get('action') !== 'create') return;

    openCreate();

    // 清除查询参数，避免刷新后重复弹出
    params.delete('action');
    const qs = params.toString();
    navigate(location.pathname + (qs ? `?${qs}` : ''), { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.search, location.pathname]);
}
