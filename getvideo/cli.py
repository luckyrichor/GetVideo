"""命令行入口，用来在没有 App 的阶段验证链路。

    python -m getvideo.cli extract "<分享文本>"
    python -m getvideo.cli list
"""
from __future__ import annotations

import argparse
import logging
import sys
import time

from sqlalchemy import select

from .config import settings
from .db import init_db, session_scope
from .extractors import ExtractorError
from .models import Track
from .pipeline import extract
from .sharelink import LinkError, resolve
from . import credentials, login


def _fmt_size(n: int | None) -> str:
    if not n:
        return "-"
    return f"{n / 1024 / 1024:.2f} MB" if n >= 1024 * 1024 else f"{n / 1024:.0f} KB"


def _fmt_dur(sec: float | None) -> str:
    if not sec:
        return "-"
    return f"{int(sec) // 60}:{int(sec) % 60:02d}"


def cmd_resolve(args) -> int:
    try:
        link = resolve(args.text)
    except LinkError as exc:
        print(f"解析失败: {exc}", file=sys.stderr)
        return 2
    print(f"平台     : {link.platform.value}")
    print(f"视频 id  : {link.video_id}")
    print(f"规范地址 : {link.url}")
    return 0


def cmd_extract(args) -> int:
    init_db()
    try:
        out = extract(args.text, user_id=args.user, force=args.force)
    except LinkError as exc:
        print(f"解析失败: {exc}", file=sys.stderr)
        return 2
    except ExtractorError as exc:
        print(f"提取失败: {exc}", file=sys.stderr)
        return 3

    tag = "（命中已有，未重新下载）" if out.reused else ""
    print(f"提取成功 {tag}")
    print(f"  标题   : {out.title}")
    print(f"  平台   : {out.platform} / {out.video_id}")
    print(f"  时长   : {_fmt_dur(out.duration_sec)}")
    print(f"  大小   : {_fmt_size(out.audio_bytes)}")
    print(f"  文件   : {settings.audio_dir / out.audio_path}")
    print(f"  来源   : {out.source_url}")
    return 0


def cmd_list(args) -> int:
    init_db()
    with session_scope() as s:
        rows = s.execute(
            select(Track).where(Track.deleted == 0).order_by(Track.updated_seq.desc()).limit(args.limit)
        ).scalars().all()
    if not rows:
        print("视频库还是空的。")
        return 0
    print(f"{'seq':>5}  {'平台':<9} {'时长':>6} {'大小':>9}  标题")
    for t in rows:
        print(f"{t.updated_seq:>5}  {t.platform:<9} {_fmt_dur(t.duration_sec):>6} {_fmt_size(t.audio_bytes):>9}  {t.title[:50]}")
    return 0


def cmd_login(args) -> int:
    """扫码登录。前台阻塞轮询直到出结果 —— 二维码只活三分钟，
    丢到后台等于让用户对着一个已经失效的码扫。"""
    init_db()
    try:
        session = login.start(args.user, args.platform)
    except login.LoginNotSupported as exc:
        print(f"{exc}", file=sys.stderr)
        print(f"\n改用：getvideo import-cookies --user {args.user} --platform {args.platform}", file=sys.stderr)
        return 4
    except login.LoginError as exc:
        print(f"发起登录失败: {exc}", file=sys.stderr)
        return 3

    import qrcode

    qr = qrcode.QRCode(border=1)
    qr.add_data(session.qr_url)
    qr.make()
    qr.print_ascii(invert=True)
    print(f"\n用 {args.platform} App 扫上面的二维码（{session.expires_in} 秒内有效）")
    print(f"扫不出来就打开这个地址：{session.qr_url}\n")

    last = None
    deadline = time.time() + session.expires_in + 10
    while time.time() < deadline:
        s = login.poll(session.session_id)
        if s.state.value != last:
            print(f"  [{s.state.value}] {s.message}")
            last = s.state.value
        if s.state is login.QrState.CONFIRMED:
            print(f"\n登录成功，凭证已加密保存到 {args.user}/{args.platform}")
            return 0
        if s.state in (login.QrState.EXPIRED, login.QrState.FAILED):
            print(f"\n登录未完成：{s.message}", file=sys.stderr)
            return 3
        time.sleep(2)

    print("\n等待超时", file=sys.stderr)
    return 3


def cmd_import_cookies(args) -> int:
    init_db()
    text = args.cookies
    if not text:
        print(f"粘贴 {args.platform} 的 cookie，回车结束：")
        print(f"（{login.how_to_export()}）")
        text = sys.stdin.readline()
    try:
        result = login.import_cookies(args.user, args.platform, text)
    except login.LoginError as exc:
        print(f"导入失败: {exc}", file=sys.stderr)
        return 3
    print(f"已保存 {len(result['saved_keys'])} 项: {', '.join(result['saved_keys'])}")
    return 0


def cmd_credentials(args) -> int:
    init_db()
    rows = credentials.list_for(args.user)
    if not rows:
        print(f"{args.user} 还没有任何凭证。")
        return 0
    for r in rows:
        mark = "✓" if r["status"] == "active" else "✗"
        print(f"  {mark} {r['platform']:<10} {r['status']:<14} {r['last_checked_at'] or '-'}")
    return 0


