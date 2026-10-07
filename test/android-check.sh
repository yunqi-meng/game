#!/usr/bin/env bash
# test/android-check.sh —— 提审前的 APK 自检（方案 docs/android-taptap-plan.md 6.5 / 4.2 那一节）。
#
# 用法：
#   bash test/android-check.sh [APK 路径] [BASE_URL]
#   不给 APK 路径时：优先 release 包，退回 debug 包（debug 包会额外提示"这个不能提审"）。
#   BASE_URL 默认 http://localhost:8080，只为拿 /api/app/version 的 minBuild/latestBuild 比对；
#   后端没在跑会跳过版本比对那一条，不影响其余断言。
#
# 为什么值得单独立一个脚本：APK 是这一轮唯一"发出去就改不了"的产物。签名、包名、targetSdk、
# 图标档位、接口地址这些错法，在浏览器里都会退化成一个看不懂的报错，而在商店驳回邮件里
# 只会有一句"不符合要求"。所以这里全部按"打开包本身"来断言，不看源码。
#
# 依赖：unzip、build-tools 里的 aapt2 / dexdump / apksigner、node（跑 CSS 兜底裁判）、java。
# 缺哪个就跳过靠它的那几条，并明确打"跳过"而不是装作通过（dexdump 缺失时类判据会退化成弱判据，见 0b）。
# 口令一律从 server/.env 读，脚本不落字面量。
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
APK="${1:-}"
BASE="${2:-http://localhost:8080}"
[ -f "$ROOT/server/.env" ] && set -a && . "$ROOT/server/.env" && set +a

PASS=0; FAIL=0; SKIP=0
ok(){ echo "  ✓ $1"; PASS=$((PASS+1)); }
no(){ echo "  ✗ $1  → $2"; FAIL=$((FAIL+1)); }
warn(){ echo "  ! $1"; }
skip(){ echo "  · 跳过：$1"; SKIP=$((SKIP+1)); }

# ---------------- 0. 定位 APK 与 build-tools ----------------
if [ -z "$APK" ]; then
  for c in "$ANDROID/app/build/outputs/apk/release/app-release.apk" \
           "$ANDROID/app/build/outputs/apk/release/app-release-signed.apk" \
           "$ANDROID/app/build/outputs/apk/release/app-release-unsigned.apk" \
           "$ANDROID/app/build/outputs/apk/debug/app-debug.apk"; do
    [ -f "$c" ] && APK="$c" && break
  done
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "找不到 APK。先构建：cd android && ./gradlew assembleDebug（发布包：CHEMERA_API_BASE=https://域名 ./gradlew assembleRelease）"
  exit 1
fi
echo "== 目标包：$APK（$(du -h "$APK" | cut -f1 | tr -d '\n')）=="
IS_DEBUG=0
case "$APK" in *debug*) IS_DEBUG=1;; esac
[ "$IS_DEBUG" = "1" ] && warn "这是 debug 包：签名是 Android Debug debug.keystore，提审必被拒；此处只验证结构与前端产物。"
# 不带参数时默认会先找到 app-release-unsigned.apk（gradle assembleRelease 在无 keystore 的机器上就出这个）。
# 它验不过签名那条不是 bug，但"✗ 未签名"混在一堆结构断言里很容易让人以为包本身坏了，所以先说清楚。
case "$(basename "$APK")" in *unsigned*) warn "这是未签名的 release 包：只适合验结构与产物，签名那条必红；提审前用 keystore 签一遍再跑一次。";; esac

# 包里的**字符串常量**（广告位 ID 这类编译期内联进 dex 的值）只能这样搜。
# debug 包是分多 dex 的（classes.dex 里主要是 Capacitor，自研类在 classes2~7），
# 所以必须把 classes*.dex 连起来搜，只看 classes.dex 会把"在包里"判成"没进包"。
# 判"某个类在不在包里"不要用这个——见下面 classdef 的注释。
DEXCOUNT(){ unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -ac -- "$1" || true; }

# local.properties 里 sdk.dir 是 Java properties 格式（反斜杠是转义，所以写路径时得写成 D\:/…）
SDK_DIR=""
if [ -f "$ANDROID/local.properties" ]; then
  SDK_DIR=$(grep -E '^[[:space:]]*sdk\.dir=' "$ANDROID/local.properties" | tail -1 | cut -d= -f2- | sed 's/\\//g' | tr -d '[:space:]')
fi
[ -z "$SDK_DIR" ] && SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -n "$SDK_DIR" ] && [ -d "$SDK_DIR" ] && command -v cygpath >/dev/null 2>&1; then
  SDK_DIR=$(cygpath -u "$SDK_DIR")
