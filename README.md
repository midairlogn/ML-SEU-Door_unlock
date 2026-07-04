<div align="center">
<h1>SEU Door Lock</h1>
东南大学九龙湖校区门锁控制 Android 客户端，替代原"住理生活"混合应用。<br><br>

**中文简体** | [**English**](README_EN.md)
</div>

## 使用方法

1. **登录**：推荐使用支付宝快捷登录，也支持手机号 + 6 位数字密码登录。凭据经 Android Keystore + AES-256-GCM 加密存储在本地。
2. **NFC 开锁**：将手机贴近门锁 NFC 标签即可开锁，无需打开应用（需保持屏幕亮起）。
3. **蓝牙开锁**：在主界面点击开锁按钮，自动连接附近门锁并开锁。
4. **设置**：支持切换语言（中文/英文/跟随系统）和主题（浅色/深色/跟随系统）。

> 首次使用需联网同步门锁信息和凭据，之后 NFC 开锁可离线使用。

## 系统要求

- Android 7.0 (SDK 24) 及以上
- NFC 功能（必需）
- 蓝牙 LE（可选，用于蓝牙开锁）

## 安装

前往 [Release](https://github.com/midairlogn/ML-SEU-Door_unlock/releases) 页面下载最新 APK 安装。

或从源码构建：

```bash
git clone https://github.com/midairlogn/ML-SEU-Door_unlock.git
./gradlew assembleDebug
```

## 技术栈

- **语言**：Java
- **网络**：OkHttp 3
- **UI**：AndroidX, Material Design 3
- **加密**：Android Keystore + AES-256-GCM, RC4, CRC8

## 许可证

[GNU General Public License v3.0](LICENSE)
