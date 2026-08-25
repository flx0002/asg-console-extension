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
echo "=== 2. 菜单注入（_defaultProps.tsx：5 个 ASG 菜单 + 服务/插件菜单重排）==="
python3 - "$SRC/pages/_defaultProps.tsx" "$EXT_DIR/menu.config.ts" <<'PYEOF'
import sys, re

props_path, menu_path = sys.argv[1], sys.argv[2]
s = open(props_path, encoding='utf-8').read()
orig = s

# ---- 幂等检查：已注入则跳过 ----
if "name: 'menu.shadowAiManagement'" in s:
    print("  SKIP: menu already injected")
else:
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
echo "=== 3. services/index.ts 追加 export（幂等）==="
python3 - "$SRC/services/index.ts" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
exports = [
    "export * from './shadow-ai';",
    "export * from './agent-guard';",
    "export * from './audit-chain-service';",
    "export * from './behavior-analysis';",
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
