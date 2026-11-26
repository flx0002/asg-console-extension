#!/bin/bash
# inject.sh：ASG 前端扩展注入（构建时执行，console 源码零侵入，幂等可重复执行）
# 用法: bash inject.sh [console 仓库目录]
# 依赖：python3 + node（merge locales 用）
set -euo pipefail
CON=${1:-/home/wnt/ASG/AISecGw-console}
EXT_DIR=$(cd "$(dirname "$0")" && pwd)
SRC=$CON/frontend/src

echo "=== 1. 扩展页面/services/interfaces/theme 复制 ==="
# ai-shadow 目录整体以扩展仓为准：先删后拷。
# cp -r 只覆盖、不删除；页面一旦改名或移走（例如两个页签视图从 pages/ 移到 components/），
# console 仓里的旧文件会残留下来，umi 仍会把它注册成路由——表现为「旧 URL 还能打开一份没有页头的页面」，
# 而这份页面和配置页里的页签是同一份组件，用户会看到两个入口。
rm -rf "$SRC/pages/ai-shadow"
cp -r "$EXT_DIR/pages/"* "$SRC/pages/"
cp "$EXT_DIR/services/"*.ts "$SRC/services/"
mkdir -p "$SRC/components"
cp "$EXT_DIR/components/"*.tsx "$SRC/components/"
cp -r "$EXT_DIR/interfaces" "$SRC/"
cp "$EXT_DIR/theme.ts" "$SRC/theme.ts"
echo "  ✓ 页面 $(find "$EXT_DIR/pages" -name '*.tsx' | wc -l) 个 / services $(ls "$EXT_DIR/services" | wc -l) 个 / interfaces / theme"

echo
echo
echo "=== 1b. 清理历史注入的旧命名残留（shadow-ai → ai-shadow 改名遗留）==="
# inject.sh 为追加式：只复制新文件、只追加菜单，不会移除历史注入内容。
# 改名后若不清理，控制台会同时存在「影子AI」新旧两组菜单，且旧页面仍请求
# 已下线的 /v1/shadow-ai/* 接口 → 404（功能失效）。
rm -rf "$SRC/pages/shadow-ai" "$SRC/interfaces/shadow-ai.ts" "$SRC/services/shadow-ai.ts"
echo "  [ok] 清理旧复制产物: pages/shadow-ai, interfaces/shadow-ai.ts, services/shadow-ai.ts"

# 旧菜单顶级块 menu.shadowAiManagement（含 /shadow-ai/* 子路由）
python3 - "$SRC/pages/_defaultProps.tsx" <<'PYEOF'
import sys
p = sys.argv[1]
lines = open(p, encoding='utf-8').read().split('\n')
start = None
for i, l in enumerate(lines):
    if "name: 'menu.shadowAiManagement'," in l:
        start = i
        break
if start is None:
    print('  SKIP: no legacy shadowAiManagement menu block')
else:
    k = start
    while k > 0 and lines[k].strip() != '{':
        k -= 1
    j = start
    while j < len(lines) and lines[j] != '      },':
        j += 1
    del lines[k:j + 1]
    open(p, 'w', encoding='utf-8').write('\n'.join(lines))
    print('  [ok] removed legacy menu.shadowAiManagement top-level block')
PYEOF

# services/index.ts 中遗留的旧导出
python3 - "$SRC/services/index.ts" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = "export * from './shadow-ai';\n"
if old in s:
    s = s.replace(old, '')
    open(p, 'w', encoding='utf-8').write(s)
    print("  [ok] removed legacy export './shadow-ai'")
else:
    print("  SKIP: no legacy './shadow-ai' export")
PYEOF

