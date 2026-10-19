import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Card, Button, Tag, Typography, Divider, message, Skeleton, Transfer } from 'antd';
import { useTranslation } from 'react-i18next';
import { updateRoutePluginInstance } from '@/services';
import request from '@/services/request';

interface RouteItem {
  name: string;
  desc: string;
  // AI 路由自动生成的内部路由，用业务配置名（AI 路由管理页所见）作为友好显示名
  display?: string;
  isAi?: boolean;
}

// Transfer 数据项：在标准 key/title/description 之外附带内部路由名与标签态，供 render/过滤使用
interface TransferRoute {
  key: string;
  title: string;
  description: string;
  name: string;
  isAi: boolean;
  configured: boolean;
}

export interface RouteScope {
  routes: RouteItem[];
  bound: string[];
  selected: string[];
  loading: boolean;
  saving: boolean;
  setSelected: (v: string[]) => void;
  reload: () => Promise<void>;
  applyScope: (rawConfig: string) => Promise<void>;
}

// 围栏"作用路由"两层模型数据层：
// 路由级实例（matchRules）由 console /v1/routes/{route}/plugin-instances/{name} 管理，
// 启用态回显改由批量端点 /v1/wasm-plugins/{name}/route-scopes 一次读取（消除 N+1）。
export function useRouteScope(pluginName: string): RouteScope {
  const { t } = useTranslation();
  const [routes, setRoutes] = useState<RouteItem[]>([]);
  const [bound, setBound] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      // 三个请求并发：路由列表 + AI 路由业务名（友好命名用）+ 本插件已启用的路由名（单次批量）。
      // 此前对每条路由各发一次 getRoutePluginInstance，路由达 50+ 时加载明显卡顿，现合并为一次。
      const [listRes, aiRes, scopeRes] = await Promise.all([
        request.get('/v1/routes', { params: { pageNum: 1, pageSize: 1000 } }),
        request.get('/v1/ai/routes', { params: { pageNum: 1, pageSize: 1000 } }).catch(() => null),
        request.get(`/v1/wasm-plugins/${pluginName}/route-scopes`).catch(() => []),
      ]);
      const items: any[] = Array.isArray(listRes?.data) ? listRes.data : Array.isArray(listRes?.routes) ? listRes.routes : Array.isArray(listRes) ? listRes : [];
      const simple: RouteItem[] = items
        .filter((r: any) => r?.name)
        .map((r: any) => ({ name: r.name, desc: (r.domains || []).join(', ') }));
      // 拉取 AI 路由管理页的业务配置，将 ai-route-<name>.internal 翻译回用户认识的 AI 路由名；
      // 接口不可用时仅退化按原始路由名展示，不阻断主流程。
      const aiList: any[] = Array.isArray((aiRes as any)?.data) ? (aiRes as any).data : Array.isArray(aiRes) ? aiRes : [];
      const aiNames: string[] = aiList.map((a: any) => a?.name).filter(Boolean);
      const aiLabel = (n: string): string | undefined => {
        if (!n.startsWith('ai-route-') || !n.endsWith('.internal')) return undefined;
        const base = n.slice('ai-route-'.length, -'.internal'.length);
        const isFallback = base.endsWith('-fallback');
        const owner = isFallback ? base.slice(0, -'-fallback'.length) : base;
        if (!aiNames.includes(owner)) return undefined;
        return isFallback ? `${owner} (${t('aiContentSec.routeScope.fallbackSuffix')})` : owner;
      };
      // 批量端点返回 Response<List<String>>，经 request 拦截器解包后即为路由名数组
      const scopeNames: string[] = Array.isArray(scopeRes) ? scopeRes : Array.isArray((scopeRes as any)?.data) ? (scopeRes as any).data : [];
      const enabledNames = scopeNames.filter(Boolean);
      if (!alive.current) return;
      // .internal 内部路由中：AI 路由生成的展示为友好名（用户可见可配）；
      // 其它无主内部路由默认隐藏，但已挂启用路由级配置的仍展示，保证既有防护可见可控。
      setRoutes(
        simple
          .map((r) => {
            const label = aiLabel(r.name);
            return label === undefined ? r : { ...r, display: label, isAi: true };
          })
          .filter((r) => r.isAi || !r.name.endsWith('.internal') || enabledNames.includes(r.name)),
      );
      setBound(enabledNames);
      setSelected(enabledNames);
    } catch {
      if (alive.current) message.error(t('aiContentSec.routeScope.loadFail'));
    } finally {
      if (alive.current) setLoading(false);
    }
  }, [pluginName]);

  useEffect(() => { reload(); }, [reload]);

  const applyScope = useCallback(async (rawConfig: string) => {
    const toAdd = selected.filter((n) => !bound.includes(n));
    const toRemove = bound.filter((n) => !selected.includes(n));
    if (!toAdd.length && !toRemove.length) {
      message.info(t('aiContentSec.routeScope.noChange'));
      return;
    }
    setSaving(true);
    const failed: string[] = [];
    for (const name of toAdd) {
      try {
        await updateRoutePluginInstance({ name, pluginName }, { enabled: true, rawConfigurations: rawConfig });
      } catch {
        failed.push(name);
      }
    }
    for (const name of toRemove) {
      try {
        await request.delete(`/v1/routes/${name}/plugin-instances/${pluginName}`);
      } catch {
        failed.push(name);
      }
    }
    if (alive.current) setSaving(false);
    if (failed.length) {
      message.error(t('aiContentSec.routeScope.syncFail', { names: failed.join(', ') }));
    } else {
      message.success(t('aiContentSec.saveSuccess'));
    }
    await reload();
  }, [pluginName, selected, bound, reload]);

  return { routes, bound, selected, loading, saving, setSelected, reload, applyScope };
}

