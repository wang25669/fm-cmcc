#!/usr/bin/env python3
"""
FM Backend — 统一后端服务（支持多客户端多用户、共享云盘并发防重、细分场景曲风模式）

核心特性：
  1. 多客户端多用户隔离：每个客户端分配独立 Client ID，绑定独立的网易云 Cookie 与推荐模式，
     拥有完全独立的后台预取池，彻底避免多用户切歌相互串味。
  2. 共享云盘与单曲 Single-flight 并发防重：
     OpenList/移动云盘音乐目录全局共享；当多个客户端同时遇到一首云盘未缓存的新歌时，
     按歌曲 ID 互斥锁（Single-flight + Double-checked locking）保证仅下载上传一次，并发端直接复用。
  3. 细分场景/曲风模式支持：
     支持基础模式（DEFAULT、EXPLORE、COMMUTE、FAMILIAR）与 9 种专属细分场景曲风
     （FOCUS、EXERCISE、NIGHT_EMO、MANYAO、FOLK、JAZZ、CHINESE、ENGLISH、YUEYU）。
     细分模式若在上游请求异常或无推荐时，自动优雅降级回退至 DEFAULT 保证播放不中断。
  4. 绝不提前生成直链：
     移动云盘直链有时效性，永远在 /next 或 /play 返回给客户端的那一刻现场获取。
"""

import os, re, time, json, threading, requests
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
UNM_SOURCES  = [s.strip() for s in os.environ.get(
    "UNM_SOURCES",
    "gdmusic,msls,byfuns,unm,bikonoo,qijieya,qijieyaPlus"
).split(",") if s.strip()]
UNM_SOURCE_TIMEOUT = float(os.environ.get("UNM_SOURCE_TIMEOUT", "6"))

USERS_FILE   = "/data/users.json"
COOKIE_FILE  = "/data/ncm_cookie.txt"  # 旧版兼容读取
MODE_FILE    = "/data/fm_mode.txt"     # 旧版兼容读取
MAX_CANDIDATES_PER_NEXT = 5            # 一次/next最多尝试候选歌曲数

# ── 推荐模式定义 ──────────────────────────────────────────────────────────
# 基础模式
BASE_FM_MODES = ("DEFAULT", "FAMILIAR", "EXPLORE", "COMMUTE", "SCENE_RCMD")
# 设置页细分曲风/场景模式（9种）
SCENE_FM_MODES = (
    "FOCUS",      # 纯音乐
    "EXERCISE",   # 节奏
    "NIGHT_EMO",  # 伤感
    "MANYAO",     # 慢摇DJ
    "FOLK",       # 民谣
    "JAZZ",       # 爵士
    "CHINESE",    # 华语
    "ENGLISH",    # 欧美
    "YUEYU",      # 粤语
)
FM_MODES = BASE_FM_MODES + SCENE_FM_MODES
DEFAULT_FM_MODE = "DEFAULT"

NCM_HEADERS  = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://music.163.com/",
}

# ── 多用户管理与持久化 ────────────────────────────────────────────────────
#
# 数据保存在 /data/users.json 中，以 client_id（UUID）为主键：
# {
#   "uuid-xxx": {
#     "cookie": "...",
#     "mode": "MANYAO",
#     "user_name": "张三",
#     "updated_at": 1729330000.0
#   }
# }
_users      = {}
_users_lock = threading.Lock()
_ol_token   = ""   # OpenList JWT（全局共享）

