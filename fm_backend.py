#!/usr/bin/env python3
"""
FM Backend — 统一后端服务（简化版：不维护预取队列，按需实时处理）

流程完全同步、透明：
  客户端 /next
    → 找网易云私人FM下一首候选
    → 查移动云盘上是否已有这首歌（内存索引，零网络开销）
    → 有：直接现拿一条新鲜直链返回
    → 没有：从网易云下载 → 上传移动云盘 → 现拿新鲜直链返回
    → 直链永远是这次请求里现生成的，不存在"提前生成后过期"的问题

Android 客户端只需配置本服务地址，调用 /next 获取歌曲信息并播放。
"""

import os, re, time, threading, requests
from flask import Flask, request, jsonify

app = Flask(__name__)

# ── 从环境变量读取配置 ────────────────────────────────────────────────────
NCM_URL      = os.environ.get("NCM_URL",      "http://ncm-api:3000")
OL_URL       = os.environ.get("OL_URL",       "http://openlist:5244")
OL_USER      = os.environ.get("OL_USERNAME",  "admin")
OL_PASS      = os.environ.get("OL_PASSWORD",  "")
OL_DIR       = os.environ.get("OL_MUSIC_DIR", "/移动云盘/music").rstrip("/")
UNM_URL      = os.environ.get("UNM_URL",      "")
UNM_ENABLED  = os.environ.get("UNM_ENABLED",  "false").lower() == "true"
# UNM 音源优先级：按实测稳定性+速度排序，稳的快的排前面（gdmusic/msls 0.5s，
# byfuns/unm 2s 级）；bikonoo/qijieya/qijieyaPlus 实测常卡死，压到最后当兜底。
# 可用 UNM_SOURCES 环境变量覆盖（逗号分隔）。
UNM_SOURCES  = [s.strip() for s in os.environ.get(
    "UNM_SOURCES",
    "gdmusic,msls,byfuns,unm,bikonoo,qijieya,qijieyaPlus"
).split(",") if s.strip()]
# 单个音源的超时（秒）。unm-utils 各音源模块内部没设 axios 超时，全靠这里兜底。
UNM_SOURCE_TIMEOUT = float(os.environ.get("UNM_SOURCE_TIMEOUT", "6"))
COOKIE_FILE  = "/data/ncm_cookie.txt"
MAX_CANDIDATES_PER_NEXT = 5   # 一次/next最多尝试几个候选歌曲（防止连续遇到无法播放的歌卡死）

NCM_HEADERS  = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://music.163.com/",
}

# ── 全局状态 ──────────────────────────────────────────────────────────────
_cookie       = ""       # 网易云 cookie
_ol_token     = ""       # OpenList JWT

# ── Cookie 持久化 ─────────────────────────────────────────────────────────

def load_cookie():
    global _cookie
    try:
        with open(COOKIE_FILE) as f:
            _cookie = f.read().strip()
        print("[init] 已加载 NCM cookie", flush=True)
    except Exception:
        pass

def save_cookie(cookie):
    global _cookie
    _cookie = cookie
    os.makedirs(os.path.dirname(COOKIE_FILE), exist_ok=True)
    with open(COOKIE_FILE, "w") as f:
        f.write(cookie)
    print("[auth] Cookie 已保存", flush=True)

# ── OpenList 工具 ─────────────────────────────────────────────────────────

def ol_login():
    global _ol_token
    r = requests.post(f"{OL_URL}/api/auth/login",
                      json={"username": OL_USER, "password": OL_PASS}, timeout=15)
    data = r.json()
    if data.get("code") == 200:
        _ol_token = data["data"]["token"]
        print("[ol] 登录成功", flush=True)
    else:
        raise RuntimeError(f"OpenList 登录失败: {data.get('message')}")