// 第二区 UI：antd 穿梭框（搜索 + 分页 + 跨页全选 + 已选计数），路由较多时避免全量平铺难检索/易误选
export const RouteScopeCard: React.FC<{
  scope: RouteScope;
  getRawConfig: () => string;
}> = ({ scope, getRawConfig }) => {
  const { t } = useTranslation();
  const dataSource: TransferRoute[] = scope.routes.map((r) => ({
    key: r.name,
    title: r.display || r.name,
    description: r.desc,
    name: r.name,
    isAi: !!r.isAi,
    configured: scope.bound.includes(r.name),
  }));
  return (
    <Card
      title={t('aiContentSec.routeScope.title')}
      style={{ marginBottom: 16 }}
      extra={(
        <Typography.Text type="secondary">
          {t('aiContentSec.routeScope.countText', {
            defaultValue: '已选 {{selected}} / 共 {{total}} 条',
            selected: scope.selected.length,
            total: scope.routes.length,
          })}
        </Typography.Text>
      )}
    >
      {scope.loading ? (
        <Skeleton active paragraph={{ rows: 4 }} />
      ) : (
        <>
          {dataSource.length ? (
            <Transfer
              dataSource={dataSource as any}
              titles={[
                t('aiContentSec.routeScope.availableTitle', '可选路由'),
                t('aiContentSec.routeScope.selectedTitle', '已选路由'),
              ]}
              operations={[t('aiContentSec.routeScope.opAdd', '添加'), t('aiContentSec.routeScope.opRemove', '移除')]}
              showSearch
              filterOption={(input, item) => {
                const kw = String(input || '').toLowerCase();
                if (!kw) return true;
                const r = item as TransferRoute;
                return (
                  String(r.title || '').toLowerCase().includes(kw)
                  || String(r.description || '').toLowerCase().includes(kw)
                  || String(r.name || '').toLowerCase().includes(kw)
                );
              }}
              render={(item) => {
                const r = item as TransferRoute;
                return (
                  <span>
                    <span style={{ fontWeight: 500 }}>{r.title}</span>
                    {r.isAi ? (
                      <Tag color="blue" style={{ marginLeft: 8 }}>{t('aiContentSec.routeScope.aiRoute')}</Tag>
                    ) : null}
                    {r.configured ? (
                      <Tag color="green" style={{ marginLeft: 8 }}>{t('aiContentSec.routeScope.configured')}</Tag>
                    ) : null}
                    {r.description ? (
                      <Typography.Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                        {r.description}
                      </Typography.Text>
                    ) : null}
                  </span>
                );
              }}
              targetKeys={scope.selected}
              onChange={(keys) => scope.setSelected(keys as string[])}
              pagination={{ pageSize: 8 }}
              listStyle={{ width: 340, height: 360 }}
              style={{ justifyContent: 'center' }}
            />
          ) : (
            <Typography.Text type="secondary">{t('aiContentSec.routeScope.empty')}</Typography.Text>
          )}
          <Divider />
          <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 16 }}>
            <Typography.Text type="secondary">{t('aiContentSec.routeScope.hint')}</Typography.Text>
            <Button
              type="primary"
              loading={scope.saving}
              onClick={() => scope.applyScope(getRawConfig())}
            >
              {t('aiContentSec.routeScope.saveScope')}
            </Button>
          </div>
        </>
      )}
    </Card>
  );
};

// 插件状态开关的影响范围说明：五页 Zone1 统一引用，避免文案漂移
export const GlobalSwitchHint: React.FC = () => {
  const { t } = useTranslation();
  return (
    <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
      {t('aiContentSec.routeScope.globalSwitchHint')}
    </Typography.Text>
  );
};