def load_users():
    global _users
    with _users_lock:
        if os.path.exists(USERS_FILE):
            try:
                with open(USERS_FILE, "r", encoding="utf-8") as f:
                    _users = json.load(f)
                print(f"[init] 已加载 {len(_users)} 个用户信息", flush=True)
                return
            except Exception as e:
                print(f"[init] 读取 users.json 失败: {e}", flush=True)

        # 首次启动或平滑迁移：若无 users.json 但存在旧版 cookie，则迁移为 default 用户
        migrated_cookie = ""
        migrated_mode   = DEFAULT_FM_MODE
        if os.path.exists(COOKIE_FILE):
            try:
                with open(COOKIE_FILE, "r", encoding="utf-8") as f:
                    migrated_cookie = f.read().strip()
            except Exception:
                pass
        if os.path.exists(MODE_FILE):
            try:
                with open(MODE_FILE, "r", encoding="utf-8") as f:
                    v = f.read().strip().upper()
                    if v in FM_MODES:
                        migrated_mode = v
            except Exception:
                pass

        if migrated_cookie:
            _users["default"] = {
                "cookie":     migrated_cookie,
                "mode":       migrated_mode,
                "user_name":  "",
                "updated_at": time.time(),
            }
            save_users_locked()
            print("[init] 已将旧版单用户凭据平滑迁移为 default 用户", flush=True)

def save_users_locked():
    os.makedirs(os.path.dirname(USERS_FILE), exist_ok=True)
    tmp = USERS_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(_users, f, ensure_ascii=False, indent=2)
    os.replace(tmp, USERS_FILE)

def get_client_id(req):
    """从请求头 X-Client-Id、URL 参数或 JSON Body 中提取客户端标识，缺省回退到 default"""
    cid = req.headers.get("X-Client-Id")
    if not cid:
        cid = req.args.get("client_id")
    if not cid:
        try:
            body = req.get_json(silent=True)
            if isinstance(body, dict):
                cid = body.get("client_id")
        except Exception:
            pass
    cid = str(cid).strip() if cid else ""
    return cid or "default"

def get_user_cookie(client_id):
    with _users_lock:
        u = _users.get(client_id)
        if u and u.get("cookie"):
            return u["cookie"]
        # 向后兼容：若特定 client_id 尚无凭据但存在 default 用户，则回退尝试 default
        if client_id != "default" and "default" in _users:
            return _users["default"].get("cookie", "")
        return ""

def get_user_mode(client_id):
    with _users_lock:
        u = _users.get(client_id)
        if u and u.get("mode"):
            return u["mode"]
        if client_id != "default" and "default" in _users:
            return _users["default"].get("mode", DEFAULT_FM_MODE)
        return DEFAULT_FM_MODE

def get_user_name(client_id):
    with _users_lock:
        u = _users.get(client_id)
        if u and u.get("user_name"):
            return u["user_name"]
        if client_id != "default" and "default" in _users:
            return _users["default"].get("user_name", "")
        return ""

def save_user_cookie(client_id, cookie, user_name=""):
    with _users_lock:
        u = _users.get(client_id, {})
        u["cookie"] = cookie
        if user_name:
            u["user_name"] = user_name
        if "mode" not in u:
            u["mode"] = DEFAULT_FM_MODE
        u["updated_at"] = time.time()
        _users[client_id] = u
        save_users_locked()
    print(f"[auth] 用户 {client_id} Cookie 已保存 (用户名: {user_name})", flush=True)

def save_user_mode(client_id, mode):
    with _users_lock:
        u = _users.get(client_id, {})
        u["mode"] = mode
        u["updated_at"] = time.time()
        _users[client_id] = u
        save_users_locked()
    print(f"[mode] 用户 {client_id} 模式已保存: {mode}", flush=True)

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
        print(f"[ol] token 失效（{last or 'HTTP 401'}），重新登录", flush=True)
        ol_login()
    raise RuntimeError(f"OpenList 鉴权连续失败: {last or '401'}")

def ol_get_url(filename):
    """
    实时获取移动云盘直链。
    每次调用都是新鲜的一次性请求，绝不缓存，绝不提前生成。
    """
    d = ol_post("/api/fs/link", {"path": f"{OL_DIR}/{filename}", "password": ""})
    if d.get("code") != 200:
        raise RuntimeError(f"获取直链失败: {d.get('message')}")
    return d["data"]["url"]

# ── ID 索引：共享云盘文件索引 ──────────────────────────────────────────────

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
    with _index_lock:
        if _index_built:
            return _index.get(song_id)

    build_index()

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

