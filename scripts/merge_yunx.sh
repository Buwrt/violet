#!/usr/bin/env bash
# 在 CI runner 上把 YunX 源码并入已检出的 violet_Box 工作树。
# YunX 由调用方 clone 到 $1（默认 /tmp/yunx）。
set -eo pipefail

YUNX="${1:-/tmp/yunx}"
DST="app/src/main"
echo "YunX 源: $YUNX"
ls "$YUNX/app/src/main"

# ---------- 1. Kotlin 源码：整包拷入（包名 com.yunx.app 与 com.violet.box 不冲突） ----------
mkdir -p "$DST/kotlin/com/yunx"
cp -r "$YUNX/app/src/main/kotlin/com/yunx/app" "$DST/kotlin/com/yunx/"
echo "Kotlin 文件: $(find "$DST/kotlin/com/yunx" -name '*.kt' | wc -l)"

# ---------- 2. res：冲突项加 yunx_ 前缀 ----------
mkdir -p "$DST/res/drawable"

# drawable：与宿主同名的改名
for f in "$YUNX"/app/src/main/res/drawable/*; do
  b=$(basename "$f")
  if [ -e "$DST/res/drawable/$b" ]; then
    cp "$f" "$DST/res/drawable/yunx_$b"
    echo "  改名 drawable: $b -> yunx_$b"
  else
    cp "$f" "$DST/res/drawable/$b"
  fi
done

# drawable-* 变体目录
for d in drawable-nodpi drawable-v24 drawable-v26; do
  if [ -d "$YUNX/app/src/main/res/$d" ]; then
    mkdir -p "$DST/res/$d"
    cp -r "$YUNX/app/src/main/res/$d/." "$DST/res/$d/"
  fi
done

# mipmap：全部加前缀，避免覆盖宿主图标
for d in "$YUNX"/app/src/main/res/mipmap*; do
  [ -d "$d" ] || continue
  dn=$(basename "$d")
  mkdir -p "$DST/res/$dn"
  for f in "$d"/*; do
    cp "$f" "$DST/res/$dn/yunx_$(basename "$f")"
  done
done
echo "mipmap 已加 yunx_ 前缀"

# values：app_name -> yunx_app_name，主题文件改名存放（资源名与文件名无关，R 引用照旧可用）
mkdir -p "$DST/res/values"
sed 's/<string name="app_name"/<string name="yunx_app_name"/g' \
  "$YUNX/app/src/main/res/values/strings.xml" > "$DST/res/values/yunx_strings.xml"
cp "$YUNX/app/src/main/res/values/themes.xml" "$DST/res/values/yunx_themes.xml"

# xml：网络安全配置（YunX 的公告服务需放行明文域）+ 合并 FileProvider 路径
cp "$YUNX/app/src/main/res/xml/network_security_config.xml" "$DST/res/xml/"
python3 - <<'PY'
import re
src=open('/tmp/yunx/app/src/main/res/xml/file_paths.xml',encoding='utf-8').read()
dst=open('app/src/main/res/xml/file_paths.xml',encoding='utf-8').read()
m=re.search(r'<paths>(.*?)</paths>', src, re.S)
if m and '</paths>' in dst:
    merged=dst.replace('</paths>', '\n    <!-- 以下来自 YunX（网盘解析/下载的文件共享） -->'+m.group(1)+'</paths>')
    open('app/src/main/res/xml/file_paths.xml','w',encoding='utf-8').write(merged)
    print("file_paths.xml 已合并")
PY

# ---------- 3. assets：YunX 自带的静态资源（Gopeed 内核、内置规则等） ----------
if [ -d "$YUNX/app/src/main/assets" ]; then
  mkdir -p "$DST/assets"
  cp -r "$YUNX/app/src/main/assets/." "$DST/assets/"
  echo "assets: $(find "$DST/assets" -type f | wc -l) 个文件"
fi

# ---------- 4. libs：YunX 自带的 Gopeed 绑定 jar ----------
if [ -d "$YUNX/app/libs" ]; then
  mkdir -p app/libs
  cp -r "$YUNX/app/libs/." app/libs/
  echo "libs: $(ls app/libs)"
  # 让 Gradle 认下这个 jar（宿主原本没有 libs 目录依赖声明）
  python3 - <<'PYEOF'
import re
p='app/build.gradle.kts'
s=open(p,encoding='utf-8').read()
if 'gopeed-classes.jar' not in s:
    s=s.replace('    // ===== 云析 YunX（AGPL-3.0）=====',
                '    // 云析的 Gopeed 引擎绑定（YunX 自带的预编译 jar）\n'
                '    implementation(files("libs/gopeed-classes.jar"))\n\n'
                '    // ===== 云析 YunX（AGPL-3.0）=====')
    open(p,'w',encoding='utf-8').write(s)
    print("已加入 gopeed-classes.jar 依赖")
PYEOF
fi

# ---------- 5. jniLibs：YunX 自带的 so（若有）----------
if [ -d "$YUNX/app/src/main/jniLibs" ]; then
  mkdir -p "$DST/jniLibs"
  cp -r "$YUNX/app/src/main/jniLibs/." "$DST/jniLibs/"
  echo "jniLibs 已拷贝"
fi

# ---------- 6. R 类改写 ----------
# 融合后整个 APK 只有一个 R，生成在宿主的 namespace（com.violet.box）下。
# YunX 源码里写的是 com.yunx.app.R，必须统一改成 com.violet.box.R；
# 隐式引用 R（没 import，靠同包名解析）的文件也要补上显式 import。
python3 - <<'PYEOF'
import os, re
root = 'app/src/main/kotlin/com/yunx'
HOST_R = 'com.violet.box.R'
changed = 0
imported = 0
for dp, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith('.kt'):
            continue
        fp = os.path.join(dp, f)
        src = open(fp, encoding='utf-8').read()
        if not re.search(r'\bR\.(string|drawable|layout|mipmap|xml|color|style|dimen|id|array|plurals|raw|anim|font)\b', src):
            continue
        out = src
        # 把 com.yunx.app.R 改成宿主 R
        if 'com.yunx.app.R' in out:
            out = out.replace('com.yunx.app.R', HOST_R)
            changed += 1
        # 没有显式 import R 的，补一个（放在 package 行之后）
        if not re.search(r'^import\s+' + HOST_R.replace('.', r'\.') + r'\s*$', out, re.M):
            m = re.search(r'^package\s+[^\n]+\n', out, re.M)
            if m:
                out = out[:m.end()] + '\nimport ' + HOST_R + '\n' + out[m.end():]
                imported += 1
        if out != src:
            open(fp, 'w', encoding='utf-8').write(out)
print(f"R 引用改写: {changed} 个文件换包名, {imported} 个文件补 import")
PYEOF

echo "===== 合并完成 ====="
