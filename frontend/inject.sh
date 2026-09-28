#!/bin/bash
# inject.sh：ASG 前端扩展注入（构建时执行，console 源码零侵入，幂等可重复执行）
# 用法: bash inject.sh [console 仓库目录]
# 依赖：python3 + node（merge locales 用）
set -euo pipefail
CON=${1:-/home/wnt/ASG/AISecGw-console}
EXT_DIR=$(cd "$(dirname "$0")" && pwd)
SRC=$CON/frontend/src

echo "=== 1. 扩展页面/services/interfaces/theme 复制 ==="
cp -r "$EXT_DIR/pages/"* "$SRC/pages/"
cp "$EXT_DIR/services/"*.ts "$SRC/services/"
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
    needed = {'AuditOutlined', 'EyeOutlined', 'RadarChartOutlined', 'SecurityScanOutlined'}
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
#    ProLayout 父项带 children 后仅展开不跳转，必须显式保留 /system 子项）
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
echo "=== 3. services/index.ts 追加 export（幂等）==="
python3 - "$SRC/services/index.ts" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
exports = [
    "export * from './ai-shadow';",
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
echo "=== 6. 注入结果统计 ==="
cd "$CON"
git status --porcelain -- frontend/ | wc -l
echo "===== inject.sh DONE ====="