elif [ -n "$SDK_DIR" ]; then
  # D:/x/y -> /d/x/y（这台机器上没装 cygpath 时的兜底）
  SDK_DIR=$(printf '%s' "$SDK_DIR" | sed -E 's#^([A-Za-z]):[\\/]?#/\L\1/#; s#\\#/#g')
fi
AAPT2=""; APKSIGNER=""; DEXDUMP=""
if [ -n "$SDK_DIR" ] && [ -d "$SDK_DIR/build-tools" ]; then
  # 优先用工程钉住的那份 build-tools（android/variables.gradle 的 buildToolsVersion），
  # 理由和 gradle 里钉它一样：本机装的不止一档，拿最高那档会验出和本机构建不同的 aapt2 行为。
  PIN=$(sed -n "s/^[[:space:]]*buildToolsVersion[[:space:]]*=[[:space:]]*'\([^']*\)'.*/\1/p" "$ANDROID/variables.gradle" | head -1)
  BT=""
  [ -n "$PIN" ] && [ -d "$SDK_DIR/build-tools/$PIN" ] && BT="$SDK_DIR/build-tools/$PIN/"
  [ -z "$BT" ] && BT=$(ls -d "$SDK_DIR"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
  [ -n "$BT" ] && AAPT2="${BT}aapt2" && APKSIGNER="${BT}lib/apksigner.jar" && DEXDUMP="${BT}dexdump"
fi
[ -x "$AAPT2" ] || AAPT2=""
[ -n "$DEXDUMP" ] && { [ -x "$DEXDUMP" ] || DEXDUMP=""; }
BADGE=""
if [ -n "$AAPT2" ]; then BADGE=$("$AAPT2" dump badging "$APK" 2>/dev/null); else warn "没找到 aapt2（检查 android/local.properties 的 sdk.dir）"; fi

# ---------------- 0b. 类"定义"清单（dexdump） ----------------
# 判"某个类在不在包里"为什么不能 grep 原始 dex：dex 的字符串区里**被引用的类名**和
# **被定义的类名**长得一模一样。Providers 用 Class.forName("com.chemera.game.ad.TapAdnProvider")
# 去找实现，那句字面量本身就把类名送进了包里——于是"provider 根本没编进来"这种最该报的错会判成"在包里"。
# 上一轮踩到的正是它的另一面：编译期桩改成 compileOnly 之后桩类已经不进包了，可 app 仍然引用 com.tapsdk.*，
# raw grep 照样把"没带 SDK"读成"带了 SDK"。dexdump 列的是 class_defs 段，只有真的定义了才算。
# 没有 dexdump（机器只有 build-tools 的一部分）时退回 raw grep 并明说这是弱判据：
# 宁可打得准一点，也不要让脚本装作用户手册。
CLASSES=""; CLS_OK=0
CHK_DIR="$ROOT/test/.apk-check"
rm -rf "$CHK_DIR"
if [ -n "$DEXDUMP" ] && mkdir -p "$CHK_DIR" && unzip -oq "$APK" 'classes*.dex' -d "$CHK_DIR" 2>/dev/null; then
  CLASSES="$CHK_DIR/descriptors.txt"
  "$DEXDUMP" "$CHK_DIR"/*.dex 2>/dev/null | grep -a "Class descriptor" \
    | sed -n "s/.*'\(L[^']*\)'.*/\1/p" > "$CLASSES"
  [ -s "$CLASSES" ] && CLS_OK=1
fi
classdef(){
  local d="$1"
  if [ "$CLS_OK" = "1" ]; then grep -qxF -- "L$d;" "$CLASSES"; return $?; fi
  [ "$(DEXCOUNT "$d")" -gt 0 ]
}
# "包里到底有没有真 SDK"（BuildConfig.AD_SDK_BUNDLED 是编译期内联常量，扫不出来，只能看类）。
# tapsdk-stub 那份桩与真实 SDK 同名同类，所以它自己带一个真实 AAR 里不可能有的探针类：
# 探针被**定义**了 = 桩被打进了包（compileOnly 被改回了 implementation），那次判的是桩，不是 SDK。
STUB_IN=0
classdef "com/tapsdk/ChemeraCompileOnlyStub" && STUB_IN=1
SDK_IN=0
classdef "com/tapsdk/tapad/TapAdSdk" && SDK_IN=1
[ "$STUB_IN" = "1" ] && SDK_IN=0
[ "$CLS_OK" = "0" ] && warn "没有 dexdump，类在不在包里退回搜 dex 原始字节（只认'被引用'，判不出'没编进来'）"

# ---------------- 1. 包名三处一致 ----------------
PKG=$(printf '%s' "$BADGE" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
GRADLE_APP_ID=$(sed -n 's/.*applicationId[[:space:]]*"\([^"]*\)".*/\1/p' "$ANDROID/app/build.gradle" | head -1)
# capacitor.config.ts 是 TS 源码，引号风格（单/双）不该成为回归的失败原因，所以两种都吃
CFG_APP_ID=$(sed -n "s/.*appId:[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" "$ANDROID/capacitor.config.ts" | head -1)
if [ -n "$BADGE" ]; then
  [ "$PKG" = "$GRADLE_APP_ID" ] && ok "applicationId 一致：$PKG" || no "applicationId" "APK=$PKG gradle=$GRADLE_APP_ID"
  [ "$PKG" = "$CFG_APP_ID" ] && ok "与 capacitor.config.ts 的 appId 一致" || no "capacitor appId" "APK=$PKG config=$CFG_APP_ID"
  # TapTap 的开发者后台、防沉迷实名、广告位都是挂在包名下的，改一个不换其余就是"线上数据串包"
  [ "$PKG" = "com.chemera.game" ] && ok "包名是登记过的那个（com.chemera.game）" || no "包名" "APK=$PKG"
else
  skip "包名比对（缺 aapt2）"
fi

# ---------------- 2. SDK 档位 ----------------
VCODE=$(printf '%s' "$BADGE" | sed -n "s/^package: name='[^']*' versionCode='\([^']*\)'.*/\1/p")
MIN=$(printf '%s' "$BADGE" | sed -n "s/^minSdkVersion:'\([^']*\)'.*/\1/p")
TGT=$(printf '%s' "$BADGE" | sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p")
# 底线来源：minSdk 24 = Capacitor 8 的硬要求（方案原写 23，以壳框架为准）；
# targetSdk 34 = 商店当年的受理线，本仓库钉在 36。提审前顺手看一眼商店政策，这条只保证不倒退。
if [ -n "$MIN" ] && [ -n "$TGT" ]; then
  [ "$MIN" -ge 24 ] && ok "minSdk=$MIN（≥24，Capacitor 8 底线）" || no "minSdk=$MIN" "低于 24 装不上 / 框架不兼容"
  [ "$TGT" -ge 34 ] && ok "targetSdk=$TGT（≥34 受理线）" || no "targetSdk=$TGT" "低于商店受理线，提审会被拒"
  [ -n "$VCODE" ] && ok "versionCode=$VCODE" || no "versionCode 缺失" ""
else
  skip "SDK 档位（缺 aapt2）"
fi

# ---------------- 3. 权限与清单里的合规开关 ----------------
if [ -n "$BADGE" ]; then
  EXTRA=$(printf '%s' "$BADGE" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" \
          | grep -v -E "^android.permission.INTERNET$|^${PKG}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$")
  [ -z "$EXTRA" ] && ok "权限只有 INTERNET（+ AGP 自加的 DYNAMIC_RECEIVER）" || no "权限超出必要范围" "$EXTRA"
  TREE=$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null)
  mflag(){ printf '%s' "$TREE" | grep -q "android:$1(0x[0-9a-f]*)=$2"; }
  mflag screenOrientation 1 && ok "强制竖屏（screenOrientation=1）" || no "竖屏" "手游上架会按方向锁分类"
  mflag allowBackup false && ok "allowBackup=false（存档不进系统备份，云存档是唯一真源）" || no "allowBackup" "没关"
  mflag usesCleartextTraffic false && ok "禁止明文 HTTP（release 走 https，误配 http:// 直接连不上而不是偷偷降级）" || no "usesCleartextTraffic" "没关死"
  mflag enableOnBackInvokedCallback false && ok "系统返回手势交给壳自己接管（MainActivity 的 OnBackPressedDispatcher）" || no "enableOnBackInvokedCallback" "没关"
  # ①（方案 §4）release 包不许是可调试构建。以前 debug/release 只按文件名判断，而文件名是人写的：
  # 构建参数被人临时改过、或者从别的目录拷来一份带 debuggable 的包，光看名字都会被骗过去。
  # 合并清单才是权威——release 的清单里这个属性根本不该出现（AGP 只在 debug buildType 注入它）。
  if [ "$IS_DEBUG" = "1" ]; then
    mflag debuggable true && ok "debug 包带 android:debuggable=true（本机可连 chrome://inspect；这个不能提审）" \
      || warn "debug 包里居然没有 debuggable：确认 gradle 的 buildType，别把 release 当 debug 跑"
  else
    printf '%s' "$TREE" | grep -q "android:debuggable(0x[0-9a-f]*)=true" \
      && no "发布包是可调试构建" "合并清单里有 android:debuggable=true ⇒ 任何一根 USB 线都能读出走壳的令牌，TapTap 审核 5.x 直接拒" \
      || ok "发布包不含 android:debuggable（不可调试）"
  fi
  # ④（方案 §4）组件暴露面。Android 12 起 exported 必须显式写，写 true 又不带 intent-filter
  # 等于"没有任何入口却对外敞开"，是能被别的 App 用显式 Intent 直接拉起来的后台通道。
  # 缩进还原层级：E: activity/service/receiver/provider 那一级是组件，它的属性正好深两级，
  # intent-filter 只要出现在它下面就算"有入口"。
  COMPONENTS=$(printf '%s\n' "$TREE" | awk '
    function flushrec() { if (nm != "") printf "%s|%s|%s\n", nm, ex, ft }
    { line = $0; sub(/[^ ].*$/, "", line); ind = length(line) }
    /^ *E: (activity|activity-alias|service|receiver|provider)( |$)/ {
      flushrec(); split($0, a, " "); nm = "[" a[2] " 未写 name]"; ex = "?"; ft = 0; base = ind; next
    }
    base > 0 && ind > base {
      if (ind == base + 2 && $0 ~ /android:name\(0x/) { match($0, /="[^"]*"/); nm = substr($0, RSTART + 2, RLENGTH - 3) }
      if (ind == base + 2 && $0 ~ /android:exported\(0x/) { ex = ($0 ~ /=true/) ? 1 : 0 }
      if ($0 ~ /^ *E: intent-filter/) ft = 1
    }
    END { flushrec() }')
  OPEN=$(printf '%s\n' "$COMPONENTS" | awk -F'|' '$2 == 1 && $3 == 0 { print $1 }')
  [ -z "$OPEN" ] && ok "所有 exported=true 的组件都带 intent-filter（没有裸暴露的后台通道）" \
    || no "exported 组件没有 intent-filter" "$(printf '%s' "$OPEN" | tr '\n' ' ')"
  LCH=$(printf '%s' "$TREE" | grep -c 'android.intent.category.LAUNCHER')
  [ "$LCH" = "1" ] && ok "桌面入口恰好一个 LAUNCHER" || no "LAUNCHER 数量异常" "$LCH 个（多一个就是装机后两个图标）"
  # 已知的 exported 基线：壳的入口 Activity + AGP 自动合进来的 profile 安装广播。
  # 接了 TapADN AAR 之后 SDK 的回调 Activity 会加进来（那是它接收广告跳转的入口，属预期），
  # 所以基线之外只有在"包里确实有 SDK"时才放行，否则就是有人往清单里加了组件而没人知道。
  KNOWN_OUT=$(printf '%s\n' "$COMPONENTS" | awk -F'|' '$2 == 1 && $1 != "com.chemera.game.MainActivity" \
              && $1 != "androidx.profileinstaller.ProfileInstallReceiver" { print $1 }' | tr '\n' ' ')
  if [ -z "$KNOWN_OUT" ]; then
    ok "对外组件就是基线那两个（MainActivity + ProfileInstallReceiver）"
  elif [ "$SDK_IN" = "1" ]; then
    ok "SDK 已入包，额外 exported 组件属预期：$KNOWN_OUT"
  else
    no "包里没有 TapADN SDK，却多出 exported 组件" "${KNOWN_OUT}（清单被手改过，或某个依赖悄悄加了入口）"
  fi
else
  skip "权限与清单断言（缺 aapt2）"
fi

# ---------------- 4. 壳里的前端产物 ----------------
LIST=$(unzip -Z1 "$APK" 2>/dev/null)
printf '%s' "$LIST" | grep -q "^assets/public/index.html$" && ok "APK 内含 Web 前端（assets/public/index.html）" || no "前端未打进包" "先跑 npx cap sync android"
for f in js/shell.js js/ads.js js/gate.js css/style.css; do
  printf '%s' "$LIST" | grep -q "^assets/public/$f$" && ok "  带 $f" || no "缺 $f" "壳内会白屏/按钮无响应"
done
# 缓存是 immutable 一年（WebConfig#addResourceHandlers），版本号 ?v= 就是唯一的刷新手段；
# 壳里带旧版 index.html 的后果是"改了不生效"，所以直接比对两处 ?v=。
APK_V=$(unzip -p "$APK" assets/public/index.html 2>/dev/null | grep -o 'v=3\.[0-9]*' | head -1)
SRC_V=$(grep -o 'v=3\.[0-9]*' "$ROOT/frontend/index.html" | head -1)
if [ -n "$APK_V" ] && [ "$APK_V" = "$SRC_V" ]; then
  ok "壳内前端与仓库同源（资源版本 $APK_V）"
elif [ -z "$APK_V" ] || [ -z "$SRC_V" ]; then
  warn "没能读到资源版本号（APK=$APK_V 仓库=$SRC_V），跳过这条"
else
  no "壳内前端是旧的" "APK=$APK_V 仓库=$SRC_V → 重跑 npx cap sync android"
fi
# 内容级同源裁判（2026-10-06 加）：上一版 APK 里 index.html 还带着仓库已删的 user-scalable=no，
# 但因为只比 ?v=（两处同为 3.14），第 5 层照样绿——"版本号没顶、内容改了"这类分叉从此判得到。
# 判法是最笨也最硬的：把壳里每个文本资产（js/css/html，生成物 app-config.js 除外）逐字节哈希对仓库。
ASSET_DIFF=""
for f in $(printf '%s' "$LIST" | sed -n 's|^assets/public/\(.*\)$|\1|p' | grep -E '\.(js|css|html|json)$' | grep -Ev '^(js/app-config\.js|cordova\.js|cordova_plugins\.js)$'); do
  if [ ! -f "$ROOT/frontend/$f" ]; then ASSET_DIFF="$ASSET_DIFF 仓库没有:$f"; continue; fi
  A=$(unzip -p "$APK" "assets/public/$f" 2>/dev/null | sha256sum | cut -d' ' -f1)
  B=$(sha256sum "$ROOT/frontend/$f" | cut -d' ' -f1)
  [ "$A" = "$B" ] || ASSET_DIFF="$ASSET_DIFF 内容不同:$f"
done
for f in $(cd "$ROOT/frontend" && find . \( -name '*.js' -o -name '*.css' -o -name '*.html' \) | sed 's|^\./||' | grep -v '^js/app-config\.js$'); do
  printf '%s' "$LIST" | grep -q "^assets/public/$f$" || ASSET_DIFF="$ASSET_DIFF 壳里缺:$f"
done
if [ -z "$ASSET_DIFF" ]; then
  ok "壳内前端与仓库逐字节同源（文本资产全量哈希比对）"
else
  no "壳内前端与仓库内容分叉（哪怕 ?v= 相同）" "差异:$ASSET_DIFF → 重跑 npx cap sync android 再 assembleDebug"
fi
# ⑤（方案 §4）商店版本号与资源版本号同源。gradle 现在从 frontend/index.html 读 ?v= 生成
# versionName（见 android/app/build.gradle 的 frontendVersion），所以这条红的含义很具体：
# 包里的 versionName 与包里的 assets/public/index.html 对不上 ⇒ 这次构建用的是同步之前的前端，
# 玩家看到的新版号、旧内容——比"版本号写错"难查得多，因为它一切"正常"。
APK_VN=$(printf '%s' "$BADGE" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | sed 's/-debug$//')
PKG_VN_IN_APK=$(unzip -p "$APK" assets/public/index.html 2>/dev/null | grep -o 'v=3\.[0-9]*' | head -1)
if [ -z "$BADGE" ] || [ -z "$APK_VN" ]; then
  skip "versionName 比对（缺 aapt2 或包里没版本号）"
else
  [ "${PKG_VN_IN_APK#v=}" = "$APK_VN" ] && ok "versionName=$APK_VN 与壳内前端 ?v= 同源（商店号与缓存号一个数）" \
    || no "versionName 与壳内前端不同源" "gradle=${APK_VN} 壳内前端=${PKG_VN_IN_APK:-读不到} → 重跑 npx cap sync android 再构建"
  [ "$APK_VN" = "${SRC_V#v=}" ] && ok "versionName 就是仓库当前这一版前端" \
    || no "versionName 落后于仓库" "包=$APK_VN 仓库=${SRC_V#v=} → 这个包不是从当前 frontend/ 构建的"
fi

# API 地址：generateAppConfig 在构建期写 assets/public/js/app-config.js（不在仓库里，见 android/.gitignore）
CFGJS=$(unzip -p "$APK" assets/public/js/app-config.js 2>/dev/null)
API=$(printf '%s' "$CFGJS" | sed -n 's/.*window\.CHEM_API_BASE[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p')
if [ -z "$API" ]; then
  if [ "$IS_DEBUG" = "1" ]; then
    ok "debug 包未写死 API（留空=同源，直连本机后端调试）"
  else
    no "发布包的 CHEMERA_API_BASE 是空的" "发布构建必须带 https:// 域名，否则玩家打开即网络错误"
  fi
else
  case "$API" in
    *localhost*|*127.0.0.1*|http://*)
      # 本机地址只允许出现在调试包里：安卓设备上的 localhost 是设备自己，指向它等于连不上。
      if [ "$IS_DEBUG" = "1" ]; then warn "debug 包指向本机地址 $API（发布包不能这样，真机上必连不上）"
      else no "发布包指向本机/明文地址" "$API"; fi;;
    https://*) ok "API 地址 $API（https）";;
    *) no "API 地址不是 https" "$API";;
  esac
fi

# ②（方案 §4）壳自己的两条危险开关，只能看包内那份 capacitor.config.json：
# 源码里 capacitor.config.ts 写对没用——它是构建期由 cap sync 生成 JSON 的，
# "改了 ts 没重跑 cap sync" 与 "临时开 CHEMERA_WEB_DEBUG / 填了 server.url 调试，忘了关就打包" 这两种事故，
# 都只有打开包本身才看得见。前者等于把整个运行时交给任何一根 USB 线，后者等于把玩家发往另一台服务器。
CCFG=$(unzip -p "$APK" assets/capacitor.config.json 2>/dev/null | tr -d ' \t\n')
if [ -z "$CCFG" ]; then
  no "APK 内没有 assets/capacitor.config.json" "cap sync 没跑成，壳连 appId 都读不到"
else
  case "$CCFG" in
    *'"webContentsDebuggingEnabled":true'*)
      no "包内开了 WebView 远程调试" "chrome://inspect 能直读 localStorage 里的令牌与存档";;
    *'"webContentsDebuggingEnabled":false'*)
      ok "包内 webContentsDebuggingEnabled=false（远程调试关着）";;
    *)
      no "capacitor.config.json 里没有 webContentsDebuggingEnabled" "读到的键：$(printf '%s' "$CCFG" | cut -c1-80)…";;
  esac
  # 只看 server 那一段里的 url：整份 JSON 里 "url" 到处都是（插件配置也有叫这名的），
  # 拿全局子串判会误报，拿不到 server 段又会漏报。
  SRV=$(printf '%s' "$CCFG" | sed -n 's/.*"server":{\([^}]*\)}.*/\1/p')
  case "$SRV" in
    *'"url"'*)
      # 指向远程 dev server 的包在没网的玩家手里是白屏，而且把"前端随包发布"这条纪律整条绕开
      no "包内配置带 server.url（壳去加载远程页面）" "server 段=$SRV";;
    *) ok "包内 server 段没有 url（前端随包发布，不远程加载）：server 段=$SRV";;
  esac
  case "$CCFG" in
    *'"androidScheme":"https"'*) ok "壳的 scheme 是 https（同源请求才走得上 /api）";;
    *) no "androidScheme 不是 https" "前端 cloud.js 判可用性用的是 /^https?:/，改这一项会连不上后端";;
  esac
