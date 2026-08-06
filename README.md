# FM Player - 车机私人 FM 播放器

一款为老旧车机（Android 4.x）定制的网易云私人 FM 播放器，专注于稳定播放和低资源占用。

## 特性

### 播放架构
- **原生播放器优先**：只用系统 MediaPlayer（厂商测试过的 stagefright 管线），适配车机硬件解码器
- **下载后播放**：整首歌下载到本地再播，避免老设备 TLS/网络栈问题
- **智能缓存**：500MB LRU 缓存，"上一首"和常听的歌瞬间响应
- **后台预下载**：当前歌播放时就下载下一首，按"下一首"时文件已经在本地

### 兼容性
- **最低支持 API 16** (Android 4.1+)，车机 API 17/19 实测通过
- **老设备 TLS 1.2 支持**：OkHttp 3.12.x + 自定义 TLS 配置，打通移动云盘 HTTPS 直链
- **无 multidex**：方法数优化后低于 65k，避免首次启动解压第二个 dex 的卡顿
- **低内存占用**：192MB 堆实测流畅运行

### 稳定性
- **单线程下载锁**：防止预下载和前台播放撞车（曾出现 0 字节假故障）
- **代次标记**：用户连按下一首时，正在下载的旧歌回调不会打断当前播放
- **失败分类处理**：网络失败自动重试换直链，解码失败则跳歌（避免卡在坏文件上）
- **一次性体检**：每个版本首次启动记录设备/解码器/网络环境，便于远程排查

## 使用方法

### 服务端部署

1. **准备环境**
   - 一台 Linux 服务器（或树莓派、NAS）
   - Docker + Docker Compose
   - 中国移动云盘账号（用于存储歌曲，获取免费直链）
   - OpenList（中国移动云盘 WebDAV 服务）

2. **配置**
   ```bash
   # 复制配置模板
   cp .env.example .env
   
   # 编辑 .env 填写真实配置
   OL_URL=http://你的服务器IP:5244
   OL_USERNAME=你的用户名
   OL_PASSWORD=你的密码
   OL_MUSIC_DIR=/移动云盘6611/myfm  # 歌曲存储目录
   ```

3. **启动服务**
   ```bash
   docker-compose up -d
   ```

4. **网易云授权**
   - 首次启动后访问 `http://你的服务器IP:3000/auth/qr` 
   - 用网易云 App 扫描二维码授权
   - 授权成功后 cookie 会持久化到 `/data/ncm_cookie.txt`

### 客户端安装

