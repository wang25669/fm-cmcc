# FMPlayer — 老车机 / 旧安卓上的网易云私人 FM 播放器

一套「自己部署后端 + 装个 APK」的网易云私人 FM 播放器。后端把网易云私人 FM 推给你的歌下载下来、转存到你的网盘，再给 App 一个新鲜直链；App 整首下载到本地，交给系统 `MediaPlayer` 播放。

设计目标只有一个：**在 Android 4.1 起的破旧设备（尤其是魔改车机）上稳定出声**。所有看起来"没必要这么麻烦"的实现，都是为了这台设备。

---

## 它能做什么

- **私人 FM 连续播放**：自动取歌、自动预取下一首，按"下一首"基本秒开。
- **调教推荐流**：红心（喜欢）、垃圾桶（不感兴趣）都真实回传网易云，比只传播放记录有力得多。
- **多种推荐模式**：首页有 4 个快捷模式（默认 / 探索 / 开车 / 离线），设置页另有 9 种细分场景曲风（纯音乐、节奏、伤感、慢摇 DJ、民谣、爵士、华语、欧美、粤语）。
- **离线播放**：听过的歌保留在本地缓存（500MB，约 50 首），地铁、地下车库没信号也能播。
- **多设备多账号隔离**：每台设备（每个 App 安装）自动生成独立 Client ID，各自绑自己的网易云 Cookie 和推荐模式，互不串味；共享同一个网盘曲库，同一首歌并发请求只下载上传一次。
- **老设备兼容**：minSdk 16，无 multidex，不加载任何封面图片，横竖屏自动两套布局（车机横屏 / 手机竖屏）。
- **可远程排查**：每个版本首次启动写一次设备/解码器/网络体检，日志落在 SD 卡，出问题直接发文件。

---

## 架构

```
             ┌──────────── 你的服务器 / NAS（docker compose）────────────┐
  FMPlayer   │  fm-backend :3000 ──> ncm-api        （网易云 API）      │
  (Android) ─┼──> HTTP ───────┤        └─> unm-utils（VIP/灰歌解锁，可选）│
             │                └──WebDAV──> OpenList ──> 网盘（歌曲仓库） │
             └──────────────────────────────────────────────────────────┘
```

- **fm-backend**（`fm_backend.py`，单文件 Flask）：对 App 只暴露一套简单接口，把三个上游串起来。容器内监听 `5000`，对外映射 `3000`。
- **ncm-api**：网易云 API 镜像 `moefurina/ncm-api`，只在内网被后端调用，不对外开端口。
- **unm-utils**：VIP / 无版权歌曲解锁，可选。默认开启，音源逐个显式尝试，谁超时就换下一个（不会一个坏音源拖死全链路）。
- **OpenList**：歌曲仓库 + 直链来源，**独立于本 compose 部署**。后端用 WebDAV 上传，用 OpenList 的 `/api/fs/link` 取直链。
- **App**：只知道一个后端地址，其余上游对它完全透明。

**为什么整首下载而不是流式播放？** 老车机的 `MediaPlayer` 走厂商 stagefright，HTTPS/SNI/TLS 1.2 支持参差不齐；而本项目的 TLS 1.2 补丁全在 OkHttp 一侧，`MediaPlayer` 享受不到。下载与解码分离还有个好处：日志能直接指认是网络问题还是解码问题。

---

## 你需要准备

1. **一台常年在线的 Linux 机器**（NAS / 树莓派 / VPS 都行），装好 Docker 与 Docker Compose。项目离不开常驻 Linux：两个上游是容器，曲库在你家/你的网盘。
2. **一个 OpenList 实例**，且目标网盘能正常读写。示例用**中国移动云盘**（直链免费、稳定），其它网盘同样可用。
3. **一个网易云音乐账号**（登录用 App 扫码，不需要密码）。
4. **一台 Android 4.1+ 设备**（车机、旧手机、平板）。

---

## 一、服务端部署

### 1. 先在 OpenList 里准备音乐目录

在 OpenList 里建一个专门放歌的目录（例：`/移动云盘6611/myfm`），记住：

- OpenList 地址，例 `http://192.168.1.10:5244`
- 登录用户名 / 密码
- 音乐目录的完整路径

确认 WebDAV 能访问（后端上传走 `http://OpenList地址/dav<目录>`）。目录**先建好**，服务端启动时会扫一次建索引。

### 2. 拉代码并填配置

```bash
git clone https://github.com/wang25669/fm-cmcc.git
cd fm-cmcc
cp .env.example .env
```