def ol_post(path, body):
    global _ol_token
    if not _ol_token:
        ol_login()
    last = None
    for _ in range(2):
        r = requests.post(f"{OL_URL}{path}", json=body,
                          headers={"Authorization": _ol_token}, timeout=15)
        # OpenList 的鉴权失败有两种表现，必须都认：
        #   1) HTTP 401
        #   2) HTTP 200，但 body 里 code==401（token 过期/失效走的是这条！
        #      实测返回 {"code":401,"message":"token is invalidated/expired"}）
        # 早期只判断 status_code==401，导致 token 一过期自动重登录从不触发，
        # 只能靠重启进程续期——这正是"跑几天后 /next 全部 token is expired"的根因。
        if r.status_code != 401:
            try:
                data = r.json()
            except Exception:
                r.raise_for_status()
                raise
            if data.get("code") != 401:
                r.raise_for_status()
                return data
            last = data.get("message")
        # 走到这里 = 鉴权失效，重登录后重试一次
        print(f"[ol] token 失效（{last or 'HTTP 401'}），重新登录", flush=True)
        ol_login()
    raise RuntimeError(f"OpenList 鉴权连续失败: {last or '401'}")

def ol_get_url(filename):
    """
    实时获取移动云盘直链。
    每次调用都是新鲜的一次性请求，绝不缓存，绝不提前生成——
    这是解决"直链提前生成后过期"这个问题的根本方式。
    """
    d = ol_post("/api/fs/link", {"path": f"{OL_DIR}/{filename}", "password": ""})
    if d.get("code") != 200:
        raise RuntimeError(f"获取直链失败: {d.get('message')}")
    return d["data"]["url"]

# ── ID索引：启动时建一次，之后增量更新，不做周期性扫描 ────────────────────
#
# 唯一命名格式：歌手-歌名-ID.mp3
# 手动往云盘丢文件也要按这个格式命名，否则识别不出 ID，会被当成
# "云盘上没有"从而重复下载重复上传。

ID_PATTERN = re.compile(r'-(\d+)\.mp3$', re.IGNORECASE)

def extract_song_id(filename):
    m = ID_PATTERN.search(filename)
    return m.group(1) if m else None

_index       = {}
_index_lock  = threading.Lock()
_index_built = False

def build_index():
    global _index, _index_built
    try:
        d = ol_post("/api/fs/list", {
            "path": OL_DIR, "password": "", "page": 1, "per_page": 0, "refresh": False
        })
        if d.get("code") != 200:
            print(f"[index] 建立索引失败: {d.get('message')}", flush=True)
            return
        new_index = {}
        for item in (d.get("data", {}) or {}).get("content", []) or []:
            name = item.get("name", "")
            if not name.lower().endswith(".mp3"):
                continue
            sid = extract_song_id(name)
            if sid:
                new_index[sid] = name
        with _index_lock:
            _index       = new_index
            _index_built = True
        print(f"[index] 启动扫描完成，共 {len(new_index)} 首", flush=True)
    except Exception as e:
        print(f"[index] 建立索引失败: {e}", flush=True)

def find_existing_filename(song_id):
    """
    查索引。索引没建起来时先补建一次再查。

    原来这里是「没建好就直接返回 None」——如果容器启动那一刻 OpenList
    还没起来（docker compose 里两个服务没有依赖顺序，NAS 上的 OpenList
    更是完全独立的进程），build_index() 会失败并且**永远不会再重试**，
    _index_built 就一直是 False。后果不是"慢一点"，而是每一首歌都判定为
    "云盘上没有"，于是全部重新下载重新上传——云盘上很快堆满同一首歌的
    重复文件，而且每次 /next 都要多花几十秒。
    """
    with _index_lock:
        if _index_built:
            return _index.get(song_id)

    build_index()          # 补建（内部自己捕获异常，失败就还是空索引）

    with _index_lock:
        return _index.get(song_id) if _index_built else None

def register_uploaded(song_id, filename):
    with _index_lock:
        _index[song_id] = filename

def make_filename(song):
    a = re.sub(r'[/\\:*?"<>|]', '', song.get("artist", "")).strip()
    t = re.sub(r'[/\\:*?"<>|]', '', song.get("title",  "")).strip()
    sid  = song["id"]
    name = (f"{a}-" if a else "") + (t or sid) + f"-{sid}"
    return name + ".mp3"