echo "=== 2. 菜单注入（_defaultProps.tsx：git checkout HEAD 复位后重新注入，杜绝重复块）==="
# 根因：_defaultProps.tsx 是 git 跟踪文件，因历史 commit 导致 HEAD 本身已污染重复 ASG 块；
# 就地追加式注入一旦工作树漂移就会累积重复且无法自愈。
# 修复：改用 EXT 仓库自有的纯净基线（_defaultProps.baseline.tsx，来自 fork 历史 f6e2e59 “restore to upstream”，
# 0 个 ASG 块，包含所有 native 锚点），每次注入前 cp 覆盖源文件，完全不依赖 console git 状态。
# ⚠ 该基线是菜单注入的唯一真相源：若上游 console 合并新增/调整了原生路由项（新页面入口、hideFromMenu、
#   visiblePredicate 等），必须同步刷新 _defaultProps.baseline.tsx，否则这些原生项会在构建时被静默回滚。
if [ ! -f "$EXT_DIR/_defaultProps.baseline.tsx" ]; then
  echo "  FATAL: 缺少纯净基线 $EXT_DIR/_defaultProps.baseline.tsx（应随 EXT 仓库一起提交），无法安全注入" >&2
  exit 1
fi
git -C "$CON" checkout HEAD -- frontend/src/pages/_defaultProps.tsx 2>/dev/null || true
cp "$EXT_DIR/_defaultProps.baseline.tsx" "$SRC/pages/_defaultProps.tsx"
python3 - "$SRC/pages/_defaultProps.tsx" "$EXT_DIR/menu.config.ts" <<'PYEOF'
import sys, re

props_path, menu_path = sys.argv[1], sys.argv[2]
s = open(props_path, encoding='utf-8').read()
orig = s

# 复位后为纯净 HEAD，无条件注入（复位本身即幂等保证）。
if True:
    # A. icon import 重组（字母序，追加缺失的 4 个）
    m = re.search(r"import \{\n(.*?)\} from '@ant-design/icons';", s, re.S)
    assert m, 'icons import block not found'
    existing = set(re.findall(r'\b(\w+Outlined)\b', m.group(1)))
    needed = {'AlertOutlined', 'AuditOutlined', 'BookOutlined', 'EyeOutlined', 'FileSearchOutlined', 'RadarChartOutlined', 'SecurityScanOutlined'}
    icons = sorted(existing | needed)
    new_block = 'import {\n' + ''.join(f'  {i},\n' for i in icons) + "} from '@ant-design/icons';"
    s = s[:m.start()] + new_block + s[m.end():]

    # B. 读取 menu.config.ts 的 asgMenuRoutes 数组体 → 5 个 ASG 块
    mc = open(menu_path, encoding='utf-8').read()
    arr = mc[mc.index('export const asgMenuRoutes: any[] = ['):]
    arr = arr[arr.index('['):arr.index('];') + 1]
    # 分割顶级对象（2 空格缩进 { ... },）
    objs = re.findall(r'\n  \{\n.*?\n  \},', arr, re.S)
    assert len(objs) == 5, f'expected 5 menu blocks, got {len(objs)}'
    asg_blocks = [o[1:] for o in objs]  # 去掉行首 \n，每行已是 2 空格缩进

    # C. 顶级块定位（6 空格缩进 { ... },），取块名
    lines = s.split('\n')
    blocks = []  # (start_idx, end_idx, name)
    i = 0
    while i < len(lines):
        if lines[i] == '      {':
            name = None
            j = i + 1
            while j < len(lines) and lines[j] != '      },':
                mm = re.search(r"name: '(menu\.[^']+)'", lines[j])
                if mm and name is None:
                    name = mm.group(1)
                j += 1
            if name:
                blocks.append((i, j, name))
            i = j + 1
        else:
            i += 1
    by_name = {b[2]: b for b in blocks}
    need = ['menu.serviceSources', 'menu.serviceList', 'menu.routeConfig', 'menu.pluginManagement']
    for n in need:
        assert n in by_name, f'menu {n} not found'
    # 其他块（保持相对顺序）
    others = [b for b in blocks if b[2] not in need]

    # D. 重组：dashboard → aiServiceManagement → [5 ASG] → 3 服务 → plugin → others
    block_text = lambda b: '\n'.join(lines[b[0]:b[1] + 1])
    # 找到 aiServiceManagement 块结束行（ASG 块插入点：其后）
    anchor_end = by_name['menu.aiServiceManagement'][1]
    # 待移动块原文（6 空格缩进，直接可用）
    moved = [block_text(by_name[n]) for n in need]
    asg_text = ['\n'.join('  ' + l if l.strip() else l for l in b.split('\n')) for b in asg_blocks]
    # 2 空格 → 6 空格：每行 +4 空格
    def indent4(t):
        return '\n'.join(('    ' + l) if l.strip() else l for l in t.split('\n'))
    asg_text = [indent4(b) for b in asg_blocks]

    new_lines = []
    consumed = set()
    for idx, line in enumerate(lines):
        if idx == anchor_end:
            new_lines.append(line)
            for t in asg_text + moved:
                new_lines.extend(t.split('\n'))
            continue
        skip = False
        for b in blocks:
            if b[2] in need and b[0] <= idx <= b[1]:
                skip = True
                break
        if not skip:
            new_lines.append(line)
    s = '\n'.join(new_lines)
    open(props_path, 'w', encoding='utf-8').write(s)
    print('  ✓ menu injected & reordered')
