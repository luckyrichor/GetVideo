from sqlalchemy import select
from getvideo.db import session_scope, init_db
from getvideo.models import Track


def test_two_scopes_can_store_same_video():
    init_db()
    with session_scope() as s:
        s.add_all([Track(owner_scope=u, platform='douyin', video_id='scope-same',
                         source_url='https://example.invalid/', audio_path=u+'/same.m4a')
                   for u in ['acct_scope_a', 'acct_scope_b']])
    with session_scope() as s:
        rows = list(s.scalars(select(Track).where(Track.video_id == 'scope-same')))
        assert {t.owner_scope for t in rows} == {'acct_scope_a', 'acct_scope_b'}


def test_output_scope_is_thread_local(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    from getvideo.storage_scope import audio_scope, output_root
    def root(scope):
        with audio_scope(scope):
            return output_root(tmp_path)
    with ThreadPoolExecutor(2) as pool:
        a, b = list(pool.map(root, ['acct_a', 'acct_b']))
    assert a != b
    assert output_root(tmp_path) == tmp_path