# ── 网易云工具 ────────────────────────────────────────────────────────────

def ncm_get(path, **params):
    params["cookie"]    = _cookie
    params["timestamp"] = int(time.time() * 1000)
    r = requests.get(f"{NCM_URL}{path}", params=params, timeout=20)
    r.raise_for_status()
    return r.json()

def ncm_song_url(song_id):
    data = ncm_get("/song/url/v1", id=song_id, level="standard")
    item = (data.get("data") or [{}])[0]
    url  = item.get("url") or ""

    if not url or url == "null":
        return try_unm(song_id, "无版权")
    if item.get("freeTrialInfo"):
        print(f"[unm] {song_id} 是 VIP 试听，尝试解锁", flush=True)
        return try_unm(song_id, "VIP试听")
    return url

def try_unm(song_id, reason):
    if not UNM_ENABLED or not UNM_URL:
        raise RuntimeError(f"{reason}（UNM 未启用）")
    base = UNM_URL.rstrip("/")
    # 逐个音源用 ?source= 显式指定，每个独立短超时，一个卡住不拖累其他。
    # 不走 unm-utils 默认的「无 source 遍历」——它按文件名顺序(bikonoo 打头)
    # 挨个试，且每个音源模块内部都没给 axios 设超时（axios 默认无限等待）。
    # 只要打头的 bikonoo 这类上游挂了就整体卡死，fm 这边 timeout 一到就
    # 解锁失败，哪怕后面的 gdmusic/msls 秒回也轮不到。实测(2026-08-16)：
    # gdmusic 0.5s / msls 0.5s / byfuns 2s / unm 2.4s 均正常；
    # bikonoo / qijieya / qijieyaPlus 全部卡死 8s+。
    last_err = ""
    for src in UNM_SOURCES:
        try:
            r = requests.get(f"{base}/match",
                             params={"id": song_id, "source": src},
                             timeout=UNM_SOURCE_TIMEOUT)
            url = (r.json().get("data") or {}).get("url", "")
            if url:
                print(f"[unm] {song_id} 解锁成功 via {src}", flush=True)
                return url
            last_err = f"{src} 无音源"
        except Exception as e:
            last_err = f"{src}:{e}"
            print(f"[unm] {song_id} 音源 {src} 失败/超时，换下一个（{e}）", flush=True)
            continue
    raise RuntimeError(f"{reason}（UNM 所有音源均失败，最后：{last_err}）")

def ncm_fetch_fm():
    """拉取私人FM歌单候选（一般返回1-3首）"""
    data = ncm_get("/personal_fm")
    if data.get("code") != 200:
        raise RuntimeError(f"personal_fm 失败: {data.get('message')}")
    songs = []
    for item in data.get("data") or []:
        artists = item.get("artists") or []
        album   = item.get("album") or {}
        songs.append({
            "id":      str(item["id"]),
            "title":   item.get("name", ""),
            "artist":  artists[0].get("name", "") if artists else "",
            "album":   album.get("name", ""),
        })
    return songs

# ── 核心：确保一首歌在移动云盘，返回新鲜直链 ─────────────────────────────

def ensure_uploaded(song):
    """
    确保一首歌已经躺在移动云盘上，返回它的文件名。
    只做"搬运"，**不碰直链**——直链的生成必须留在真正要返回给客户端的那次
    请求里，这是这个项目从头到尾的铁律（提前生成的直链会过期）。
    """
    sid = song["id"]

    filename = find_existing_filename(sid)
    if filename:
        print(f"[match] 已存在: {filename}", flush=True)
        return filename

    filename = make_filename(song)
    ncm_url  = ncm_song_url(sid)
    print(f"[upload] 开始: {filename}", flush=True)

    resp = requests.get(ncm_url, headers=NCM_HEADERS, timeout=60)
    resp.raise_for_status()
    content = resp.content
    print(f"[upload] 下载 {len(content)} bytes", flush=True)

    put = requests.put(
        f"{OL_URL}/dav{OL_DIR}/{filename}",
        data=content,
        auth=(OL_USER, OL_PASS),
        headers={"Content-Type": "audio/mpeg",
                 "Content-Length": str(len(content))},
        timeout=120,
    )
    if put.status_code not in (200, 201, 204):
        raise RuntimeError(f"WebDAV PUT {put.status_code}: {put.text[:100]}")
    print(f"[upload] 完成: {filename}", flush=True)

    register_uploaded(sid, filename)
    return filename

