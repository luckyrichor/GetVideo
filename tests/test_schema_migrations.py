import re
from sqlalchemy import create_engine, text
from sqlalchemy.schema import CreateTable
from getvideo.models import Track


def test_legacy_tracks_rebuild_keeps_ids_and_references(tmp_path):
    from getvideo.schema_migrations import migrate_tracks
    e = create_engine(f'sqlite:///{tmp_path / "legacy.db"}')
    ddl = str(CreateTable(Track.__table__).compile(e)).replace('owner_scope, platform, video_id', 'platform, video_id')
    ddl = '\n'.join(line for line in ddl.splitlines() if 'owner_scope VARCHAR' not in line)
    with e.begin() as c:
        c.exec_driver_sql(ddl)
        c.exec_driver_sql("INSERT INTO tracks (id,platform,video_id,source_url,title,audio_path,audio_format,updated_seq,deleted,created_at,updated_at) VALUES (17,'douyin','v','url','旧视频','old.m4a','m4a',1,0,'2026-01-01','2026-01-01')")
        c.exec_driver_sql('CREATE TABLE refs (track_id INTEGER REFERENCES tracks(id))')
        c.exec_driver_sql('INSERT INTO refs VALUES (17)')
    migrate_tracks(e); migrate_tracks(e)
    with e.connect() as c:
        assert c.execute(text('SELECT id,title,owner_scope FROM tracks')).one() == (17, '旧视频', 'legacy')
        assert c.exec_driver_sql('PRAGMA foreign_key_check').all() == []
        assert c.exec_driver_sql('SELECT track_id FROM refs').scalar() == 17
        c.exec_driver_sql("INSERT INTO tracks (id,owner_scope,platform,video_id,source_url,title,audio_path,audio_format,updated_seq,deleted,created_at,updated_at) VALUES (18,'acct_a','douyin','v','url','new','new.m4a','m4a',2,0,'2026-01-01','2026-01-01')")
