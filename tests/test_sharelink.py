"""分享文本解析的单测。全部离线 —— 短链展开的用例单独打 network 标记。"""
from __future__ import annotations

import pytest

from getvideo.sharelink import LinkError, Platform, find_urls, resolve


@pytest.mark.parametrize(
    "text,platform,video_id",
    [
        # B 站网页复制
        ("https://www.bilibili.com/video/BV1GJ411x7h7/", Platform.BILIBILI, "BV1GJ411x7h7"),
        # B 站 App 分享的完整文案
        (
            "【【4K】治愈系纯音乐-哔哩哔哩】 https://www.bilibili.com/video/BV1GJ411x7h7/?share_source=copy_web&vd_source=abc",
            Platform.BILIBILI,
            "BV1GJ411x7h7",
        ),
        # 裸 BV 号
        ("BV1GJ411x7h7", Platform.BILIBILI, "BV1GJ411x7h7"),
        # av 号
        ("av170001", Platform.BILIBILI, "av170001"),
        # 抖音 App 分享文案：中文紧贴 URL，不能被吃进链接
        (
            "7.53 pQz:/ 复制打开抖音，看看【作者】的作品 https://www.douyin.com/video/7234567890123456789 复制此链接，打开Dou音搜索",
            Platform.DOUYIN,
            "7234567890123456789",
        ),
        # 抖音个人页 modal_id 形式
        (
            "https://www.douyin.com/user/MS4wLjABAAAA?modal_id=7234567890123456789",
            Platform.DOUYIN,
            "7234567890123456789",
        ),
    ],
)
def test_resolve(text, platform, video_id):
    link = resolve(text)
    assert link.platform is platform
    assert link.video_id == video_id


def test_tracking_params_stripped():
    """追踪参数必须去掉，否则同一个视频会产生不同 URL，去重会漏。"""
    link = resolve("https://www.bilibili.com/video/BV1GJ411x7h7?vd_source=x&spm_id_from=333.999")
    assert "vd_source" not in link.url
    assert "spm_id" not in link.url


def test_page_preserved():
    """分 P 的 p 参数要留着，否则永远只下第一集。"""
    link = resolve("https://www.bilibili.com/video/BV1GJ411x7h7?p=3")
    assert "p=3" in link.url
    assert link.page == 3
    assert link.track_key == "BV1GJ411x7h7-p3"


def test_first_page_uses_existing_identity():
    first = resolve("https://www.bilibili.com/video/BV1GJ411x7h7")
    explicit = resolve("https://www.bilibili.com/video/BV1GJ411x7h7?p=1")
    assert first.track_key == explicit.track_key == "BV1GJ411x7h7"
    assert first.url == explicit.url


def test_av_page_preserved():
    link = resolve("https://www.bilibili.com/video/av170001?p=2")
    assert link.page == 2
    assert link.track_key == "av170001-p2"
    assert link.url.endswith("?p=2")


@pytest.mark.parametrize("page", ["0", "-1", "abc"])
def test_invalid_bilibili_page_rejected(page):
    with pytest.raises(LinkError, match="正整数"):
        resolve(f"https://www.bilibili.com/video/BV1GJ411x7h7?p={page}")


def test_url_not_swallowing_chinese():
    """URL 正则不能贪婪到把后面的中文吃进去。"""
    urls = find_urls("看看这个 https://www.douyin.com/video/7234567890123456789 复制此链接打开抖音")
    assert urls == ["https://www.douyin.com/video/7234567890123456789"]


@pytest.mark.parametrize("text", ["", "   ", "这段文字里什么都没有", "https://www.youtube.com/watch?v=abc"])
def test_rejects_unusable(text):
    with pytest.raises(LinkError):
        resolve(text)
