import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Card, Button, Tag, Typography, Divider, message, Skeleton, Checkbox, Input, Segmented, Space } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
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

// 第二区 UI：清单式勾选（搜索 + 全部/已选/未选 + 行内勾选），操作语义直白：
// 勾选 = 作用路由；保存前用「待新增/待移除」把差异说清楚，再点「保存作用范围」生效。
export const RouteScopeCard: React.FC<{
  scope: RouteScope;
  getRawConfig: () => string;
  // 可选：保存作用范围前的配置校验（如凭据必填），返回 false 则阻止提交。
  // 不传则不校验，保持其他页面行为不变。
  validate?: () => boolean;
}> = ({ scope, getRawConfig, validate }) => {
  const { t } = useTranslation();
  const [keyword, setKeyword] = useState('');
  const [filter, setFilter] = useState<'all' | 'selected' | 'unselected'>('all');

  const kw = keyword.trim().toLowerCase();
  const visible = scope.routes.filter((r) => {
    const checked = scope.selected.includes(r.name);
    if (filter === 'selected' && !checked) return false;
    if (filter === 'unselected' && checked) return false;
    if (!kw) return true;
    return (
      String(r.display || r.name).toLowerCase().includes(kw)
      || String(r.desc || '').toLowerCase().includes(kw)
      || r.name.toLowerCase().includes(kw)
    );
  });

  const toggle = (name: string, checked: boolean) => {
    scope.setSelected(checked
      ? scope.selected.concat(name)
      : scope.selected.filter((n) => n !== name));
  };

  // 待生效差异：勾选态 vs 已生效（bound）态，保存前显式提示，避免“改了没生效”或“以为生效了”
  const toAdd = scope.selected.filter((n) => !scope.bound.includes(n));
  const toRemove = scope.bound.filter((n) => !scope.selected.includes(n));
  const dirty = toAdd.length > 0 || toRemove.length > 0;

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
          {scope.routes.length ? (
            <>
              <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center', marginBottom: 12 }}>
                <Input
                  allowClear
                  prefix={<SearchOutlined />}
                  placeholder={t('aiContentSec.routeScope.searchPlaceholder', '搜索路由名或域名')}
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  style={{ width: 260 }}
                />
                <Segmented
                  value={filter}
                  onChange={(v) => setFilter(v as 'all' | 'selected' | 'unselected')}
                  options={[
                    { value: 'all', label: t('aiContentSec.routeScope.filterAll', '全部') },
                    { value: 'selected', label: t('aiContentSec.routeScope.filterSelected', '已选') },
                    { value: 'unselected', label: t('aiContentSec.routeScope.filterUnselected', '未选') },
                  ]}
                />
                <Space size={4}>
                  <Typography.Link
                    onClick={() => {
                      const add = visible.map((r) => r.name).filter((n) => !scope.selected.includes(n));
                      scope.setSelected(scope.selected.concat(add));
                    }}
                  >
                    {t('aiContentSec.routeScope.selectAll', '全选')}
                  </Typography.Link>
                  <Typography.Text type="secondary">/</Typography.Text>
                  <Typography.Link
                    onClick={() => {
                      const drop = visible.map((r) => r.name);
                      scope.setSelected(scope.selected.filter((n) => !drop.includes(n)));
                    }}
                  >
                    {t('aiContentSec.routeScope.clearAll', '全取消')}
                  </Typography.Link>
                </Space>
              </div>
              <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, maxHeight: 340, overflowY: 'auto' }}>
                {visible.length ? visible.map((r) => {
                  const checked = scope.selected.includes(r.name);
                  const applied = scope.bound.includes(r.name);
                  return (
                    <div
                      key={r.name}
                      style={{
                        display: 'flex', alignItems: 'flex-start', gap: 10,
                        padding: '10px 14px', borderBottom: '1px solid #f5f5f5', background: checked ? '#f6ffed' : undefined,
                      }}
                    >
                      <Checkbox
                        checked={checked}
                        onChange={(e) => toggle(r.name, e.target.checked)}
                        style={{ marginTop: 3 }}
                      />
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                          <span style={{ fontWeight: 500 }}>{r.display || r.name}</span>
                          {r.isAi ? (
                            <Tag color="blue" style={{ marginInlineEnd: 0 }}>{t('aiContentSec.routeScope.aiRoute')}</Tag>
                          ) : null}
                          {applied ? (
                            <Tag color="green" style={{ marginInlineEnd: 0 }}>{t('aiContentSec.routeScope.configured')}</Tag>
                          ) : null}
                        </div>
                        {r.desc ? (
                          <div style={{ color: '#8c8c8c', fontSize: 12, marginTop: 2 }}>{r.desc}</div>
                        ) : null}
                      </div>
                    </div>
                  );
                }) : (
                  <div style={{ padding: 24, textAlign: 'center', color: '#8c8c8c' }}>
                    {t('aiContentSec.routeScope.noMatch', '没有匹配的路由')}
                  </div>
                )}
              </div>
              {dirty ? (
                <div
                  style={{
                    marginTop: 10, padding: '8px 12px', fontSize: 12, color: '#874d00',
                    background: '#fffbe6', border: '1px solid #ffe58f', borderRadius: 6,
                  }}
                >
                  {t('aiContentSec.routeScope.pendingChanges', {
                    defaultValue: '待新增 {{add}} 条、待移除 {{remove}} 条，点击“保存作用范围”后生效',
                    add: toAdd.length,
                    remove: toRemove.length,
                  })}
                </div>
              ) : null}
            </>
          ) : (
            <Typography.Text type="secondary">{t('aiContentSec.routeScope.empty')}</Typography.Text>
          )}
          <Divider style={{ margin: '16px 0 12px' }} />
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('aiContentSec.routeScope.hint')}
            </Typography.Text>
            <Button
              type="primary"
              loading={scope.saving}
              disabled={!dirty}
              onClick={() => {
                if (validate && !validate()) return;
                scope.applyScope(getRawConfig());
              }}
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