PYEOF

echo
echo "=== 2b. 配置备份与恢复 → 系统配置(menu.systemSettings) 子菜单（幂等，含历史迁移）==="
python3 - "$SRC/pages/_defaultProps.tsx" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
lines = s.split('\n')
changed = False

# a0. 升级历史注入的 children（仅含 configVersionCenter）→ 加入系统设置页面子项（幂等）
OLD_CHILDREN = """        children: [
          {
            name: 'menu.configVersionCenter',
            path: '/config-versions',
          },
        ],"""
NEW_CHILDREN = """        children: [
          {
            name: 'menu.systemSettings',
            path: '/system',
          },
          {
            name: 'menu.licenseManagement',
            path: '/license',
          },
          {
            name: 'menu.kbOnlineSetting',
            path: '/kb-online-setting',
          },
          {
            name: 'menu.configVersionCenter',
            path: '/config-versions',
          },
        ],"""
if OLD_CHILDREN in s:
    s = s.replace(OLD_CHILDREN, NEW_CHILDREN, 1)
    lines = s.split('\n')
    changed = True
    print('  ✓ system settings page restored as first child under systemSettings')

# a. 移除历史版本注入的 menu.configVersion 顶级块（若存在）
start = None
for i, l in enumerate(lines):
    if "name: 'menu.configVersion'," in l:
        start = i
        break
if start is not None:
    k = start
    while k > 0 and lines[k].strip() != '{':
        k -= 1
    j = start
    while j < len(lines) and lines[j] != '      },':
        j += 1
    del lines[k:j + 1]
    s = '\n'.join(lines)
    changed = True
    print('  ✓ removed legacy menu.configVersion top-level block')
else:
    print('  SKIP: no legacy configVersion top-level block')

# b. systemSettings 叶子项 → 加 children（幂等；首个子项保留原系统设置页面入口，
#    ProLayout 父项带 children 后仅展开不跳转，必须显式保留 /system 子项；注意 2c2 默认隐藏该子项，届时 /system 仅 URL 直达，恢复入口=从 2c2 清单移除）
LEAF = """      {
        name: 'menu.systemSettings',
        path: '/system',
        icon: <SettingOutlined />,
      },"""
PARENT = """      {
        name: 'menu.systemSettings',
        path: '/system',
        icon: <SettingOutlined />,
        children: [
          {
            name: 'menu.systemSettings',
            path: '/system',
          },
          {
            name: 'menu.licenseManagement',
            path: '/license',
          },
          {
            name: 'menu.kbOnlineSetting',
            path: '/kb-online-setting',
          },
          {
            name: 'menu.configVersionCenter',
            path: '/config-versions',
          },
        ],
      },"""
if 'menu.configVersionCenter' in s:
    print('  SKIP: systemSettings children already injected')
elif LEAF in s:
    s = s.replace(LEAF, PARENT, 1)
    changed = True
    print('  ✓ config backup submenu added under systemSettings')
else:
    raise SystemExit('!! systemSettings leaf block not found — fork menu structure changed?')