fi
# ⑥（F7）CSS 旧写法兜底裁判跑**包里那份**，不是仓库那份：仓库修好了但 assets/public 是旧拷贝时，
# 在低端 WebView 上塌掉的仍然是玩家那块屏。裁判本体与 test/validate.js 第 1 层共用同一份 css-guard.js。
if command -v node >/dev/null 2>&1; then
  CSS_TMP="$ROOT/test/.apk-style.css"
  unzip -p "$APK" assets/public/css/style.css > "$CSS_TMP" 2>/dev/null
  if [ ! -s "$CSS_TMP" ]; then
    no "读不到包内 assets/public/css/style.css" "前端产物不完整"
  else
    CG=$(node "$ROOT/test/css-guard.js" "$CSS_TMP" 2>&1); CGRC=$?
    rm -f "$CSS_TMP"
    [ "$CGRC" = "0" ] && ok "包内 CSS 的 dvh/inset/color-mix 都有老写法兜底" \
      || { no "包内 CSS 缺旧引擎兜底（Android 8 那批 WebView 上整屏塌陷）" "见下"; printf '%s\n' "$CG" | sed 's/^/      /'; }
  fi
else
  skip "CSS 兜底裁判（本机没有 node）"
fi

# ---------------- 5. 原生插件与广告 SDK 是否在包里 ----------------
printf '%s' "$LIST" | grep -q "^assets/capacitor.plugins.json$" \
  && ok "带 capacitor.plugins.json" || no "插件清单缺失" "npm 依赖没同步"
