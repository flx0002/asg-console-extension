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
bash "$EXT_DIR/brand-apply.sh" "$CON" | tail -3

echo
echo "=== [3/5] 品牌标记校验 ==="
test -f "$CON/frontend/public/titleLogo.png" || { echo "!! 缺少品牌 logo（titleLogo.png），品牌重放未生效"; exit 1; }
grep -q "WntASG" "$CON/frontend/src/components/Footer/index.tsx" || { echo "!! Footer 无品牌名，brand.patch 未生效"; exit 1; }
grep -q "getShadowAiDetectEvents" "$CON/frontend/src/services/shadow-ai.ts" || { echo "!! 功能注入不完整（缺 shadow-ai services）"; exit 1; }
echo "  ✓ logo / Footer 品牌名 / ASG 功能 services 全部就位"

echo
echo "=== [4/5] npm run build（约 13 分钟）==="
cd "$CON/frontend"
npm run build

echo
echo "=== [5/5] 镜像构建与部署 ==="
if [ "$MODE" = "deploy" ]; then
    bash /home/wnt/ASG/asg-deploy/scripts/build-and-deploy.sh deploy
    # 镜像 tag（latest）不变时 helm upgrade 不重建 pod，必须手动滚动重启
    kubectl rollout restart deployment/higress-console -n higress-system
    kubectl rollout status deployment/higress-console -n higress-system --timeout=300s
    echo "=== ASG BUILD+DEPLOY DONE ==="
else
    echo "=== ASG BUILD-ONLY DONE（跳过部署）==="
fi
