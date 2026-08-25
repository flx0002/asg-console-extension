#!/bin/bash
# brand-apply.sh：ASG 品牌重放（inject.sh 之后执行，幂等可重复执行）
# 用法: bash brand-apply.sh [console 仓库目录]
# 依赖：python3 + node（merge locales 用）
set -euo pipefail
CON=${1:-/home/wnt/ASG/AISecGw-console}
EXT_DIR=$(cd "$(dirname "$0")" && pwd)

cd "$CON"

echo "=== 1. 品牌资源复制（logo 等）==="
cp -r "$EXT_DIR/brand/assets/public/"* frontend/public/
mkdir -p frontend/src/assets
cp -r "$EXT_DIR/brand/assets/src/assets/"* frontend/src/assets/
echo "  ✓ 资源 $(find "$EXT_DIR/brand/assets" -type f | wc -l) 个"

echo
echo "=== 2. 品牌补丁应用（20 个品牌修改文件）==="
if git apply --check "$EXT_DIR/brand/patches/brand.patch" 2>/dev/null; then
  git apply "$EXT_DIR/brand/patches/brand.patch"
  echo "  ✓ brand.patch applied"
elif git apply --reverse --check "$EXT_DIR/brand/patches/brand.patch" 2>/dev/null; then
  echo "  SKIP: already applied"
else
  echo "  !! brand.patch cannot apply cleanly" >&2
  exit 1
fi

echo
echo "=== 3. 品牌 locales 值替换（15 key/语言）==="
node - frontend/src/locales/zh-CN/translation.json "$EXT_DIR/brand/locales/zh-CN.json" <<'PYEOF'
const fs = require('fs');
const [target, brandPath] = process.argv.slice(2);
const cur = JSON.parse(fs.readFileSync(target, 'utf8'));
const brand = JSON.parse(fs.readFileSync(brandPath, 'utf8'));
function merge(a, b) {
  for (const k of Object.keys(b)) {
    if (b[k] && typeof b[k] === 'object' && a[k] && typeof a[k] === 'object') merge(a[k], b[k]);
    else a[k] = b[k];
  }
}
merge(cur, brand);
fs.writeFileSync(target, JSON.stringify(cur, null, 2) + '\n');
console.log('  ✓ zh-CN brand keys merged');
PYEOF
node - frontend/src/locales/en-US/translation.json "$EXT_DIR/brand/locales/en-US.json" <<'PYEOF'
const fs = require('fs');
const [target, brandPath] = process.argv.slice(2);
const cur = JSON.parse(fs.readFileSync(target, 'utf8'));
const brand = JSON.parse(fs.readFileSync(brandPath, 'utf8'));
function merge(a, b) {
  for (const k of Object.keys(b)) {
    if (b[k] && typeof b[k] === 'object' && a[k] && typeof a[k] === 'object') merge(a[k], b[k]);
    else a[k] = b[k];
  }
}
merge(cur, brand);
fs.writeFileSync(target, JSON.stringify(cur, null, 2) + '\n');
console.log('  ✓ en-US brand keys merged');
PYEOF

echo
echo "=== 4. package.json description 品牌化 ==="
node - frontend/package.json <<'PYEOF'
const fs = require('fs');
const p = process.argv[2];
const j = JSON.parse(fs.readFileSync(p, 'utf8'));
if (j.description !== 'WntASG Console') {
  j.description = 'WntASG Console';
  fs.writeFileSync(p, JSON.stringify(j, null, 2) + '\n');
  console.log('  ✓ description = WntASG Console');
} else {
  console.log('  SKIP: already branded');
}
PYEOF

echo
echo "=== 5. 品牌应用后统计 ==="
cd "$CON"
git status --porcelain -- frontend/ | wc -l
echo "===== brand-apply.sh DONE ====="