if changed:
    open(p, 'w', encoding='utf-8').write(s)
PYEOF

echo
echo "=== 2c. 菜单裁剪（ASG 定制清单，与 baseline 解耦）==="
# 只隐藏侧栏入口：不删块、不删路由，页面仍可通过 URL 与页面内跳转访问（layout.tsx menuDataRender 过滤）。
# 清单只在此处维护：从 upstream 刷新 _defaultProps.baseline.tsx 后无需重打标；
# 上游新增菜单不必加入本清单；只有列出的菜单被删掉或改名（上游或 menu.config.ts）才需同步更新。
# 匹配 8 空格缩进的顶级块（name/path/icon）；每项都做硬校验，缺失、重复、
# icon 不唯一或字段落点不对一律中止构建，杜绝「静默回滚」。
HIDDEN_MENUS=(
  menu.serviceSources
  menu.serviceList
  menu.routeConfig
  menu.domainManagement
  menu.certManagement
  menu.aiAgentGuard
  menu.behaviorAnalysis
)
python3 - "$SRC/pages/_defaultProps.tsx" "${HIDDEN_MENUS[@]}" <<'PYEOF_CULL'
import sys
p = sys.argv[1]
keys = sys.argv[2:]
def own(seg):
    for x, l in enumerate(seg):
        if l.strip() == 'children: [':
            return seg[:x]
    return seg
assert keys, 'HIDDEN_MENUS is empty'
lines = open(p, encoding='utf-8').read().split('\n')
ins = []
for key in keys:
    needle = "        name: '" + key + "',"
    hits = [i for i, l in enumerate(lines) if l == needle]
    assert len(hits) == 1, 'menu not found or duplicated: ' + key
    i = hits[0]
    j = i + 1
    while j < len(lines) and lines[j] != '      },':
        j += 1
    assert j < len(lines), 'block end not found: ' + key
    block = lines[i:j]
    if any('hideFromMenu: true' in l for l in own(block)):
        print('  SKIP already hidden:', key)
        continue
    icon_lines = [x for x, l in enumerate(block) if l.startswith('        icon:')]
    assert len(icon_lines) == 1, 'icon line not found or not unique: ' + key
    ins.append((i + icon_lines[0] + 1, '        hideFromMenu: true,'))
for pos, text in sorted(ins, reverse=True):
    lines.insert(pos, text)
out = '\n'.join(lines)
check = out.split('\n')
for key in keys:
    i = check.index("        name: '" + key + "',")
    j = i + 1
    while j < len(check) and check[j] != '      },':
        j += 1
    assert j < len(check), 'block end not found in verify: ' + key
    assert any('hideFromMenu: true' in l for l in own(check[i:j])), 'hide not applied in block: ' + key
orphans = [n + 1 for n, l in enumerate(check)
           if l.strip() == 'hideFromMenu: true,' and n > 0 and check[n-1].strip() == '},']
assert not orphans, 'orphan hideFromMenu lines: ' + str(orphans)
open(p, 'w', encoding='utf-8').write(out)
print('  OK hidden %d menus: %s' % (len(keys), ', '.join(keys)))
PYEOF_CULL
echo
echo "=== 2c2. 子菜单裁剪（children 项，12 空格缩进；与顶级块同名 key 靠缩进区分）==="
# 只隐藏侧栏子项入口；父菜单与路由保留（layout.tsx menuDataRender 递归过滤 hideFromMenu）。
# 隐藏 menu.systemSettings 子项后侧栏将无 /system 入口（父项带 children 仅展开不跳转），
# URL 直达保留，属有意下架；恢复入口=从本清单移除该项。
# 注意：menu.systemSettings 父子同名同 key，本清单按 12 空格 name 行精确匹配，只会命中子项。
# 上游/menu.config.ts 删掉或改名这里列出的子菜单才需同步更新；缺失、重复、icon 不唯一或
# 字段落点不对一律中止构建，杜绝「静默回滚」。
HIDDEN_SUBMENUS=(
  menu.systemSettings
  menu.logsAuditChainLogs
  menu.logsAuditChainTracking
  menu.aiSecurityGuard
)
python3 - "$SRC/pages/_defaultProps.tsx" "${HIDDEN_SUBMENUS[@]}" <<'PYEOF_CULL_SUB'
import sys
p = sys.argv[1]
keys = sys.argv[2:]
def own(seg):
    for x, l in enumerate(seg):
        if l.strip() == 'children: [':
            return seg[:x]
    return seg
