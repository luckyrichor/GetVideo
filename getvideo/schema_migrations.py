"""SQLite ownership upgrade. Run before starting workers; IDs and references stay stable."""
from sqlalchemy import inspect
from sqlalchemy.schema import CreateTable, CreateIndex
from .models import Track


def migrate_tracks(engine):
    inspector = inspect(engine)
    if not inspector.has_table('tracks'):
        return
    columns = {c['name'] for c in inspector.get_columns('tracks')}
    if 'owner_scope' in columns:
        return
    if engine.dialect.name != 'sqlite':
        raise RuntimeError('Existing non-SQLite databases require an explicit ownership migration')
    ddl = str(CreateTable(Track.__table__).compile(engine)).replace('CREATE TABLE tracks', 'CREATE TABLE tracks_scoped', 1)
    connection = engine.raw_connection()
    try:
        cursor = connection.cursor()
        cursor.execute('PRAGMA foreign_keys=OFF')
        cursor.execute('BEGIN IMMEDIATE')
        cursor.execute(ddl)
        names = ','.join('"' + c.name + '"' for c in Track.__table__.columns if c.name in columns)
        cursor.execute(f'INSERT INTO tracks_scoped ({names},owner_scope) SELECT {names},\'legacy\' FROM tracks')
        old_count = cursor.execute('SELECT count(*) FROM tracks').fetchone()[0]
        new_count = cursor.execute('SELECT count(*) FROM tracks_scoped').fetchone()[0]
        if old_count != new_count:
            raise RuntimeError('Track migration row count mismatch')
        cursor.execute('DROP TABLE tracks')
        cursor.execute('ALTER TABLE tracks_scoped RENAME TO tracks')
        for index in Track.__table__.indexes:
            cursor.execute(str(CreateIndex(index).compile(engine)))
        if cursor.execute('PRAGMA foreign_key_check').fetchall():
            raise RuntimeError('Track migration foreign key check failed')
        if cursor.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise RuntimeError('Track migration integrity check failed')
        connection.commit()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.cursor().execute('PRAGMA foreign_keys=ON')
        connection.close()
