#!/usr/bin/env bash
# test/admin-dist-check.sh —— 随包后台产物 == admin/src 现在这份代码 build 出来的产物。
#
# 为什么需要它（迭代 4 的 F6）：本项目的规矩是"后台产物入库、jar 直出"，
# 而 `.github/workflows/ci.yml` 以前只有 `mvn package`，没有任何一步重建后台。
# 于是出现过一个只有干净检出才会暴露的状态：`admin/src` 里已经写好的【运营配置】新表单，
# 随包那份 `index-*.js` 里搜不到——本地看工作区永远是绿的，CI 与线上却是旧的。
# 这类"改了源码忘了出包 / 出了包忘了入库"的错位，靠人记是记不住的，所以拿构建来对账。
#
# 做法：把 admin/ 重新 build 到一个临时目录（不动随包那份），逐文件比 sha256。
# 只在 CI 里额外跑 `git diff --quiet` 才是完整的——本地工作区允许"刚改完还没入库"，
# 而"随包产物没跟着源码重出"在任何时候都是错的，所以这条按后者判。
#
# 退出码：0 一致 / 1 不一致或 build 失败。依赖：node、npm、git（只读）。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/admin"
OUT="$ROOT/server/src/main/resources/static/admin"
TMP="$SRC/.dist-check"
QUIET="${1:-}"

fail() { echo "  ✗ $1"; exit 1; }
say() { [ "$QUIET" = "-q" ] || echo "  $1"; }

[ -d "$SRC" ] || fail "找不到 admin/ 源码目录"
[ -d "$OUT" ] || fail "随包后台目录不存在：$OUT（先跑一次 npm run build）"

# 1) 重新出一份到临时目录（--outDir 用相对路径：Vite 把绝对路径当相对 root 解析过，别冒险）
if [ ! -d "$SRC/node_modules" ]; then
  say "admin/node_modules 不在，先 npm ci"
  (cd "$SRC" && npm ci > /tmp/admin-ci.log 2>&1) || fail "npm ci 失败，见 /tmp/admin-ci.log"
fi
rm -rf "$TMP"
say "重新构建后台（vite build → admin/.dist-check）"
(cd "$SRC" && npm run build -- --outDir .dist-check --emptyOutDir --logLevel warn > /tmp/admin-dist-build.log 2>&1) \
  || fail "后台构建失败：见 /tmp/admin-dist-build.log（末尾：$(tail -3 /tmp/admin-dist-build.log | tr '\n' ' ')）"
[ -f "$TMP/index.html" ] || fail "临时产物里没有 index.html，构建产物不可信"

# 2) 文件清单先对齐：Vite 的产物名带内容哈希，多一个少一个都说明两边不是同一份代码
LIST_A=$(cd "$OUT" && find . -type f | sort)
LIST_B=$(cd "$TMP" && find . -type f | sort)
if [ "$LIST_A" != "$LIST_B" ]; then
  echo "  ✗ 随包后台与源码重新构建的产物文件清单不一致（哈希变了就是内容变了）"
  diff <(printf '%s\n' "$LIST_A") <(printf '%s\n' "$LIST_B") | sed -n '1,20p'
  echo "    出路：cd admin && npm run build，再把 server/src/main/resources/static/admin 的增删改一起入库"
  rm -rf "$TMP"; exit 1
fi

# 3) 同名文件逐个比字节；只报前 5 处，够了——真要 diff 的人自己去看那两个文件
BAD=""
N=0
while IFS= read -r f; do
  N=$((N+1))
  HA=$(sha256sum "$OUT/$f" | cut -d' ' -f1)
  HB=$(sha256sum "$TMP/$f" | cut -d' ' -f1)
  [ "$HA" = "$HB" ] || BAD="$BAD ${f#./}"
done < <(printf '%s\n' "$LIST_B")
rm -rf "$TMP"

if [ -n "$BAD" ]; then
  echo "  ✗ 随包后台产物是旧的（$(printf '%s' "$BAD" | wc -w) / $N 个文件对不上）"
  for b in $BAD; do echo "      · $b"; done | sed -n '1,5p'
  echo "    出路：cd admin && npm run build（产物直出到 server/src/main/resources/static/admin），再重出 jar"
  exit 1
fi
say "随包后台与源码同源（$N 个产物逐文件 sha256 相同）"
exit 0