def ensure_and_get_url(song):
    """
    同步处理一首歌：查索引→(下载上传)→现拿新鲜直链。
    整个过程在一次请求里完成，直链绝不提前缓存。
    """
    filename = ensure_uploaded(song)
    # 现拿一条新鲜直链——这是保证不过期的关键
    return {**song, "filename": filename, "stream_url": ol_get_url(filename)}

# ── 后台预取：提前把下一首搬到云盘，但绝不提前生成直链 ────────────────────
#
# /next 的耗时几乎全在"从网易云下载整首歌 + 整首上传移动云盘"这一段，
# 车机上表现为按下一首之后要干等十几秒。
# 所以每次 /next 返回之后，立刻在后台线程里把再下一首也搬上云盘，
# 下次 /next 就只剩"生成一条直链"这一个网络往返，基本是秒回。
#
# 预取里存的是**歌曲元数据 + 文件名**，绝不存 stream_url——
# 直链依然由 /next 在返回前现场调用 ol_get_url() 生成。
# 这样既拿到了提速，又完全不触碰"直链会过期"这个雷。

_prefetch        = None                 # {"song": {...}, "filename": "..."}
_prefetch_lock   = threading.Lock()
_prefetching     = False                # 已经有一个预取线程在跑了，别重复起
_last_served_id  = None                 # 上一次 /next 真正返回给客户端的那首

def take_prefetched():
    """
    取走预取结果（一次性消费）。

    这里必须再查一次"是不是刚放过的那首"：预取线程是在上一次 /next 之前
    就启动的，它拿到的候选列表和后来同步流程实际选中的那首可能撞车，
    不拦一下客户端会连着听到同一首歌两遍。
    """
    global _prefetch
    with _prefetch_lock:
        item, _prefetch = _prefetch, None
        last = _last_served_id
    if not item:
        return None
    if last and item["song"]["id"] == last:
        print(f"[prefetch] 预取的正是刚播过的 {item['filename']}，丢弃", flush=True)
        return None
    return item

def _prefetch_worker(exclude_id):
    global _prefetch, _prefetching
    try:
        candidates = ncm_fetch_fm()
        for song in candidates:
            if song["id"] == exclude_id:
                continue
            try:
                filename = ensure_uploaded(song)
            except Exception as e:
                print(f"[prefetch] 跳过 {song.get('title')}({song['id']}): {e}", flush=True)
                continue
            with _prefetch_lock:
                _prefetch = {"song": song, "filename": filename}
            print(f"[prefetch] 已就绪: {filename}", flush=True)
            return
        print("[prefetch] 本轮候选没有一首能用，放弃", flush=True)
    except Exception as e:
        # 预取纯属锦上添花，失败了下一次 /next 走同步老路即可，绝不能影响主流程
        print(f"[prefetch] 失败: {e}", flush=True)
    finally:
        with _prefetch_lock:
            _prefetching = False

def kick_prefetch(served_id):
    """记下这次真正返回的是哪首，并（在没有正在跑的预取时）起一个新的预取线程。"""
    global _prefetching, _last_served_id
    with _prefetch_lock:
        _last_served_id = served_id
        if _prefetching or _prefetch is not None:
            return          # 已经在跑、或者已经有货了，不用再起
        _prefetching = True
    threading.Thread(target=_prefetch_worker, args=(served_id,), daemon=True).start()