PLUGINS=$(unzip -p "$APK" assets/capacitor.plugins.json 2>/dev/null | tr -d ' \t\n')
case "$PLUGINS" in
  *PreferencesPlugin*Share*) ok "Preferences + Share 已注册（令牌镜像与分享要用）";;
  *) no "npm 插件注册不全" "$PLUGINS";;
esac
# DEXCOUNT 与 SDK_IN 在第 0 节定义（第 3 节的组件暴露面要用它判断"SDK 在不在"）。
for C in ChemeraAdPlugin ChemeraLoginPlugin ChemeraInfoPlugin; do
  classdef "com/chemera/game/$C" && ok "包里有 $C（类已定义）" || no "$C 没进包" "MainActivity 的 registerPlugin 会直接编译失败或找不到类"
done
# ③（方案 §4，F4 的那颗雷）provider 实现类必须在包里。它们住在 app/src/tapadn/java，
# 以前"没 AAR 就不参与编译"，于是里面引用了不存在的字段也没人发现（详见 build.gradle 的 sourceSets 注释）。
# 现在这个源集永远编译，所以包里没有它 = 接线被改坏了，而不是"还没接 SDK"。
# 这条尤其要用 dexdump 那份定义清单：Providers 是拿字符串去找类的，raw grep 永远扫得到。
for C in TapAdnProvider TapTapLoginProvider; do
  classdef "com/chemera/game/ad/$C" && ok "包里有 $C（广告/登录通道接得上）" || no "$C 没进包" "src/tapadn 那个源集没被编进来，或类被改名"