assert keys, 'HIDDEN_SUBMENUS is empty'
NAME = '            '
END = '          },'
lines = open(p, encoding='utf-8').read().split('\n')
ins = []
for key in keys:
    needle = NAME + "name: '" + key + "',"
    hits = [i for i, l in enumerate(lines) if l == needle]
    assert len(hits) == 1, 'submenu not found or duplicated: ' + key
    i = hits[0]
    j = i + 1
    while j < len(lines) and lines[j] != END:
        j += 1
    assert j < len(lines), 'submenu block end not found: ' + key
    block = lines[i:j]
    if any('hideFromMenu: true' in l for l in own(block)):
        print('  SKIP already hidden:', key)
        continue
    icon_lines = [x for x, l in enumerate(block) if l.startswith(NAME + 'icon:')]
    assert len(icon_lines) <= 1, 'submenu icon not unique: ' + key
    at = (i + icon_lines[0] + 1) if icon_lines else (i + 1)
    ins.append((at, NAME + 'hideFromMenu: true,'))
for pos, text in sorted(ins, reverse=True):
    lines.insert(pos, text)
out = '\n'.join(lines)
check = out.split('\n')
for key in keys:
    i = check.index(NAME + "name: '" + key + "',")
    j = i + 1
    while j < len(check) and check[j] != END:
        j += 1
    assert j < len(check), 'submenu block end not found in verify: ' + key
    assert any('hideFromMenu: true' in l for l in own(check[i:j])), 'submenu hide not applied: ' + key
orphans = [n + 1 for n, l in enumerate(check)
           if l.strip() == 'hideFromMenu: true,' and n > 0 and check[n-1].strip() == '},']
assert not orphans, 'orphan hideFromMenu lines: ' + str(orphans)
open(p, 'w', encoding='utf-8').write(out)
print('  OK hidden %d submenus: %s' % (len(keys), ', '.join(keys)))
PYEOF_CULL_SUB
echo

echo "=== 2d. AI路由管理页使用说明（route.tsx 注入说明条 + aiRoute.usageHint* i18n，幂等）==="
# route.tsx 是 2 号仓上游页面，按 patch-topbar/brand.patch 既有机制由本脚本构建期注入说明条，
# 改动源留在 3 号仓；i18n 键同 4b 约定直接写 fork translation.json（EXT locales 勿定义同名键）。
# 锚点失配即中止构建，杜绝「静默失效」。
for LOC in zh-CN en-US; do
  node - "$SRC/locales/$LOC/translation.json" "$LOC" <<'PYEOF_HINT_I18N'
const fs = require('fs');
const [target, locale] = process.argv.slice(2);
const cur = JSON.parse(fs.readFileSync(target, 'utf8'));
if (typeof cur.aiRoute !== 'object' || cur.aiRoute === null) cur.aiRoute = {};
const add = locale === 'zh-CN'
  ? {
      usageHintTitle: '使用说明',
      usageHint: 'AI 路由把发送到网关的 AI 请求按域名、路径或模型转发到后端 AI 服务。使用步骤：1）在「AI网关管理 › AI服务提供者管理」添加 AI 服务提供者；2）点击「创建AI路由」，填写名称、目标 AI 服务与匹配规则；3）需要访问控制时启用「请求认证」并绑定消费者；4）创建完成后点击列表中的「使用方法」查看调用命令。',
    }
  : {
      usageHintTitle: 'How to use',
      usageHint: 'AI routes forward AI requests hitting the gateway to backend AI services by domain, path or model. Steps: 1) Add an LLM provider under AI Gateway Config > LLM Provider Management; 2) Click "Create AI Route" and fill in the name, target AI service and match rules; 3) Enable request authentication and bind consumers for access control; 4) Click "Usage" in the list to view a sample request command.',
    };