def ncm_get(path, cookie="", **params):
    if cookie:
        params["cookie"] = cookie
    params["timestamp"]  = int(time.time() * 1000)
    r = requests.get(f"{NCM_URL}{path}", params=params, timeout=20)
    r.raise_for_status()
    return r.json()

def ncm_song_url(song_id, cookie=""):
    data = ncm_get("/song/url/v1", cookie=cookie, id=song_id, level="standard")
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

def ncm_fetch_fm(client_id):
    """
    拉取指定客户端私人FM候选歌曲（1-3首）。
    按 client_id 隔离各自的 Cookie 与推荐模式。
    细分模式（如 MANYAO、FOLK 等）走 /personal/fm/mode 带 SCENE_RCMD 与 submode；
    若上游异常则自动优雅降级回退至 DEFAULT 默认私人FM，保证播放不断流。
    """
    cookie = get_user_cookie(client_id)
    if not cookie:
        raise RuntimeError("网易云尚未登录")

    mode = get_user_mode(client_id)

    data = None
    if mode == "DEFAULT":
        data = ncm_get("/personal_fm", cookie=cookie)
    elif mode in ("COMMUTE", "SCENE_RCMD"):
        data = ncm_get("/personal/fm/mode", cookie=cookie, mode="SCENE_RCMD", submode="COMMUTE", limit=3)
    elif mode in ("FAMILIAR", "EXPLORE"):
        data = ncm_get("/personal/fm/mode", cookie=cookie, mode=mode, limit=3)
    elif mode in SCENE_FM_MODES:
        try:
            data = ncm_get("/personal/fm/mode", cookie=cookie, mode="SCENE_RCMD", submode=mode, limit=3)
            if data.get("code") != 200 or not (data.get("data") or []):
                raise RuntimeError(f"上游返回 code={data.get('code')}")
        except Exception as e:
            print(f"[fm] [{client_id}] 细分模式 {mode} 拉取失败（{e}），降级回退至 DEFAULT 模式", flush=True)
            data = ncm_get("/personal_fm", cookie=cookie)
    else:
        data = ncm_get("/personal_fm", cookie=cookie)

    if data.get("code") != 200:
        raise RuntimeError(f"personal_fm({mode}) 失败: {data.get('message')}")

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

# ── 核心：单曲并发防重上传（Single-flight per song ID）───────────────────
#
# 多个客户端同时遇到一首云盘未缓存的新歌时：
# 维护正在处理的歌曲锁字典。第一个线程负责下载与上传，其余并发线程阻塞等待其完成，
# 完成后通过 Double-checked locking 直接复用结果，绝不发生重复下载和覆盖上传。

_upload_locks      = {}
_upload_locks_lock = threading.Lock()

def ensure_uploaded(song, cookie=""):
    """
    确保一首歌已经存在于移动云盘上，返回它的文件名。
    并发安全：同一 song_id 同一时间只有一个线程执行实际搬运。
    """
    sid = song["id"]

    # 1. 快速检查已建立的索引
    filename = find_existing_filename(sid)
    if filename:
        print(f"[match] 已存在: {filename}", flush=True)
        return filename

    # 2. 获取该歌曲维度的互斥单飞锁
    with _upload_locks_lock:
        if sid not in _upload_locks:
            _upload_locks[sid] = threading.Lock()
        song_lock = _upload_locks[sid]

    with song_lock:
        # 3. Double-checked locking：排队等待醒来后再次检查索引，可能前序线程已经上传完毕
        filename = find_existing_filename(sid)
        if filename:
            print(f"[match] 并发复用已上传文件: {filename}", flush=True)
            return filename

        try:
            filename = make_filename(song)
            ncm_url  = ncm_song_url(sid, cookie=cookie)
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
        finally:
            with _upload_locks_lock:
                _upload_locks.pop(sid, None)

def ensure_and_get_url(song, cookie=""):
    """同步处理一首歌：查索引/防重上传 → 现场生成新鲜直链"""
    filename = ensure_uploaded(song, cookie=cookie)
    return {**song, "filename": filename, "stream_url": ol_get_url(filename)}

