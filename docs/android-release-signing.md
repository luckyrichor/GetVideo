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
3. 都没有 → 回退到调试签名（只保证能编，**不能用于发布**）

properties 格式：

```properties
storeFile=/Users/lc/.android-keys/getvideo-release.jks
storePassword=***
keyAlias=getvideo
keyPassword=***
```

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

## 迁移注意

换成固定证书是一次性的"签名断代"：手机上装旧证书包的设备，第一次升级必须卸载重装
（会丢应用私有数据），或者改用 `.preview` 并存版先过渡。
