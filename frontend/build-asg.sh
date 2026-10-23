#!/bin/bash
# build-asg.sh：ASG 前端一键构建部署（防止步骤遗漏导致品牌/功能回归）
# 用法: bash build-asg.sh [deploy|build-only]
# 流程: inject.sh（功能注入）→ brand-apply.sh（品牌重放）→ 品牌标记校验 → npm run build → 镜像构建部署 → 滚动重启
# 警告: inject 与 brand-apply 缺一不可——只跑 inject 会还原成 Higress 品牌；只跑 brand-apply 会缺 ASG 功能页面
set -euo pipefail
EXT_DIR=$(cd "$(dirname "$0")" && pwd)
CON=${CONSOLE_DIR:-/home/wnt/ASG/AISecGw-console}
MODE=${1:-deploy}

echo "=== [1/5] inject.sh 功能注入（幂等）==="
bash "$EXT_DIR/inject.sh" "$CON" | tail -3

echo
echo "=== [2/5] brand-apply.sh 品牌重放（幂等）==="
bash "$EXT_DIR/brand-apply.sh" "$CON" > /tmp/brand_apply.log 2>&1 || true
tail -3 /tmp/brand_apply.log

echo
echo "=== [2.5/5] patch-topbar (post-brand topbar) ==="
bash "$EXT_DIR/patch-topbar.sh" "$CON"

echo
echo "=== [3/5] 品牌标记校验 ==="
test -f "$CON/frontend/public/titleLogo.png" || { echo "!! 缺少品牌 logo（titleLogo.png），品牌重放未生效"; exit 1; }
grep -q "WntASG" "$CON/frontend/src/components/Footer/index.tsx" || { echo "!! Footer 无品牌名，brand.patch 未生效"; exit 1; }
grep -q "getAiShadowDetectEvents" "$CON/frontend/src/services/ai-shadow.ts" || { echo "!! 功能注入不完整（缺 ai-shadow services）"; exit 1; }
echo "  ✓ logo / Footer 品牌名 / ASG 功能 services 全部就位"

echo
echo "=== [4/5] npm run build（约 13 分钟）==="
cd "$CON/frontend"
npm run build

echo
echo "=== [5/5] 镜像构建与部署 ==="
if [ "$MODE" = "deploy" ]; then
    # 部署与滚动全部交给 build-and-deploy.sh：它把镜像内容 ID 写进 pod 模板 annotation，
    # 内容变才滚动、没变不动 pod，并打印生效 imageID。此处不要再加 rollout restart，
    # 否则一次部署会滚动两遍（第一遍已由 build-and-deploy.sh 完成）。
    bash /home/wnt/ASG/asg-deploy/scripts/build-and-deploy.sh deploy
    echo "=== ASG BUILD+DEPLOY DONE ==="
else
    echo "=== ASG BUILD-ONLY DONE（跳过部署）==="
fi