# ── 多客户端隔离后台预取 ──────────────────────────────────────────────────
#
# 每个 client_id 拥有独立预取槽位，互不冲突，彻底防串味。
# 切换模式或重新扫码时立即清空该客户端的预取槽位。

class UserPrefetchState:
    def __init__(self):
        self.lock           = threading.Lock()
        self.prefetch       = None     # {"song": {...}, "filename": "..."}
        self.prefetching    = False
        self.last_served_id = None

_user_prefetches      = {}
_user_prefetches_lock = threading.Lock()

def get_user_prefetch_state(client_id):
    with _user_prefetches_lock:
        if client_id not in _user_prefetches:
            _user_prefetches[client_id] = UserPrefetchState()
        return _user_prefetches[client_id]

def clear_prefetched(client_id):
    state = get_user_prefetch_state(client_id)
    with state.lock:
        state.prefetch       = None
        state.last_served_id = None
    print(f"[prefetch] [{client_id}] 预取槽位已清空", flush=True)

def take_prefetched(client_id):
    state = get_user_prefetch_state(client_id)
    with state.lock:
        item, state.prefetch = state.prefetch, None
        last = state.last_served_id
    if not item:
        return None
    if last and item["song"]["id"] == last:
        print(f"[prefetch] [{client_id}] 预取与刚播过的 {item['filename']} 重合，丢弃", flush=True)
        return None
    return item

def _prefetch_worker(client_id, exclude_id):
    state = get_user_prefetch_state(client_id)
    cookie = get_user_cookie(client_id)
    try:
        candidates = ncm_fetch_fm(client_id)
        for song in candidates:
            if song["id"] == exclude_id:
                continue
            try:
                filename = ensure_uploaded(song, cookie=cookie)
            except Exception as e:
                print(f"[prefetch] [{client_id}] 跳过 {song.get('title')}({song['id']}): {e}", flush=True)
                continue
            with state.lock:
                state.prefetch = {"song": song, "filename": filename}
            print(f"[prefetch] [{client_id}] 预取就绪: {filename}", flush=True)
            return
        print(f"[prefetch] [{client_id}] 本轮无可用候选，放弃预取", flush=True)
    except Exception as e:
        print(f"[prefetch] [{client_id}] 预取失败: {e}", flush=True)
    finally:
        with state.lock:
            state.prefetching = False

def kick_prefetch(client_id, served_id):
    state = get_user_prefetch_state(client_id)
    with state.lock:
        state.last_served_id = served_id
        if state.prefetching or state.prefetch is not None:
            return
        state.prefetching = True
    threading.Thread(target=_prefetch_worker, args=(client_id, served_id), daemon=True).start()

# ── API 路由 ─────────────────────────────────────────────────────────────

@app.route("/health")
def health():
    cid    = get_client_id(request)
    cookie = get_user_cookie(cid)
    return jsonify({
        "logged_in": bool(cookie),
        "user_name": get_user_name(cid),
        "ol_ok":     bool(_ol_token),
        "indexed":   _index_built,
        "client_id": cid,
    })

@app.route("/next")
def next_song():
    cid    = get_client_id(request)
    cookie = get_user_cookie(cid)
    if not cookie:
        return jsonify({"error": "网易云尚未登录"}), 401

    # 快路径：吃掉属于当前客户端的预取结果
    hit = take_prefetched(cid)
    if hit:
        try:
            entry = {**hit["song"], "filename": hit["filename"],
                     "stream_url": ol_get_url(hit["filename"])}
            print(f"[next] [{cid}] 命中预取: {hit['filename']}", flush=True)
            kick_prefetch(cid, hit["song"]["id"])
            return jsonify(entry)
        except Exception as e:
            print(f"[next] [{cid}] 预取项取直链失败，回退同步流程: {e}", flush=True)

    last_error = "未知错误"
    tried = 0

    while tried < MAX_CANDIDATES_PER_NEXT:
        try:
            candidates = ncm_fetch_fm(cid)
        except Exception as e:
            return jsonify({"error": f"获取歌单失败: {e}"}), 502

        if not candidates:
            return jsonify({"error": "私人FM暂无歌曲"}), 504

        for song in candidates:
            tried += 1
            if tried > MAX_CANDIDATES_PER_NEXT:
                break
            try:
                entry = ensure_and_get_url(song, cookie=cookie)
                kick_prefetch(cid, song["id"])
                return jsonify(entry)
            except Exception as e:
                last_error = str(e)
                print(f"[next] [{cid}] 跳过 {song.get('title')}({song['id']}): {e}", flush=True)
                continue

    return jsonify({"error": f"连续{tried}首都无法播放，最后错误: {last_error}"}), 502

