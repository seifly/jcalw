#!/bin/bash

# QQ Gateway 启动脚本

# 自动定位到脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "========================================"
echo "     QQ Gateway for jclaw v1.0.0"
echo "========================================"
echo ""

# 检查 Java
if ! command -v java &> /dev/null; then
    echo "❌ 错误: 未找到 Java，请先安装 JDK 17+"
    exit 1
fi

# 检查 jclaw 配置文件
JCLAW_CONFIG="$HOME/.jclaw/config.json"
if [ ! -f "$JCLAW_CONFIG" ]; then
    echo "❌ 错误: 未找到 jclaw 配置文件: $JCLAW_CONFIG"
    echo ""
    echo "请先启动 jclaw 以生成配置文件："
    echo "  java -jar jclaw-0.1.0.jar"
    echo ""
    echo "或者手动创建配置文件："
    echo '{'
    echo '  "channels": {'
    echo '    "qq": {'
    echo '      "enabled": true,'
    echo '      "appId": "your_app_id",'
    echo '      "appSecret": "your_app_secret"'
    echo '    }'
    echo '  }'
    echo '}'
    exit 1
fi

# 检查 QQ 配置是否存在
QQ_CONFIG=$(python3 -c "
import json, sys
try:
    with open('$JCLAW_CONFIG') as f:
        config = json.load(f)
    qq = config.get('channels', {}).get('qq', {})
    if qq.get('appId') and qq.get('appSecret'):
        print('ok')
    else:
        print('missing')
except:
    print('error')
" 2>/dev/null)

if [ "$QQ_CONFIG" = "missing" ]; then
    echo "⚠️  警告: jclaw 配置中缺少 QQ 通道配置"
    echo "   请在 $JCLAW_CONFIG 中添加："
    echo '{'
    echo '  "channels": {'
    echo '    "qq": {'
    echo '      "enabled": true,'
    echo '      "appId": "your_app_id",'
    echo '      "appSecret": "your_app_secret"'
    echo '    }'
    echo '  }'
    echo '}'
    echo ""
    read -p "是否继续? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
elif [ "$QQ_CONFIG" = "error" ]; then
    echo "⚠️  警告: 无法解析 jclaw 配置文件"
    echo ""
    read -p "是否继续? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# 构建（如需要）
if [ ! -f target/qq-gateway-1.0.0.jar ]; then
    echo "📦 正在构建项目..."
    mvn clean package -DskipTests -q
    if [ $? -ne 0 ]; then
        echo "❌ 构建失败"
        exit 1
    fi
    echo "✓ 构建成功"
    echo ""
fi

# 运行
echo "🚀 正在启动 QQ Gateway..."
echo ""
java -jar target/qq-gateway-1.0.0.jar
