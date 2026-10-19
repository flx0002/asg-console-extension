#!/bin/bash
# patch-topbar.sh：post-brand 顶栏改造（在 brand-apply.sh 之后执行，幂等）
# 作用：右上角隐藏 Navbar（官网/文档/企业版/开发者/博客/下载/Github），改为「保存」入口跳转 /config-versions
# 用法: bash patch-topbar.sh [console 仓库目录]
set -euo pipefail
CON=${1:-/home/wnt/ASG/AISecGw-console}
L=$CON/frontend/src/pages/layout.tsx
python3 - "$L" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
if 'asgTopbar.saveSnapshot' in s:
    print('  SKIP: topbar already patched')
    sys.exit(0)

changed = False

# 1) antd import 增加 Button
old_imp = "import { ConfigProvider, Result } from 'antd';"
new_imp = "import { Button, ConfigProvider, Result } from 'antd';"
if old_imp in s:
    s = s.replace(old_imp, new_imp, 1)
    changed = True
    print('  [ok] add Button import')

# 2) 移除 Navbar import（顶栏整体隐藏后不再引用）
nav_imp = "import Navbar from '@/components/Navbar';\n"
if nav_imp in s:
    s = s.replace(nav_imp, '', 1)
    changed = True
    print('  [ok] remove Navbar import')

# 3) rightContentRender 内 <Navbar /> → 「保存」入口(Link 到 /config-versions)
old_block = (
    "          <div style={{ display: 'flex', alignItems: 'center' }}>\n"
    "            <Navbar />\n"
)
new_block = (
    "          <div style={{ display: 'flex', alignItems: 'center' }}>\n"
    "            <Link to=\"/config-versions\"><Button size=\"small\" type=\"primary\">{t('asgTopbar.saveSnapshot', '保存')}</Button></Link>\n"
)
if old_block in s:
    s = s.replace(old_block, new_block, 1)
    changed = True
    print('  [ok] replace Navbar with save entry')
else:
    raise SystemExit('!! rightContentRender Navbar block not found — layout.tsx structure changed?')

if changed:
    open(p, 'w', encoding='utf-8').write(s)
    print('  topbar patched')
PYEOF
echo "===== patch-topbar.sh DONE ====="
