"""Video selection must never silently substitute the music track."""
import pytest
from getvideo.extractors.base import ExtractorError
from getvideo.extractors.douyin import DouyinExtractor

@pytest.mark.parametrize("music_duration", [None, 21, 300])
def test_complete_video_always_selected(music_duration):
    aweme = {"duration": 245000, "music": {"duration": music_duration,
             "play_url": {"url_list": ["https://example.com/music.mp3"]}},
             "video": {"play_addr": {"url_list": ["https://example.com/video.mp4"]}}}
    assert DouyinExtractor()._video_sources(aweme) == ("video", ["https://example.com/video.mp4"])

@pytest.mark.parametrize("aweme", [{}, {"music": {"play_url": {"url_list": ["https://example.com/music.mp3"]}}}])
def test_missing_picture_is_an_error(aweme):
    with pytest.raises(ExtractorError, match="视频地址"):
        DouyinExtractor()._video_sources(aweme)

def test_candidates_deduplicate_and_prefer_avc():
    aweme = {"video": {"play_addr_h264": {"url_list": ["https://example.com/avc.mp4"]},
                       "play_addr": {"url_list": ["https://example.com/avc.mp4"]},
                       "download_addr": {"url_list": ["https://example.com/backup.mp4"]}}}
    assert DouyinExtractor()._video_sources(aweme)[1] == ["https://example.com/avc.mp4", "https://example.com/backup.mp4"]
