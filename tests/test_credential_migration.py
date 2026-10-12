import pytest
from fastapi import HTTPException
from getvideo import credentials
from getvideo.db import init_db
from getvideo.config import settings


def test_ticket_moves_once_and_reencrypts():
    from getvideo.credential_migration import issue_ticket, claim
    init_db()
    credentials.save('guest_migration', 'douyin', {'sessionid': 'fixture'})
    ticket = issue_ticket('guest_migration')
    assert claim(ticket, 'acct_migration')['platforms'] == ['douyin']
    assert credentials.load('acct_migration', 'douyin') == {'sessionid': 'fixture'}
    assert credentials.load('guest_migration', 'douyin') is None
    assert not (settings.cookie_dir / 'guest_migration__douyin.txt').exists()
    assert claim(ticket, 'acct_migration')['platforms'] == ['douyin']
    with pytest.raises(HTTPException) as e:
        claim(ticket, 'acct_other')
    assert e.value.status_code == 409


def test_ticket_conflict_preserves_both_and_expired_rejected():
    from getvideo.credential_migration import issue_ticket, claim
    init_db()
    credentials.save('guest_conflict', 'bilibili', {'SESSDATA': 'source'})
    credentials.save('acct_conflict', 'bilibili', {'SESSDATA': 'target'})
    ticket = issue_ticket('guest_conflict')
    with pytest.raises(HTTPException):
        claim(ticket, 'acct_conflict')
    assert credentials.load('guest_conflict', 'bilibili')['SESSDATA'] == 'source'
    assert credentials.load('acct_conflict', 'bilibili')['SESSDATA'] == 'target'
    with pytest.raises(HTTPException):
        claim(issue_ticket('guest_conflict', expires_in=-1), 'acct_new')
    with pytest.raises(HTTPException):
        claim('guest_conflict', 'acct_new')


def test_competing_ticket_claims_have_one_owner():
    from concurrent.futures import ThreadPoolExecutor
    from getvideo.credential_migration import issue_ticket, claim
    init_db()
    credentials.save('guest_race', 'douyin', {'sessionid': 'fixture-race'})
    ticket = issue_ticket('guest_race')
    def attempt(owner):
        try:
            claim(ticket, owner)
            return owner
        except HTTPException as e:
            assert e.status_code == 409
            return None
    with ThreadPoolExecutor(max_workers=2) as pool:
        winners = list(pool.map(attempt, ['acct_race_a', 'acct_race_b']))
    assert sum(w is not None for w in winners) == 1
    for owner in ['acct_race_a', 'acct_race_b']:
        assert (credentials.load(owner, 'douyin') is not None) == (owner in winners)
    assert credentials.load('guest_race', 'douyin') is None