done
# 同意闸门记在**原生侧**（ConsentStore），前端删掉那句判断也拦不住 SDK 起来；而审核 5.8 的口径是
# "同意之前不初始化任何第三方 SDK"，广告与 TapTap 登录两条路都要拦（F4 ②就是登录那条漏了）。
# 判的是字符串常量，正该用 raw grep——类判据（classdef）在这里反而不适用。
[ "$(DEXCOUNT NO_CONSENT)" -gt 0 ] \
  && ok "壳里带 NO_CONSENT 拒绝码（未同意时广告与 TapTap 登录都在原生侧被拦）" \
  || no "包里找不到 NO_CONSENT" "ConsentStore 的判定没接进插件：SDK 会在玩家同意之前被初始化"
# 广告 SDK 是本地 AAR，靠"libs 里有没有 aar"决定运行期用谁（见 app/build.gradle 的 adAar）。
# 没带不算失败：调试包本来就不该把三方 SDK 塞进去；但提审包必须带，所以把结论打在最后一行让人自己确认。
if [ "$STUB_IN" = "1" ]; then
  no "编译期桩被打进了包（dex 里有 com/tapsdk/ChemeraCompileOnlyStub）" \
     "tapsdk-stub 必须保持 compileOnly：桩与真实 SDK 同名同类，它进包会让下面这条'带没带 SDK'的判断自我欺骗"