@app.route("/played", methods=["POST"])
def played():
    cid    = get_client_id(request)
    cookie = get_user_cookie(cid)
    d      = request.get_json(force=True) or {}
    sid    = d.get("id", "")
    sec    = int(d.get("seconds", 0))
    if sid and cookie:
        try:
            ncm_get("/scrobble", cookie=cookie, id=sid, sourceid=sid, time=sec)
        except Exception:
            pass
    return jsonify({"ok": True})

@app.route("/like", methods=["POST"])
def like():
    cid    = get_client_id(request)
    cookie = get_user_cookie(cid)
    d      = request.get_json(force=True) or {}
    sid    = str(d.get("id", "")).strip()
    if not sid:
        return jsonify({"ok": False, "error": "id 不能为空"}), 400
    if not cookie:
        return jsonify({"ok": False, "error": "网易云尚未登录"}), 401

    flag     = d.get("like", True)
    like_str = "false" if (flag is False or str(flag).lower() == "false") else "true"
    try:
        r      = ncm_get("/like", cookie=cookie, id=sid, like=like_str)
        code   = r.get("code", -1)
        ok     = code == 200
        action = "红心" if like_str == "true" else "取消红心"
        print(f"[like] [{cid}] {action} {sid} -> code={code}", flush=True)
        return jsonify({"ok": ok, "code": code, "like": like_str == "true"})
    except Exception as e:
        print(f"[like] [{cid}] {sid} 失败: {e}", flush=True)
        return jsonify({"ok": False, "error": str(e)}), 502

@app.route("/trash", methods=["POST"])
def trash():
    cid    = get_client_id(request)
    cookie = get_user_cookie(cid)
    d      = request.get_json(force=True) or {}
    sid    = str(d.get("id", "")).strip()
    if not sid:
        return jsonify({"ok": False, "error": "id 不能为空"}), 400
    if not cookie:
        return jsonify({"ok": False, "error": "网易云尚未登录"}), 401

    try:
        r    = ncm_get("/fm_trash", cookie=cookie, id=sid)
        code = r.get("code", -1)
        ok   = code == 200
        print(f"[trash] [{cid}] 垃圾桶 {sid} -> code={code}", flush=True)
        return jsonify({"ok": ok, "code": code})
    except Exception as e:
        print(f"[trash] [{cid}] {sid} 失败: {e}", flush=True)
        return jsonify({"ok": False, "error": str(e)}), 502

@app.route("/mode", methods=["GET", "POST"])
def fm_mode():
    cid     = get_client_id(request)
    current = get_user_mode(cid)
    if request.method == "GET":
        return jsonify({"ok": True, "mode": current, "modes": list(FM_MODES)})

    d    = request.get_json(force=True) or {}
    mode = str(d.get("mode", "")).strip().upper()
    if mode not in FM_MODES:
        return jsonify({"ok": False,
                        "error": f"mode 必须是 {'/'.join(FM_MODES)} 之一"}), 400
    save_user_mode(cid, mode)
    # 切换模式后立刻清空当前客户端预取槽位，确保下一次 /next 100% 走新模式
    clear_prefetched(cid)
    print(f"[mode] [{cid}] 私人FM模式 -> {mode}（预取已清空）", flush=True)
    return jsonify({"ok": True, "mode": mode})