编辑 `.env`：

| 变量 | 说明 |
|---|---|
| `OL_URL` | OpenList 地址，例 `http://192.168.123.178:5244` |
| `OL_USERNAME` / `OL_PASSWORD` | OpenList 账号密码（后端用它们登录、上传 WebDAV） |
| `OL_MUSIC_DIR` | 音乐目录，必须与 OpenList 里的真实路径一致 |
| `NCM_URL` | 网易云 API 容器内地址，默认 `http://ncm-api:3000`，一般不改 |
| `UNM_ENABLED` | 是否启用 VIP/灰歌解锁，`true` / `false` |
| `UNM_URL` | 解锁服务容器内地址，默认 `http://unm-utils:3000` |

可选（写在 `.env` 里也能生效）：

- `UNM_SOURCES`：音源尝试顺序，默认 `msls,oi,byfuns,unm,qijieya,ddyr,gdmusic`
- `UNM_SOURCE_TIMEOUT`：单个音源超时秒数，默认 `6`

`.env` 含真实凭据，已被 gitignore，不要提交。

### 3. 启动

```bash
docker compose up -d
docker compose logs -f fm-backend
```

启动日志里应该看到：OpenList 登录成功、`[index] 启动扫描完成，共 N 首`、`FM Backend 已启动，监听 :5000`。

`ncm-api` 启动时打印 `xeapi public key is missing` / `Z_BUF_ERROR` 是启动期噪音，后面 banner 照打、接口照常，不是故障。

### 4. 验证

```bash
curl -s http://127.0.0.1:3000/health
```

返回形如：

```json
{"logged_in": false, "user_name": "", "ol_ok": true, "indexed": true, "client_id": "default"}
```

- `ol_ok=true`：OpenList 已连上
- `indexed=true`：曲库索引已建好
- `logged_in=false` 此时正常 —— 网易云登录在 App 里扫码完成

对外只开 `3000` 一个端口。局域网直接用；要在公网用，走 Cloudflare Tunnel / frp 之类隧道挂 `3000`，App 里改个地址就行，代码零改动（不要试图把这个项目搬到 Workers 之类无容器平台，两个上游是 Docker 容器）。

---

## 二、客户端安装与首次配置

### 1. 下载 APK