1. **下载 APK**
   - 从 [Releases](https://github.com/wang25669/fm-cmcc/releases) 下载最新版本
   - 点开一条 Release，在 **Assets** 里直接下 `.apk`（不是压缩包，下完就能装）
   - 标 `Pre-release` 的是每次 push 自动构建的，不带标记的是打过 tag 的正式版

2. **安装**
   - 车机上允许安装未知来源应用
   - 安装 `FMPlayer-vX.X.apk`

3. **配置后端地址**
   - 打开 App，点击右上角设置图标
   - 填写服务端地址：`http://你的服务器IP:3000`
   - 返回主界面，点击播放

### 日常使用

- **播放/暂停**：点击中央播放按钮
- **切歌**：左右按钮切换上一首/下一首
- **查看播放列表**：点击播放列表图标（仅显示本次启动后播过的歌）
- **查看日志**：车机文件管理器找 `/mnt/sdcard/fmplayer_debug.txt`

## 项目结构

```
.
├── FMPlayer/              # Android 客户端
│   ├── app/src/main/java/com/fmplayer/
│   │   ├── player/        # 播放引擎 + 缓存管理
│   │   ├── service/       # 后台服务（控制核心）
│   │   ├── network/       # 网络层 + TLS 适配 + 体检
│   │   └── ...
│   └── app/build.gradle   # 版本号在这里改：versionCode / versionName
│
├── fm_backend.py          # 统一后端服务
├── docker-compose.yml     # 容器编排配置
└── .env.example           # 配置模板
```

## 开发

### 修改版本号
每次调试完成后需要手动修改 `FMPlayer/app/build.gradle`：

```gradle
versionCode 2      // 整数，每次 +1，体检开关靠这个判断
versionName "1.1"  // 字符串，语义化版本号（对应 git tag）
```

### 本地编译
```bash
cd FMPlayer
./gradlew assembleDebug
# APK 输出到 app/build/outputs/apk/debug/
```

### CI/CD
- 每次 push 到 main 分支自动触发编译
- `versionCode` 自动 +1（基于 commit 数）
- `versionName` 跟 git tag 同步（无 tag 时用 `0.0.commitCount`）
- 编译好的 APK 自动发布到 Releases，可直接下载安装
- 打了 tag 的是正式版，其余标记为 Pre-release

## 技术细节

### 为什么不流式播放？
1. **用不上**：没有进度条拖动，只有上/下一首，秒开的价值有限
2. **风险大**：老设备的 MediaPlayer 走系统原生 stagefright，对 HTTPS 支持参差不齐（SNI/TLS 1.2 可能不完整）；而项目的 TLS 补丁在 OkHttp 这一侧，MediaPlayer 用不到

### 缓存清理策略
- **自动清理**：超过 500MB 时按 LRU（最久未播）删到降回 500MB 以下
- **系统回收**：用的是 `getExternalCacheDir()`，空间紧张时系统可以自行回收
- **正在播的歌**：`trim()` 时绝对保留，不会误删
- **`.part` 临时文件**：下载失败时立即清理，不占缓存预算

### 0 字节竞态修复
曾出现"下载下来是 0 字节"假故障（日志记录 5 次），根因是预下载和前台播放两个线程池同时下载同一首歌，第一个下完 rename 走了，第二个看到 `.part` 文件消失。现在用静态 `INFLIGHT` 锁，同一首歌的第二个请求会阻塞等第一个的结果，醒来直接用缓存文件。

### 体检机制
每个 `versionCode` 只在首次启动时跑一次，记录：
- 设备信息（型号/ABI/内存/CPU）
- 音频输出（音量/采样率/输出设备）
- 存储空间
- 解码器清单（只列汇总 + MP3 解码器名单）
- 网络 + TLS 版本 + 直链真实字节（ID3v2/MPEG 版本/比特率）

输出到 `fmplayer_debug.txt`，远程排查时发过来即可。

## 常见问题

**Q: 为什么不用 ExoPlayer？**  
A: 车机的 `OMX.mtk.audio.decoder.mpeg` 不发 `INFO_OUTPUT_FORMAT_CHANGED` 事件，ExoPlayer 的 `audioSink.configure()` 从没被调用过，直接 NPE。原生 MediaPlayer 是厂商出厂前测过的路径，车机能从 U 盘放 MP3 就说明这条路通。

**Q: 日志文件会越来越大吗？**  
A: 会。单文件追加写，不自动轮转（早期加过轮转，后来用户要求去掉）。实测一次 7 分钟播放日志约 20KB，一个月满负荷约 120MB，车机 SD 卡还剩 3.6GB，不是瓶颈。

**Q: 歌曲缓存会占满存储吗？**  
A: 不会。硬上限 500MB（约 50 首 320kbps 歌曲），超了就删最久没播的。而且用的是 cache 目录，系统空间不足时可以自己回收。

**Q: 为什么 `/next` 有时要等几秒？**  
A: 如果缓存未命中，后端要从网易云下载（~10MB）→ 上传到移动云盘 → 生成直链，网络差时单次可能 10-20 秒。预下载机制就是为了减少这种等待——只有第一首和"连按下一首快过预下载"才会卡。

**Q: 可以在普通手机上用吗？**  
A: 可以，但体验一般。设计目标是"老车机没有 adb 不能流式"，手机上完全可以选更现代的播放器。

## 许可证

MIT License

## 鸣谢

- [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi) - 网易云音乐 API
- [UnblockNeteaseMusic](https://github.com/UnblockNeteaseMusic/server) - 灰色歌曲解锁
- OkHttp - 老设备 TLS 适配的基础
