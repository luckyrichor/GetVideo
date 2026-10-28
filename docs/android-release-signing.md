# Android 发布签名（固定证书）

## 为什么需要

Android 用"签名证书"认定应用归属。包名相同、证书不同 → 系统拒绝覆盖安装
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），只能卸载重装，而卸载会清掉应用私有目录
（`files/video` 里的视频文件就在那儿）。

早期构建直接复用了各自环境自动生成的调试证书（`~/.android/debug.keystore`，
每台机器/每个 HOME 首次构建时随机生成），所以换环境后签名就变了。从 0.3.0 起改用
固定发布证书，后续任何机器构建的包都能互相覆盖升级。

## 证书在哪

| 项目 | keystore | 证书 SHA-256 |
|---|---|---|
| GetAudio | `~/.android-keys/getaudio-release.jks` | `9D:A1:6F:85:94:B6:FA:88:9C:79:90:5C:3B:D3:B4:31:C1:A0:79:EE:0B:CB:0A:48:E1:C1:83:A0:44:3F:50:C5` |
| GetVideo | `~/.android-keys/getvideo-release.jks` | `9D:58:FE:AE:04:C2:1E:02:E6:24:13:B6:14:22:C7:EF:D5:7B:27:C3:3D:12:EF:8C:2F:E7:D6:1A:09:2B:73:37` |

- 类型：PKCS12，RSA 4096，有效期 10000 天，别名与项目同名。
- 口令与路径写在 `~/.android-keys/getvideo.properties`，`chmod 600`。
- 备份：服务器 `tx:~/.app-keys/`，换机器时拷回 `~/.android-keys/`。
- **绝不入库**：`.gitignore` 已排除 `*.jks`、`*.keystore`、`android/keystore.properties`。

## 构建怎么取用

`app/build.gradle.kts` 按顺序查找签名配置：

1. `~/.android-keys/getvideo.properties`（首选）
2. 项目根 `keystore.properties`（可选，适合 CI 注入）
3. 都没有 → **直接构建失败**（不再悄悄回退调试证书）

第 3 条是刻意设计的：以前"找不到就退回调试签名"，结果产出一个能编、能装、但证书不同的
APK，装到手机上才发现撞墙。现在宁可编译报错，也不让不同签名的包流出去。
确实只是本机临时调试、不发布时，才加 `-PallowDebugSignRelease=true` 绕过。

报错长这样：

```
找不到固定的发布密钥，已中止 release 构建。
请把项目密钥安装到本机后再构建： ./scripts/setup-release-key.sh
```

properties 格式：

```properties
storeFile=/Users/lc/.android-keys/getvideo-release.jks
storePassword=***
keyAlias=getvideo
keyPassword=***
```

## CI（GitHub Actions）为什么不出正式包

`.github/workflows/tests.yml` 只跑 `./gradlew testDebugUnitTest assembleDebug`：

- 密钥按设计不进 CI（不放 Secrets，避免泄露），runner 上自然没有 `~/.android-keys/`；
- 硬跑 `assembleRelease` 会被上面的强制检查拦住 → 每次 push 都红 → 每天一堆「Run failed」邮件。
  2026-10-27 就踩过一次：加了强制检查后本仓库的 CI（里面带 `assembleRelease`）连续失败。

所以 CI 只负责「代码能编、测试能过」，正式包一律在本机构建。

另外强制检查是按**任务名**判断的（`assembleRelease` / `bundleRelease` / `build` / `assemble`），
`testDebugUnitTest`、`assembleDebug` 和 IDE 同步（taskNames 为空）都不会被拦——
否则 CI 和 Android Studio 在没装密钥的机器上会直接开不了工。

## 新环境怎么开工（换电脑 / 换 Agent / CI）

密钥不在仓库里，所以**任何新环境第一次构建前都要先装密钥**：

```bash
scripts/setup-release-key.sh          # 从 tx:~/.app-keys/ 拉取，自动 chmod 600 并校验指纹
scripts/setup-release-key.sh ~/bak    # 或者从本地备份目录拷贝
```

脚本会打印 keystore 的 SHA-256，和本文档上表核对一眼即可。装过一次就一直在，
后续 `./gradlew assembleRelease` 直接可用。

> 注意：让 Agent（不论跑在 Mac 还是服务器）改功能都没问题——**只要它在这台机器上构建**，
> 用的就是 `~/.android-keys/` 里这把证书。真正需要额外一步的只有"在一台全新机器上构建"，
> 那种情况先跑上面的脚本。

## 日常命令

```bash
cd android
./gradlew assembleRelease                        # 产出 app-release.apk（固定证书）
./gradlew assembleRelease -PgetvideoPreview=true # 并存版：包名 .preview，桌面名「GetVideo 新版」
```

校验签名：

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk | grep SHA-256
```

## 发布：标签 + GitHub Release（每次都要）

```bash
git tag -a v0.3.9 -m "GetVideo 0.3.9：……" && git push origin main v0.3.9

gh release create v0.3.9 \
  --title "GetVideo v0.3.9" \
  --notes-file /tmp/rel-video.md \
  android/app/build/outputs/apk/release/app-release.apk#getvideo-0.3.9-release.apk
```

约定：标题 `GetVideo v0.3.9`、tag 同名带 `v`、附件名 `getvideo-0.3.9-release.apk`，
说明分「更新内容 / 验证」两段，末尾注明「与上一版同签名，可直接覆盖安装」，
并与 GetAudio 同号版本对齐发布。

## 迁移注意

换成固定证书是一次性的"签名断代"：手机上装旧证书包的设备，第一次升级必须卸载重装
（会丢应用私有数据），或者改用 `.preview` 并存版先过渡。