let changed = false;
for (const [k, v] of Object.entries(add)) {
  if (cur.aiRoute[k] !== v) { cur.aiRoute[k] = v; changed = true; }
}
if (changed) {
  fs.writeFileSync(target, JSON.stringify(cur, null, 2) + '\n');
  console.log('  aiRoute.usageHint* set (' + locale + ')');
} else {
  console.log('  SKIP i18n already present (' + locale + ')');
}
PYEOF_HINT_I18N
done
python3 - "$SRC/pages/ai/route.tsx" <<'PYEOF_HINT_PATCH'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
if 'aiRoute.usageHintTitle' in s:
    print('  SKIP route.tsx already patched')
    sys.exit(0)
old_import = "import { Button, Col, Drawer, Form, FormProps, Input, message, Modal, Row, Select, Space, Table } from 'antd';"
new_import = "import { Alert, Button, Col, Drawer, Form, FormProps, Input, message, Modal, Row, Select, Space, Table } from 'antd';"
assert s.count(old_import) == 1, 'antd import line not found or not unique'
s = s.replace(old_import, new_import, 1)
anchor = "    <PageContainer>\n      <Form"
assert s.count(anchor) == 1, 'PageContainer>Form anchor not found or not unique'
hint = "    <PageContainer>\n      <Alert\n        type=\"info\"\n        showIcon\n        style={{ marginBottom: 16 }}\n        message={t('aiRoute.usageHintTitle')}\n        description={t('aiRoute.usageHint')}\n      />\n      <Form"
s = s.replace(anchor, hint, 1)
assert 'Alert, Button, Col, Drawer' in s, 'Alert import not applied'
assert s.count('<Alert') == 1, 'Alert block not applied'
assert s.count("t('aiRoute.usageHintTitle')") == 1, 'hint title ref wrong'
assert s.count("t('aiRoute.usageHint')") == 1, 'hint desc ref wrong'
open(p, 'w', encoding='utf-8').write(s)
print('  OK route.tsx patched: Alert usage hint injected')
PYEOF_HINT_PATCH
echo

echo "=== 2e. 监控菜单指向自建中文页（menu.dashboard / menu.aiDashboard，幂等）==="
# 上游 /dashboard 与 /ai/dashboard 是 Grafana iframe 页：英文 + Higress 品牌，
# 且模板每次启动会被 2 号仓按 title 覆盖，无法在不改禁改仓的前提下汉化。
# 改为指向 3 号仓自建中文页；path 是菜单匹配主键，锚点失配即中止构建。
python3 - "$SRC/pages/_defaultProps.tsx" <<'PYEOF_DASH_REDIR'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
pairs = [
    ("        name: 'menu.dashboard',\n        path: '/dashboard',",
     "        name: 'menu.dashboard',\n        path: '/ai-dashboard',"),
    ("            name: 'menu.aiDashboard',\n            path: '/ai/dashboard',",
     "            name: 'menu.aiDashboard',\n            path: '/ai-dashboard/detail',"),
]
changed = 0
for old, new in pairs:
    cnt = s.count(old)
    if cnt == 0:
        if new in s:
            continue
        raise SystemExit('FATAL: menu path anchor not found: ' + old[:60].replace(chr(10), '|'))
    assert cnt == 1, 'menu path anchor not unique: ' + old[:60].replace(chr(10), '|')
    s = s.replace(old, new, 1)
    changed += 1
assert "path: '/ai-dashboard'," in s, 'main dashboard path not set'
assert "path: '/ai-dashboard/detail'," in s, 'ai dashboard path not set'
open(p, 'w', encoding='utf-8').write(s)
print('  OK redirected %d dashboard menus to self-built pages' % changed)
PYEOF_DASH_REDIR
echo