# ── API ──────────────────────────────────────────────────────────────────

@app.route("/health")
def health():
    return jsonify({
        "logged_in": bool(_cookie),
        "ol_ok":     bool(_ol_token),
        "indexed":   _index_built,
    })

@app.route("/next")
def next_song():
    """
    获取下一首可播放歌曲。

    先看后台预取的那首在不在——在的话整首歌早就躺在云盘上了，
    这里只要现生成一条直链就能返回，省掉十几秒的下载+上传。
    没有预取就走同步老路：依次尝试网易云FM候选，遇到无法播放的
    （无版权且无UNM兜底等）自动跳过换下一个，最多尝试
    MAX_CANDIDATES_PER_NEXT 首，全部失败才报错。

    无论走哪条路，返回前都会再起一个后台线程去预取"下下首"。
    """
    if not _cookie:
        return jsonify({"error": "网易云尚未登录"}), 401

    # ── 快路径：吃掉上一轮的预取结果 ──
    hit = take_prefetched()
    if hit:
        try:
            entry = {**hit["song"], "filename": hit["filename"],
                     "stream_url": ol_get_url(hit["filename"])}   # 直链依然是现生成的
            print(f"[next] 命中预取: {hit['filename']}", flush=True)
            kick_prefetch(hit["song"]["id"])
            return jsonify(entry)
        except Exception as e:
            # 只是直链没拿到，歌本身还在云盘上，退回同步老路重来
            print(f"[next] 预取项取直链失败，回退同步流程: {e}", flush=True)

    last_error = "未知错误"
    tried = 0

    while tried < MAX_CANDIDATES_PER_NEXT:
        try:
            candidates = ncm_fetch_fm()
        except Exception as e:
            return jsonify({"error": f"获取歌单失败: {e}"}), 502

        if not candidates:
            return jsonify({"error": "私人FM暂无歌曲"}), 504

        for song in candidates:
            tried += 1
            if tried > MAX_CANDIDATES_PER_NEXT:
                break
            try:
                entry = ensure_and_get_url(song)
                kick_prefetch(song["id"])
                return jsonify(entry)
            except Exception as e:
                last_error = str(e)
                print(f"[next] 跳过 {song.get('title')}({song['id']}): {e}", flush=True)
                continue

    return jsonify({"error": f"连续{tried}首都无法播放，最后错误: {last_error}"}), 502

@app.route("/played", methods=["POST"])
def played():
    d   = request.get_json(force=True) or {}
    sid = d.get("id", "")
    sec = int(d.get("seconds", 0))
    if sid and _cookie:
        try:
            ncm_get("/scrobble", id=sid, sourceid=sid, time=sec)
        except Exception:
            pass
    return jsonify({"ok": True})

# ── 用户偏好回传：把车机上的显式正/负反馈写回网易云 ─────────────────────────
#
# 私人FM的推荐是算法根据行为数据实时调整的。之前只有 /played（scrobble）这一条
# 弱信号——"听完了"充其量算轻微正向。真正能有力调教私人FM的是两个显式信号：
#
#   红心(/like)       强正向：告诉网易云"这类还要多推"
#   垃圾桶(/fm_trash)  强负向：把这首踢出私人FM流，并降权同类，"别再推了"
#
# 两个上游接口地址是实测确认的（不是照搬文档）：
#   moefurina/ncm-api 这个 Enhanced fork 里，垃圾桶的真实路径是 /fm_trash，
#   而不是 Binaryify 原版文档写的 /personal_fm/trash（那个在本 fork 上 404）。
#   源码 module/fm_trash.js -> /api/radio/trash/add，只读 query.id。
#   module/like.js -> /api/radio/like，读 query.id 和 query.like('false'才取消)。

