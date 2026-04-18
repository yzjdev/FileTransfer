# FileTransfer

FileTransfer 是一个局域网文件传输 Android 应用，支持设备发现、文件发送、APK 传输与安装、传输记录查看，以及多设备批量发送。

## 当前功能

- 局域网内自动发现设备
- 文件和 APK 发送
- 接收后保存到应用下载目录
- 传输记录展示进度、速率、时长
- 批量传输时按文件单独高亮显示，可分别点击
- 接收到的 APK 支持弹窗选择“打开 / 取消 / 安装”
- 支持多设备同时发送
- 支持设备全选、取消全选
- 支持隐藏 IP，并记住显示/隐藏状态

## 设备发现与传输协议

应用使用两套自定义协议：

| 用途 | 传输层 | 端口 | 协议标识 |
| --- | --- | ---: | --- |
| 设备发现 | UDP 广播 | `39458` | `FT_DISCOVERY_V1` |
| 文件传输 | TCP | `39457` | `FILE_TRANSFER_V1` |

协议核心实现位于：

`app/src/main/java/io/github/yzjdev/filetransfer/transfer/TransferService.kt`

### 设备发现协议

发现报文格式：

```text
FT_DISCOVERY_V1|deviceId|encodedDeviceName|tcpPort|timestamp
```

说明：

- 使用 UDP 广播发送
- 设备名通过 UTF-8 URL 编码
- 接收端根据来源地址记录对端 IP
- 同设备 ID 的报文会被忽略
- 超过 12 秒未再次出现的设备会被移除

### 文件传输协议

文件传输使用 TCP 直连，按以下顺序写入：

#### 连接头

```text
writeUTF(PROTOCOL_MAGIC)
writeUTF(localId)
writeUTF(localName)
writeLong(totalBytes)
writeInt(itemCount)
```

#### 每个文件

```text
writeUTF(displayName)
writeLong(sizeBytes)
writeUTF(mimeType)
write(file bytes)
```

接收端按相同顺序读取。

### 编码方式

| 方法 | 编码 |
| --- | --- |
| `writeUTF` / `readUTF` | Java Modified UTF-8 |
| `writeLong` / `readLong` | 8 字节大端整数 |
| `writeInt` / `readInt` | 4 字节大端整数 |
| 文件内容 | 原始字节流 |

## 传输目录

接收文件保存到：

```text
Android/data/<package-name>/files/Download/received
```

如果同名文件已存在，会自动追加 ` (1)`、` (2)` 等后缀，避免覆盖。

## 传输记录

记录页当前支持：

- 显示发送 / 接收状态
- 显示总大小与已传输大小
- 显示平均速率和耗时
- 显示对端名称和地址
- 地址显示受首页“隐藏 IP”开关影响
- 多文件记录按单独文件项展示

### 文件项交互

- 普通文件：点击后调用系统应用打开
- APK 文件：点击后弹出操作对话框

### APK 对话框

按钮顺序：

```text
选择打开 | 取消 | 安装
```

说明：

- `选择打开`：调用系统应用选择器
- `安装`：调用系统安装器
- Android 8.0+ 若未授权“安装未知应用”，会先跳到授权页面

## 多设备发送

首页设备区支持：

- 单设备直接发送
- 多设备选择后批量发送
- 全选 / 取消全选

批量发送逻辑：

- 每个设备卡可以单独切换“选择 / 已选”
- 上方工具栏显示已选设备数量
- 点击发送图标可对所有已选在线设备并行发送同一批文件

## Debug 包说明

当前 `debug` 构建类型已做区分，配置位于：

`app/build.gradle.kts`

主要配置：

```kotlin
debug {
    applicationIdSuffix = ".debug"
    versionNameSuffix = "-debug"
    resValue("string", "app_name", "FileTransfer Debug")
}
```

以及：

```kotlin
buildFeatures {
    compose = true
    resValues = true
}
```

效果：

- debug 包名变为 `io.github.yzjdev.filetransfer.debug`
- 可与正式版同时安装
- 桌面应用名显示为 `FileTransfer Debug`

## 安装 Debug 包

推荐使用构建产物：

```powershell
.\gradlew.bat assembleDebug
```

生成文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

注意：

- 请分发 `assembleDebug` 生成的 APK
- 不要分发 Android Studio Run 时临时部署的 `testOnly` 包
- 设备需允许“安装未知应用”

## 已做的兼容性处理

发送端在发送前会尽量获取真实文件大小：

- 普通文件直接读取 `file.length()`
- `content://` 文件优先读取 `AssetFileDescriptor.length`
- 如果拿不到可靠长度，则先复制到临时文件，再按临时文件大小发送

这样可以避免发送大小与实际字节数不一致，降低接收失败或 APK 损坏概率。

## 主要代码位置

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/io/github/yzjdev/filetransfer/MainActivity.kt` | Compose UI、设备列表、记录页、APK 打开/安装、多设备发送 |
| `app/src/main/java/io/github/yzjdev/filetransfer/transfer/TransferService.kt` | UDP 发现、TCP 传输、接收保存、进度更新 |
| `app/src/main/java/io/github/yzjdev/filetransfer/transfer/TransferModels.kt` | 传输模型、大小/时长/速率格式化 |
| `app/build.gradle.kts` | 构建配置、debug 包区分 |

## 当前限制

- 无加密、无鉴权、无配对机制
- 无断点续传
- 无文件校验和 / 哈希校验
- 依赖局域网 UDP 广播，部分隔离网络下可能无法发现设备
- 非 Java / Kotlin 客户端如果要兼容，必须按当前二进制协议实现