echo "=== 2f. 隐藏 AI 监控面板菜单项（menu.aiDashboard，幂等）==="
# 背景：监控面板已升级为全量（含 Token 指标），两页内容重复，AI 面板菜单暂隐藏。
# 只隐藏侧栏入口，不删块、不删路由：/ai-dashboard/detail 仍可通过 URL 直达。
# ⚠ 必须放在 2e 之后：2e 的 path 重定向锚点要求 name/path 连续两行；
#   若先插入 hideFromMenu 会让锚点失配、构建中止。恢复入口 = 从本段移除（或改回不插入）。
python3 - "$SRC/pages/_defaultProps.tsx" <<'PYEOF_HIDE_AI_DASH'
import sys
p = sys.argv[1]
NAME = '            '
END = '          },'
needle = NAME + "name: 'menu.aiDashboard',"
lines = open(p, encoding='utf-8').read().split('\n')
hits = [i for i, l in enumerate(lines) if l == needle]
assert len(hits) == 1, 'aiDashboard submenu not found or duplicated: ' + str(hits)
i = hits[0]
j = i + 1
while j < len(lines) and lines[j] != END:
    j += 1
assert j < len(lines), 'aiDashboard submenu block end not found'
block = lines[i:j]
if any('hideFromMenu: true' in l for l in block):
    print('  SKIP aiDashboard already hidden')
    sys.exit(0)
icons = [x for x, l in enumerate(block) if l.startswith(NAME + 'icon:')]
assert len(icons) <= 1, 'aiDashboard icon not unique'
at = (i + icons[0] + 1) if icons else (i + 1)
lines.insert(at, NAME + 'hideFromMenu: true,')
out = '\n'.join(lines)
check = out.split('\n')
ci = check.index(needle)
cj = ci + 1
while cj < len(check) and check[cj] != END:
    cj += 1
assert cj < len(check), 'verify: aiDashboard block end not found'
assert any('hideFromMenu: true' in l for l in check[ci:cj]), 'verify: hide not applied'
orphans = [n + 1 for n, l in enumerate(check)
           if l.strip() == 'hideFromMenu: true,' and n > 0 and check[n-1].strip() == '},']
assert not orphans, 'orphan hideFromMenu lines: ' + str(orphans)
open(p, 'w', encoding='utf-8').write(out)
print('  OK hidden submenu: menu.aiDashboard')
PYEOF_HIDE_AI_DASH
echo
echo "=== 3. services/index.ts 追加 export（幂等）==="
python3 - "$SRC/services/index.ts" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
exports = [
    "export * from './ai-shadow';",
    "export * from './ai-dashboard';",
      "export * from './ai-kb';",
    "export * from './agent-guard';",
    "export * from './audit-chain-service';",
    "export * from './behavior-analysis';",
    "export * from './config-version';",
]
missing = [e for e in exports if e not in s]
if missing:
    if not s.endswith('\n'):
        s += '\n'
    s += '\n'.join(missing) + '\n'
    open(p, 'w', encoding='utf-8').write(s)
    print('  ✓ appended:', len(missing))
else:
    print('  SKIP: exports already present')
PYEOF

echo
echo "=== 4. locales 扩展 key 合并（幂等）==="
node - "$SRC/locales/zh-CN/translation.json" "$EXT_DIR/locales/zh-CN.json" <<'PYEOF'
const fs = require('fs');
const [target, extPath] = process.argv.slice(2);
const cur = JSON.parse(fs.readFileSync(target, 'utf8'));
const ext = JSON.parse(fs.readFileSync(extPath, 'utf8'));
function merge(a, b) {
  for (const k of Object.keys(b)) {
    if (b[k] && typeof b[k] === 'object' && a[k] && typeof a[k] === 'object') merge(a[k], b[k]);
    else a[k] = b[k];
  }
}
merge(cur, ext);
fs.writeFileSync(target, JSON.stringify(cur, null, 2) + '\n');
console.log('  ✓ zh-CN merged');
PYEOF
node - "$SRC/locales/en-US/translation.json" "$EXT_DIR/locales/en-US.json" <<'PYEOF'
const fs = require('fs');
const [target, extPath] = process.argv.slice(2);
const cur = JSON.parse(fs.readFileSync(target, 'utf8'));
const ext = JSON.parse(fs.readFileSync(extPath, 'utf8'));
function merge(a, b) {
  for (const k of Object.keys(b)) {
    if (b[k] && typeof b[k] === 'object' && a[k] && typeof a[k] === 'object') merge(a[k], b[k]);
    else a[k] = b[k];
  }
}
merge(cur, ext);
fs.writeFileSync(target, JSON.stringify(cur, null, 2) + '\n');
console.log('  ✓ en-US merged');
PYEOF