def cmd_logout(args) -> int:
    init_db()
    if credentials.revoke(args.user, args.platform):
        print(f"已删除 {args.user} 的 {args.platform} 凭证")
        return 0
    print("没有这份凭证", file=sys.stderr)
    return 3


def cmd_delete(args) -> int:
    from .db import next_updated_seq
    init_db()
    with session_scope() as s:
        track = s.get(Track, args.track_id)
        if track is None or track.deleted:
            print("视频不存在", file=sys.stderr)
            return 2
        track.deleted = 1
        track.updated_seq = next_updated_seq(s)
    print("已从视频库移除，下次同步会移除手机记录；服务器文件保留。")
    return 0


def cmd_duplicates(args) -> int:
    import json
    from .fingerprint import backfill, duplicates
    if not 1 <= args.limit <= 5000:
        print("limit 必须在 1 到 5000 之间", file=sys.stderr)
        return 2
    init_db()
    if args.backfill:
        backfill(args.limit)
    print(json.dumps(duplicates(limit=args.limit), ensure_ascii=False, indent=2))
    return 0


def cmd_crawl(args) -> int:
    from pathlib import Path
    from .scheduler import run, load_schedule
    try:
        if args.check:
            schedule = load_schedule(Path(args.config))
            print(f"配置有效：{len(schedule.groups)} 个任务组")
            return 0
        init_db()
        run(Path(args.config), once=args.once, force=args.force)
    except (ValueError, OSError) as exc:
        print(f"抓取配置错误: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 0
    return 0


def cmd_classify(args) -> int:
    from .classification import reclassify
    if args.limit < 1:
        print("limit 必须大于 0", file=sys.stderr)
        return 2
    init_db()
    print(f"已更新 {reclassify(limit=args.limit, force=args.force)} 条分类")
    return 0


def cmd_repair_audio_sizes(args) -> int:
    from .audio_integrity import repair_legacy_audio_sizes
    init_db()
    print(f"已修复 {len(repair_legacy_audio_sizes())} 条视频大小记录")
    return 0


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    p = argparse.ArgumentParser(prog="getvideo", description="从 B 站 / 抖音分享链接提取视频")
    sub = p.add_subparsers(dest="cmd", required=True)

    pr = sub.add_parser("resolve", help="只解析链接，不下载")
    pr.add_argument("text", help="分享文本或链接")
    pr.set_defaults(func=cmd_resolve)

    pe = sub.add_parser("extract", help="解析并提取视频")
    pe.add_argument("text", help="分享文本或链接")
    pe.add_argument("--user", help="用户 id（决定用谁的登录凭证）")
    pe.add_argument("--force", action="store_true", help="忽略去重，强制重新下载")
    pe.set_defaults(func=cmd_extract)

    pl = sub.add_parser("list", help="列出视频库")
    pl.add_argument("--limit", type=int, default=30)
    pl.set_defaults(func=cmd_list)

    pdel = sub.add_parser("delete", help="逻辑删除视频并同步删除标记")
    pdel.add_argument("track_id", type=int)
    pdel.set_defaults(func=cmd_delete)

    pd = sub.add_parser("duplicates", help="查找相似视频候选，不删除文件")
    pd.add_argument("--limit", type=int, default=500)
    pd.add_argument("--backfill", action="store_true", help="先为已有视频补指纹")
    pd.set_defaults(func=cmd_duplicates)

    from .config import ROOT
    pw = sub.add_parser("crawl", help="定时抓取关键词与订阅链接")
    pw.add_argument("--config", default=str(ROOT / "config/keywords.yaml"))
    pw.add_argument("--once", action="store_true", help="只运行一轮到期任务")
    pw.add_argument("--force", action="store_true", help="首轮忽略任务到期时间")
    pw.add_argument("--check", action="store_true", help="仅验证配置，不访问平台")
    pw.set_defaults(func=cmd_crawl)

    pc = sub.add_parser("classify", help="补充已有视频的分类")
    pc.add_argument("--limit", type=int, default=100)
    pc.add_argument("--force", action="store_true")
    pc.set_defaults(func=cmd_classify)

    prep = sub.add_parser("repair-audio-sizes", help="按已验证哈希修复历史视频大小，并推进同步游标")
    prep.set_defaults(func=cmd_repair_audio_sizes)

    plog = sub.add_parser("login", help="扫码登录（终端里直接显示二维码）")
    plog.add_argument("--user", required=True, help="用户 id")
    plog.add_argument("--platform", required=True, choices=login.supported_platforms())
    plog.set_defaults(func=cmd_login)

    pic = sub.add_parser("import-cookies", help="导入浏览器 cookie（抖音走这条）")
    pic.add_argument("--user", required=True)
    pic.add_argument("--platform", required=True, choices=login.supported_platforms())
    pic.add_argument("--cookies", help="不给就从标准输入读")
    pic.set_defaults(func=cmd_import_cookies)

    pc = sub.add_parser("credentials", help="查看凭证状态")
    pc.add_argument("--user", required=True)
    pc.set_defaults(func=cmd_credentials)

    plo = sub.add_parser("logout", help="删除凭证")
    plo.add_argument("--user", required=True)
    plo.add_argument("--platform", required=True, choices=login.supported_platforms())
    plo.set_defaults(func=cmd_logout)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
