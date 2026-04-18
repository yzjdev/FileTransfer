# FileTransfer

> 本项目由 AI 辅助完成。

FileTransfer 是一个 Android 局域网传输应用，用于在同一网络内快速发现设备并传输文件。

![应用截图](https://raw.githubusercontent.com/yzjdev/FileTransfer/master/screenshots/1.png)

## 下载

- Release APK:
  https://github.com/yzjdev/FileTransfer/releases/download/v1.0/FileTransfer-v1.0.apk

## 核心功能

- 自动发现同一局域网内的设备
- 支持文件和 APK 发送
- 支持多设备同时发送
- 显示传输进度、速率和时长
- 传输记录支持逐文件查看和打开
- 接收 APK 后可直接选择打开或安装
- 支持隐藏 IP，并记住显示状态

## 协议

设备发现：

```text
UDP 39458
FT_DISCOVERY_V1|deviceId|deviceName|tcpPort|timestamp
```

文件传输：

```text
TCP 39457
FILE_TRANSFER_V1
```

## 接收目录

```text
Android/data/<package-name>/files/Download/received
```

同名文件会自动追加序号，避免覆盖。

## 主要代码

| 文件 | 说明 |
| --- | --- |
| `MainActivity.kt` | UI、设备列表、记录页、APK 操作、多设备发送 |
| `TransferService.kt` | 设备发现、发送接收、进度更新 |
| `TransferModels.kt` | 数据模型、大小/速率/时长格式化 |

## 开源许可证

本项目采用 `Apache-2.0` 许可证，详见 [LICENSE](LICENSE)。

## 限制

- 仅适合同一局域网内使用
- 不支持断点续传
- 未做加密和身份校验
- 依赖 UDP 广播，部分网络环境下可能无法发现设备
