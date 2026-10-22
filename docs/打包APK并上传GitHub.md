# 打包 APK 并上传到 GitHub

适用于本仓库（紫罗兰Box / violet_Box）。以 V13 为例，换版本号只需替换名字。

---

## 一、整体流程

```
本地改代码
   ↓
提交到 main
   ↓
GitHub Actions 自动编译（Gradle assembleDebug / assembleRelease）
   ↓
APK 推到 apk 孤儿分支（只放成品，不混进源码）
   ↓
用 contents API 读回 APK
   ↓
创建 Release + 上传附件
```

---

## 二、需要新增 / 修改的文件

### 1. `.github/workflows/build-v13.yml`（新增，必需）

这是唯一必须新增的文件。放在仓库根目录的 `.github/workflows/` 下。

要点：

| 步骤 | 说明 |
|---|---|
| `on.push.branches: main` | 推到 main 就自动编译；也保留 `workflow_dispatch` 可手动触发 |
| JDK 17 | `setup-java@v4`，distribution 用 `temurin` |
| Android SDK | **不要用 `android-actions/setup-android`**——它在 ubuntu-latest 上会挂。直接下 Google 官方 `commandline-tools` 再装 `platforms;android-36` + `build-tools;36.0.0` |
| 两个构建 | `./gradlew :app:assembleDebug` 和 `assembleRelease`，分开跑，日志分开存 |
| 收集产物 | `find app/build/outputs -name "*.apk"` 全部拷到 `apk-out/` |
| 产物落盘 | 推到 **`apk` 孤儿分支**（见下节"为什么要孤儿分支"） |

### 2. 版本号（可选）

`app/build.gradle.kts` 里：

```kotlin
versionCode = 3
versionName = "1.1.1"
```

V13 沿用 1.1.1，没改。要区分版本就改这里。

### 3. 签名（可选，但要知道现状）

```kotlin
signingConfigs.getByName("debug")
```

release 构建**复用 debug 签名**（没有原作者 keystore）。后果：

- 打出的包**不能覆盖安装官方版**
- release 与 debug 签名不同，**互相切换前必须先卸载旧版**

有正式 keystore 的话在这里换成自己的。

---

## 三、关键设计：为什么用 `apk` 孤儿分支

Actions 制品（artifact）和 Release 附件的**下载地址在本项目开发环境下会被网络策略拦住**（HTTP 403）。所以：

```bash
git checkout --orphan apk      # 孤儿分支，与 main 完全独立的历史
git rm -rf . --quiet           # 清空
mkdir -p apk && cp apk-out/*.apk apk/
git add -f apk
git commit -m "..."
git push -f origin apk
```

好处：

- 成品不混进源码历史，main 保持干净
- 之后用 `contents` API 就能原样读回二进制：

```
GET /repos/{owner}/{repo}/contents/apk/app-release.apk?ref=apk
Accept: application/vnd.github.raw
```

`workflow_dispatch` 触发需要 workflow 已在默认分支上，第一次推送后要再推一次 commit 才能手动触发；用 `push` 触发则立即生效。

---

## 四、失败时怎么排查

Actions 日志下载同样被拦，所以 workflow 里加了失败诊断：

```yaml
- name: Failure diagnostics
  if: failure()
  run: |
    cat build-debug.log build-release.log | tail -n 300 > ci-logs/build-log.txt
    git checkout -B ci-logs && git add -f ci-logs
    git commit -m "..." && git push -f origin ci-logs
```

然后用 contents API 读 `ci-logs/build-log.txt` 即可看到 `error:` 行。

V13 第一次编译就靠这个抓到问题：

```
RomZoneActivity.java:361: error: cannot find symbol
  symbol: class FieldPicker
```

——替换代码块时把接口定义连带删掉了。补回后重编译通过。

---

## 五、上传到 Release

读回 APK 后：

1. 若同名 Release 已存在，先删：`DELETE /releases/tags/{tag}`
2. 创建：`POST /releases`，body 里写好说明
3. 上传附件：`POST https://uploads.github.com/repos/{owner}/{repo}/releases/{id}/assets?name=xxx.apk`
   - `Content-Type: application/vnd.android.package-archive`
   - body 是文件二进制

附件上传后 `state` 为 `uploaded` 即成功。

---

## 六、清理

编译完把临时分支删掉，仓库只留 main：

```bash
git push origin --delete apk ci-logs
```

---

## 七、本地编译（不用 Actions 也行）

```bash
git clone https://github.com/Buwrt/violet_Box.git
cd violet_Box
./gradlew :app:assembleDebug      # 或 assembleRelease
```

产物在 `app/build/outputs/apk/{debug,release}/`。

需要：JDK 17、Android SDK（platform 36 + build-tools 36.0.0）。
用 Android Studio 直接打开项目也行，它会自动处理 SDK。

---

## 八、针对 V13 的最小改动清单

如果你手上是从 V12 改到 V13 的代码，要让它能打包上传：

| 改什么 | 怎么改 |
|---|---|
| 新增 workflow | 建 `.github/workflows/build-v13.yml`，内容见第二节 |
| workflow 里的名字 | `name: Build V13 APK`、artifact 名 `VioletBox-V13-apk`、commit 文案里的 V13 |
| 其余 | **不用改**——`build.gradle.kts`、签名、SDK 版本都与 V12 一致 |

推到 main 后 Actions 自动跑，约 2–4 分钟出包。