打开 [Releases](https://github.com/wang25669/fm-cmcc/releases)，在最新一条的 **Assets** 里下 `FMPlayer-vX.X.X.apk`。

- 标 `Pre-release` 的是每次 push 自动构建的，不标的是打过 tag 的正式版。
- CI 使用仓库内固定的 debug keystore 签名，同一包名可以**覆盖升级，不用先卸载**。自己用 Android Studio 另签的包才会报"签名不符"。

### 2. 安装

车机上允许"安装未知来源应用"，装 APK 即可。首次打开如果没填过后端地址，会自动跳到设置页。

### 3. 配置后端 + 登录网易云（只在设置页做一次）

1. 填**后端服务地址**：`http://你的服务器IP:3000`（公网隧道就填隧道域名）
2. 点**测试连接**：应显示"后端连接正常 / OpenList 已连接 / 歌曲索引 已建立"
3. 点**获取二维码**，用网易云音乐 App 扫码 → 手机上确认登录 → App 内显示"已登录（昵称）"
4. 点**保存**，返回主界面

登录态存在服务端 `/data/users.json`（docker volume `fm_data`），重启容器、重装 App 都不用重新扫码；只有 Cookie 过期（约一两个月）才需要再扫一次。

### 4. 开始播放

主界面点播放键即可。第一次取歌要等几秒：后端要下载整首（≤60s）+ 上传网盘（≤120s）+ 取直链，之后靠预取就快了。

---

## 三、日常使用

**主界面**

- **模式药丸**：`默认` / `探索` / `开车` / `离线`，点了立即切换并写回服务端，车机和手机读到的是同一份状态。
- **喜欢（红心）**：把当前歌加进网易云"我喜欢的音乐"歌单。按钮是本地开关语义 —— 默认灰心，点一次变红并回传，再点取消（见下面「已知行为」）。
- **不感兴趣（垃圾桶）**：把当前歌移出私人 FM 推荐流并降权同类，同时**立刻跳下一首**，不等回传结果。
- **播放列表**：本次启动以来播过的歌，**最新的在最上面**，左边的序号是真实播放顺序（最早那首 = 1）。点任意一行重播。
- **进度条只显示、不能拖**：老车机上拖拽交互不可靠，这里有意做成只读。

**设置页**

- **推荐模式**（9 种细分）：纯音乐 / 节奏 / 伤感 / 慢摇 DJ / 民谣 / 爵士 / 华语 / 欧美 / 粤语。细分模式在上游异常时会自动回退到默认模式，保证不断流。
- 另有 `FAMILIAR`（熟悉）模式只能通过接口设置，App 界面未提供入口。

**离线模式**

- 只播本地缓存里已有的歌（就是之前在线听过、被下载到缓存目录的那些），共 500MB 上限。
- 缓存目录是 `getExternalCacheDir()/songs/`，系统空间紧张时可能被回收，不是永久存储。
- 进离线模式后不再请求后端，断网可用。

**日志**

- `/mnt/sdcard/fmplayer_debug.txt`（写不了则退到应用外部目录），崩溃栈单独写 `fmplayer_crash.txt`。不自动轮转，实测一次 7 分钟播放约 20KB。

**已知行为（有意为之，不是 bug）**

- 红心图标默认灰，不与网易云真实状态同步（同步要拉整个喜欢列表，太重）。副作用：一首以前红心过的歌显示灰心，你再点一下会把它取消红心。
- 切歌后 `liked` 状态重置，同一首歌内才会发生"取消红心"。

---

## 四、后端接口（二次开发 / 调试用）

所有接口都可带 `X-Client-Id` 请求头（App 用自动生成的 UUID），不带则视为 `default` 用户。

- `GET /health` — `logged_in` / `user_name` / `ol_ok` / `indexed` / `client_id`
- `GET /next` — 取下一条私人 FM：返回 `{id,title,artist,album,filename,stream_url}`；内部会预取下一首
- `POST /played` — `{id,seconds}`，回传播放记录（scrobble）
- `POST /like` — `{id,like:true|false}`，红心 / 取消红心
- `POST /trash` — `{id}`，移出私人 FM 推荐流
- `GET /mode` / `POST /mode` — 读 / 写该客户端的推荐模式（GET 还返回全部可选值）
- `GET /play?filename=&id=` — 为历史歌曲现场取新鲜直链
- `POST /songs/batch_info` — `{ids:[...]}`，批量补元数据（云盘文件名解析 + 网易云 `/song/detail`）
- `GET /auth/qr` — 取网易云登录二维码（`key` + base64 `qr_image`）
- `GET /auth/check?key=` — 轮询扫码结果，`803` 表示登录成功并落盘 Cookie

几条硬约束：

- **直链绝不提前生成**。移动云盘直链有时效，只在真正返回给客户端的那次请求里现取；预取只缓存歌曲和文件名。
- **`/next` 是昂贵的同步调用**，未命中时全链路几十秒，客户端读超时设到 240 秒。别写轮询。
- **文件名只有一种合法格式**：`歌手-歌名-ID.mp3`（识别正则 `-(\d+)\.mp3$`）。手动往网盘丢歌必须按这个格式命名，否则会被当成"云盘上没有"，重新下载重新上传。
- 上游路由以实测为准：本项目用的 Enhanced fork 里垃圾桶是 `/fm_trash`，Binaryify 文档写的 `/personal_fm/trash` 在这个镜像上 404。

---

## 五、数据与持久化

- `fm_data` 卷 → `/data/users.json`：以 Client ID 为主键，保存每台设备的网易云 Cookie、推荐模式、昵称。
- 首次启动若发现旧版单用户文件（`/data/ncm_cookie.txt`、`/data/fm_mode.txt`），会自动迁移成 `default` 用户。
- 网盘曲库全局共享，与用户无关；索引在启动时扫一次，之后增量更新。
- 想换号：设置页重新扫码即可，该客户端的预取槽位会被清空，避免新旧账号串味。

---

## 六、更新与维护

后端只改了 `fm_backend.py`（它是 volume 挂载的，不打包进镜像）：

```bash
git pull
docker compose restart fm-backend
docker compose logs -f fm-backend
```

改了 `docker-compose.yml` 或 `.env`：

```bash
docker compose up -d
```

如果你是从别处手动下载 `fm_backend.py` 覆盖（不 git pull），下载后务必核对内容，别只看命令没报错：

```bash
md5sum fm_backend.py            # 与源站文件对比
grep -c fm_mode fm_backend.py   # 特征串必须命中
docker compose restart fm-backend
curl -s http://127.0.0.1:3000/mode   # 404 = 还是旧文件
```

---

## 七、常见问题

**Q: 按"下一首"要等十几秒？**
A: 缓存未命中时要走完整链路：网易云下载（约 10MB）→ 上传网盘 → 取直链，网络差时 10-20 秒。预取机制只在"第一首"和"连按下一首快过预下载"时失效。

**Q: VIP / 无版权歌放不出来？**
A: 先确认 `UNM_ENABLED=true`。后端会按 `UNM_SOURCES` 逐个音源尝试，单个音源卡死会超时换下一个。想看谁在拖后腿，看 `docker compose logs fm-backend` 里的 `[unm] ... via <音源>` 和超时行。

**Q: 一直提示"网易云尚未登录" / 取歌 502？**
A: Cookie 过期了（`MUSIC_U` 约一两个月失效）。设置页重新扫码即可。判断登录态用 `curl .../health` 的 `logged_in`。

**Q: 装了新版提示"签名不符"？**
A: 你装的不是本仓库 CI 出的包。CI 用仓库内固定 keystore 签名，从 Releases 下载同包名可直接覆盖升级。

**Q: 播放卡住 / 自动跳歌？**
A: 跳歌是"文件完整但本机解不出来"的处理逻辑，连续 3 首失败会停下等人工。把 `fmplayer_debug.txt` 拿出来看：`onDownloadFailed` 是直链问题（会自动重取，最多 3 次，不跳歌），`onPlaybackFailed` 才是解码问题。

**Q: 为什么不放专辑封面？**
A: 车机 API 17 用系统 HTTP 栈拉 `https://p1.music.126.net/...` 大概率 TLS 握手失败，且纯文本零绘制开销。别加回来。

**Q: 为什么不用 ExoPlayer？**
A: 这台车机的 `OMX.mtk.audio.decoder.mpeg` 不发 `INFO_OUTPUT_FORMAT_CHANGED`，ExoPlayer 的 `audioSink.configure()` 从不被调用，直接 NPE。唯一引擎是"OkHttp 整首下载 + 系统 MediaPlayer 播本地文件"。

**Q: 日志会占满卡吗？**
A: 一次 7 分钟播放约 20KB，满负荷一个月约 120MB。缓存另算，硬上限 500MB。

---

## 八、项目结构

```
.
├── fm_backend.py            # 后端：Flask 单文件，多用户隔离 / 并发防重 / 模式路由
├── docker-compose.yml       # ncm-api + unm-utils + fm-backend
├── .env.example             # 配置模板（复制为 .env）
├── keystore/debug.keystore  # CI 固定签名密钥（保证可覆盖升级）
├── FMPlayer/                # Android 客户端（Java，minSdk 16）
│   └── app/src/main/java/com/fmplayer/
│       ├── service/MusicService.java    # 控制核心：前台 Service + MediaSession
│       ├── player/                      # LocalFilePlayer / SongCache / LocalSongStore
│       ├── network/                     # OkHttp + TLS 适配 + 体检 + 日志
│       └── preferences/                 # 后端地址 / Client ID / 离线态
└── .github/workflows/       # push 即编译出包并发 Release
```

## 九、开发与出包

- 编译需要 **JDK 17 + Android SDK**（AGP 8.7.3）。本仓库没有测试、没有 lint 配置。
- 不想配环境就直接推 GitHub：push 到 `main` 触发 CI，约 1 分钟出包并发 Release。
- 版本号由 CI 改写：`versionCode = git rev-list --count HEAD`，`versionName` 取 tag（无 tag 时 `0.0.<commitCount>`）。`build.gradle` 里那两行必须保持"关键字 + 空格 + 值"的单行写法，否则 `sed` 静默替换失败。
- 改 Android 代码时注意：横竖屏两套布局的控件 id 必须完全一致；不要加 multidex；依赖版本钉死（`okhttp 3.12.13`、`androidx.media 1.4.3`）；UI 只用 API 16/17 验证过的写法。
- 代码注释、UI 文案、日志、提交信息全部中文。

## 许可

MIT（历史 README 声明的许可方式；仓库目前未附 LICENSE 文件）。

## 鸣谢

- [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi) / [api-enhanced](https://github.com/neteasecloudmusicapienhanced/api-enhanced) — 网易云 API
- [UnblockNeteaseMusic](https://github.com/UnblockNeteaseMusic/server) — 灰色 / VIP 歌曲解锁
- [OpenList](https://github.com/OpenListTeam/OpenList) — 多网盘 WebDAV 与直链
- OkHttp — 老设备 TLS 适配的基础