echo
echo "=== 4b. 授权管理拆页 i18n 补丁（menu.licenseManagement + license.* + 去 pageHint/激活措辞，幂等）==="
# ai-kb / license 页直接位于 console fork src，其 i18n 键也直接写入 fork translation.json，
# 故用独立幂等补丁脚本处理（EXT locales 采用扁平点号键，与此处的嵌套结构约定不同）。
node "$EXT_DIR/patch-i18n-license.js" "$SRC/locales/zh-CN/translation.json" zh-CN
node "$EXT_DIR/patch-i18n-license.js" "$SRC/locales/en-US/translation.json" en-US

echo
echo "=== 5. package.json 依赖合并（@antv/g6 4.8.7，幂等）==="
node - "$CON/frontend/package.json" <<'PYEOF'
const fs = require('fs');
const p = process.argv[2];
const j = JSON.parse(fs.readFileSync(p, 'utf8'));
if (!j.dependencies['@antv/g6']) {
  j.dependencies['@antv/g6'] = '4.8.7';
  fs.writeFileSync(p, JSON.stringify(j, null, 2) + '\n');
  console.log('  ✓ @antv/g6 added');
} else {
  console.log('  SKIP: @antv/g6 present');
}
PYEOF

echo
echo "=== 5b. 登录页背景图注入（幂等；图片入 src + 相对路径 url）==="
python3 - "$SRC/pages/login/index.module.css" "$SRC/pages/login/login-bg.jpg" "$EXT_DIR/brand/assets/public/login-bg.jpg" <<'PYEOF_LOGIN_BG'
import sys, shutil, os
css_path, dst_img, src_img = sys.argv[1], sys.argv[2], sys.argv[3]
OLD = '/* ASG_LOGIN_BG_PATCH'
NEW = '/* ASG_LOGIN_BG_V2'
s = open(css_path, encoding='utf-8').read()
# 自清理：注入段恒在文件末尾，砍掉历史 V1/V2 段保证幂等
for mark in (OLD, NEW):
    if mark in s:
        s = s[:s.index(mark)].rstrip() + '\n'
assert '.container {' in s, 'login CSS missing .container anchor'
assert os.path.isfile(src_img), 'source image missing: ' + src_img
extra = """
/* ASG_LOGIN_BG_V2 - industrial login background (relative url, injected by inject.sh); do not edit */
.container {
  background:
    linear-gradient(180deg, rgba(6, 10, 28, 0.32) 0%, rgba(6, 10, 28, 0.08) 42%, rgba(6, 10, 28, 0.45) 100%),
    url('./login-bg.jpg') center center / cover no-repeat fixed;
}
"""
s = s + extra
open(css_path, 'w', encoding='utf-8').write(s)
shutil.copyfile(src_img, dst_img)  # webpack 相对路径解析需要图片在 src 同目录
chk = open(css_path, encoding='utf-8').read()
assert chk.count(NEW) == 1, 'verify: marker count abnormal'
assert "url('./login-bg.jpg')" in chk, 'verify: relative url missing'
assert OLD not in chk, 'verify: old V1 block still present'
assert os.path.isfile(dst_img), 'verify: image not copied to src'
print('  OK: login bg V2 injected (relative url, brighter overlay)')
PYEOF_LOGIN_BG

echo "=== 6. 注入结果统计 ==="
cd "$CON"
git status --porcelain -- frontend/ | wc -l
echo "===== inject.sh DONE ====="