def parse_filename_meta(filename, song_id):
    name = filename
    if name.lower().endswith(".mp3"):
        name = name[:-4]
    suffix = f"-{song_id}"
    if name.endswith(suffix):
        name = name[:-len(suffix)]
    else:
        name = re.sub(r'-\d+$', '', name)
    name = name.strip()
    if "-" in name:
        parts = name.split("-", 1)
        return {
            "id": str(song_id),
            "artist": parts[0].strip(),
            "title": parts[1].strip(),
            "album": ""
        }
    return {
        "id": str(song_id),
        "artist": "",
        "title": name.strip(),
        "album": ""
    }

@app.route("/songs/batch_info", methods=["POST"])
def songs_batch_info():
    cid    = get_client_id(request)
    cookie = get_user_cookie(cid)
    d      = request.get_json(force=True) or {}
    ids    = d.get("ids", [])
    if not isinstance(ids, list):
        return jsonify({"ok": False, "error": "ids 必须是列表"}), 400

    seen = set()
    clean_ids = []
    for sid in ids:
        s = str(sid).strip()
        if s and s not in seen:
            seen.add(s)
            clean_ids.append(s)

    result_map = {}

    # 1. 先用云盘索引文件名秒级解析
    for sid in clean_ids:
        fname = find_existing_filename(sid)
        if fname:
            result_map[sid] = parse_filename_meta(fname, sid)

    # 2. 尝试从网易云 /song/detail 批量拉取权威元数据
    if cookie and clean_ids:
        batch_size = 50
        for i in range(0, len(clean_ids), batch_size):
            chunk = clean_ids[i:i + batch_size]
            try:
                data = ncm_get("/song/detail", cookie=cookie, ids=",".join(chunk))
                if data.get("code") == 200:
                    for item in data.get("songs") or []:
                        sid = str(item.get("id"))
                        name = item.get("name", "")
                        ars = item.get("ar") or item.get("artists") or []
                        ar_name = ars[0].get("name", "") if ars else ""
                        al = item.get("al") or item.get("album") or {}
                        al_name = al.get("name", "")
                        result_map[sid] = {
                            "id": sid,
                            "title": name,
                            "artist": ar_name,
                            "album": al_name
                        }
            except Exception as e:
                print(f"[batch_info] [{cid}] ncm /song/detail 失败: {e}", flush=True)

    songs = []
    for sid in clean_ids:
        if sid in result_map:
            songs.append(result_map[sid])
        else:
            songs.append({"id": sid, "title": f"本地歌曲 {sid}", "artist": "", "album": ""})

    return jsonify({"ok": True, "songs": songs})

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
    cid    = get_client_id(request)
    key    = request.args.get("key", "")
    ts     = int(time.time() * 1000)
    r      = requests.get(f"{NCM_URL}/login/qr/check",
                          params={"key": key, "timestamp": ts}, timeout=10)
    data   = r.json()
    status = data.get("code", -1)
    user_name = ""
    if status == 803:
        cookie = data.get("cookie", "")
        if cookie:
            # 抓取用户昵称
            try:
                st = requests.get(f"{NCM_URL}/login/status",
                                  params={"cookie": cookie, "timestamp": int(time.time() * 1000)},
                                  timeout=10).json()
                profile = (st.get("data") or {}).get("profile") or {}
                user_name = profile.get("nickname", "")
            except Exception as e:
                print(f"[auth] 获取登录昵称失败: {e}", flush=True)
            save_user_cookie(cid, cookie, user_name)
            # 无论换号还是重新扫码，扫码成功后立即清空该客户端现存预取，彻底防串味
            clear_prefetched(cid)
    return jsonify({"status": status, "user_name": user_name or get_user_name(cid)})

# ── 启动 ─────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    os.makedirs("/data", exist_ok=True)
    load_users()
    try:
        ol_login()
        build_index()
    except Exception as e:
        print(f"[init] 初始化失败: {e}", flush=True)
    print("[init] FM Backend 已启动，监听 :5000", flush=True)
    app.run(host="0.0.0.0", port=5000, threaded=True)