@app.route("/like", methods=["POST"])
def like():
    """
    红心/取消红心当前歌曲。
    body: {"id": "歌曲ID", "like": true|false}   like 省略时默认 true(红心)
    """
    d   = request.get_json(force=True) or {}
    sid = str(d.get("id", "")).strip()
    if not sid:
        return jsonify({"ok": False, "error": "id 不能为空"}), 400
    if not _cookie:
        return jsonify({"ok": False, "error": "网易云尚未登录"}), 401

    # 上游 like.js 判定的是字符串 'false' 才取消，其余一律视为红心。
    flag     = d.get("like", True)
    like_str = "false" if (flag is False or str(flag).lower() == "false") else "true"
    try:
        r    = ncm_get("/like", id=sid, like=like_str)
        code = r.get("code", -1)
        ok   = code == 200
        action = "红心" if like_str == "true" else "取消红心"
        print(f"[like] {action} {sid} -> code={code}", flush=True)
        return jsonify({"ok": ok, "code": code, "like": like_str == "true"})
    except Exception as e:
        print(f"[like] {sid} 失败: {e}", flush=True)
        return jsonify({"ok": False, "error": str(e)}), 502

@app.route("/trash", methods=["POST"])
def trash():
    """
    把当前歌曲丢进私人FM垃圾桶（不感兴趣）。
    body: {"id": "歌曲ID"}
    这是对私人FM最强的负反馈——上游会把这首移出推荐流并降权同类。
    """
    d   = request.get_json(force=True) or {}
    sid = str(d.get("id", "")).strip()
    if not sid:
        return jsonify({"ok": False, "error": "id 不能为空"}), 400
    if not _cookie:
        return jsonify({"ok": False, "error": "网易云尚未登录"}), 401

    try:
        r    = ncm_get("/fm_trash", id=sid)
        code = r.get("code", -1)
        ok   = code == 200
        print(f"[trash] 垃圾桶 {sid} -> code={code}", flush=True)
        return jsonify({"ok": ok, "code": code})
    except Exception as e:
        print(f"[trash] {sid} 失败: {e}", flush=True)
        return jsonify({"ok": False, "error": str(e)}), 502

@app.route("/play")
def play():
    """点播历史歌曲：实时取新鲜直链"""
    filename = request.args.get("filename", "").strip()
    song_id  = request.args.get("id", "").strip()
    if not filename:
        return jsonify({"error": "filename 参数不能为空"}), 400
    try:
        url = ol_get_url(filename)
        return jsonify({"stream_url": url})
    except Exception as e:
        if song_id:
            new_filename = find_existing_filename(song_id)
            if new_filename:
                try:
                    url = ol_get_url(new_filename)
                    return jsonify({"stream_url": url, "filename": new_filename})
                except Exception as e2:
                    return jsonify({"error": str(e2)}), 500
        return jsonify({"error": str(e)}), 500

@app.route("/auth/qr")
def auth_qr():
    ts   = int(time.time() * 1000)
    key  = requests.get(f"{NCM_URL}/login/qr/key",
                        params={"timestamp": ts}, timeout=10).json()["data"]["unikey"]
    img  = requests.get(f"{NCM_URL}/login/qr/create",
                        params={"key": key, "qrimg": "true",
                                "timestamp": ts}, timeout=10).json()["data"]["qrimg"]
    sep  = img.find(",")
    return jsonify({"key": key, "qr_image": img[sep + 1:] if sep >= 0 else img})

@app.route("/auth/check")
def auth_check():
    key    = request.args.get("key", "")
    ts     = int(time.time() * 1000)
    r      = requests.get(f"{NCM_URL}/login/qr/check",
                          params={"key": key, "timestamp": ts}, timeout=10)
    data   = r.json()
    status = data.get("code", -1)
    if status == 803:
        cookie = data.get("cookie", "")
        if cookie:
            save_cookie(cookie)
    return jsonify({"status": status})

# ── 启动 ─────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    os.makedirs("/data", exist_ok=True)
    load_cookie()
    try:
        ol_login()
        build_index()
    except Exception as e:
        print(f"[init] 初始化失败: {e}", flush=True)
    print("[init] FM Backend 已启动，监听 :5000", flush=True)
    app.run(host="0.0.0.0", port=5000, threaded=True)