fi
if [ "$SDK_IN" = "1" ]; then
  ok "包里带了 TapADN SDK（可投放激励视频）"
  # ③（续）SDK 进来了还要 ID/密钥同时到位，否则是"能弹广告、结不了算"——
  # BuildConfig.AD_MEDIA_ID 是编译期内联的字符串常量，非空时它的字面值一定在 dex 的字符串区里。
  if [ -n "${CHEMERA_AD_MEDIA_ID:-}" ]; then
    unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -aFq -- "$CHEMERA_AD_MEDIA_ID" \
      && ok "dex 里能找到构建机给定的广告位 ID（SDK 初始化的四个参数齐了）" \
      || no "带了 SDK 但 AD_MEDIA_ID 没进包" "构建时没导出 CHEMERA_AD_MEDIA_ID：SDK 会静默不加载，广告位永远回\"未配置\""
  else
    skip "没设 CHEMERA_AD_MEDIA_ID，无法比对包里的广告位 ID（提审构建必须在构建机上带着它）"
  fi
else
  warn "包里没带 TapADN SDK（app/libs/*.aar 不在）：广告位会明确回\"未内置\"，浏览器/调试包正常"
fi

# ---------------- 6. 签名指纹 ----------------
if [ -n "$APKSIGNER" ] && [ -f "$APKSIGNER" ]; then
  CERT=$(java -jar "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null)
  # 老版 apksigner 打 "Signer #1 certificate MD5 digest:"，新版打 "V2 Signer: certificate MD5 digest:"，
  # 按后缀抓就不怕 build-tools 换档了。
  MD5=$(printf '%s' "$CERT" | grep -i "MD5 digest" | head -1 | sed 's/.*digest: //')
  SHA=$(printf '%s' "$CERT" | grep -i "SHA-256 digest" | head -1 | sed 's/.*digest: //')
  DN=$(printf '%s' "$CERT" | grep -i "certificate DN" | head -1 | sed 's/.*DN: //')
  if [ -n "$MD5" ]; then
    ok "已签名：$DN"
    echo "      TapTap 后台要登记的签名 MD5 = ${MD5}（SHA-256 = ${SHA}）"
    [ "$IS_DEBUG" = "1" ] && warn "debug 证书不能提审：正式包用 android/keystore/ 下那份（口令在环境变量里）"
    if [ -n "${CHEMERA_APK_CERT_MD5:-}" ]; then
      M1=$(printf '%s' "$MD5" | tr -d ':' | tr 'A-F' 'a-f')
      M2=$(printf '%s' "$CHEMERA_APK_CERT_MD5" | tr -d ':' | tr 'A-F' 'a-f')
      [ "$M1" = "$M2" ] && ok "与登记指纹（CHEMERA_APK_CERT_MD5）一致" \
        || no "签名指纹和后台登记不一致" "TapTap 登录必失败：APK=$M1 登记=$M2"
    else
      skip "未设置 CHEMERA_APK_CERT_MD5，无法比对登记指纹"
    fi
  else
    no "APK 未签名或验签失败" "apksigner 没吐出证书（未签名包 / 签名版本过老）"
  fi
else
  skip "签名校验（没找到 build-tools 的 apksigner.jar）"
fi

# ---------------- 7. 图标五档 ----------------
# 不能按 res/mipmap-*dpi/ic_launcher.png 这种文件名去找：release 构建会把资源路径缩短成
# res/BW.png 之类，包本身没问题，是断言写错了。改看 aapt2 自己认下来的档位（badging 的
# application-icon-<dpi>），它才是"系统装机时会取哪张图"的答案；再补一条仓库侧检查，
# 确认每档的 PNG 兜底图（Android 8 以下）确实存在，而不只有一个 anydpi 的自适应 xml。
MISS=""
for D in 160 240 320 480 640; do
  printf '%s' "$BADGE" | grep -q "^application-icon-${D}:" || MISS="$MISS ${D}dpi"
done
if [ -n "$BADGE" ]; then
  [ -z "$MISS" ] && ok "启动图标覆盖 160/240/320/480/640 五档" || no "图标缺档" "$MISS"
else
  skip "图标档位（缺 aapt2）"
fi
SRCMISS=""
for D in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  [ -f "$ANDROID/app/src/main/res/mipmap-$D/ic_launcher.png" ] || SRCMISS="$SRCMISS $D"
done
[ -z "$SRCMISS" ] && ok "仓库里五张 mipmap PNG 齐全（低版本兜底）" || no "mipmap PNG 缺档" "$SRCMISS"

# ---------------- 8. 产物里不含服务端口令 ----------------
LEAK=""
for K in CHEMERA_DB_PASSWORD CHEMERA_BACKUP_PASSWORD CHEMERA_JWT_SECRET CHEMERA_SEED_ADMIN_PASS; do
  V=$(printenv "$K" 2>/dev/null)
  [ -z "$V" ] && continue
  if unzip -p "$APK" 'assets/public/*' 2>/dev/null | grep -aF -q -- "$V"; then LEAK="$LEAK $K"; fi
done
[ -z "$LEAK" ] && ok "打进包的 Web 产物里没有服务端口令" || no "包里出现口令变量名" "$LEAK"

# ---------------- 9. 与线上版本门的关系 ----------------
if curl -s -o /dev/null -m 3 "$BASE/api/healthz"; then
  JSON=$(curl -s "$BASE/api/app/version")
  MINB=$(printf '%s' "$JSON" | sed -n 's/.*"minBuild":\([0-9]*\).*/\1/p')
  LASTB=$(printf '%s' "$JSON" | sed -n 's/.*"latestBuild":\([0-9]*\).*/\1/p')
  if [ -n "$MINB" ] && [ -n "$VCODE" ]; then
    [ "$VCODE" -ge "$MINB" ] && ok "versionCode=$VCODE ≥ 服务端 minBuild=$MINB（这个包不会被版本门拦住）" \
      || no "versionCode 低于强制升级线" "包=$VCODE minBuild=$MINB → 旧包玩家进不来"
    if [ "$VCODE" -lt "${LASTB:-0}" ]; then
      warn "服务端已有 latestBuild=$LASTB，这个包是 $VCODE：提审前确认 gradle 里的 versionCode 递增了（方案 4.2）"
    else
      ok "versionCode=$VCODE 就是线上最新一版（latestBuild=${LASTB:-?}）"
    fi
  else
    skip "版本门比对：$BASE/api/app/version 没返回 minBuild/latestBuild"
  fi
else
  skip "版本门比对（$BASE 未响应；启动后端后重跑这条才有意义）"
fi

echo
# dexdump 用的那份 dex 是临时解出来的，判完就删：留在 test/ 下既会被 git status 绊一次，
# 也可能让下一次运行读到上一**个**包的类清单（这个脚本可以被指名传任意 APK）。
rm -rf "$CHK_DIR"
echo "== 通过 $PASS · 失败 $FAIL · 跳过 $SKIP =="
if [ "$FAIL" -gt 0 ]; then
  echo "结论：这个包不能提审，按上文 ✗ 逐条修。"
  exit 1
fi
echo "结论：结构与产物自检通过。商店素材（截图/玩法视频/隐私政策文本）与真机回归不在本脚本射程内。"
exit 0
