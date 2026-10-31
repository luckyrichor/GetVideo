from fastapi import FastAPI
from fastapi.testclient import TestClient

from getvideo.public_path import PublicPathMiddleware


def application():
    app = FastAPI()
    app.add_middleware(PublicPathMiddleware)

    @app.get('/probe/')
    def probe():
        return {'ok': True}

    return TestClient(app, base_url='https://43.142.37.199')


def test_project_prefix_preserves_routing_redirect_and_openapi():
    with application() as client:
        headers = {'x-forwarded-prefix': '/getvideo'}
        assert client.get('/probe/', headers=headers).json() == {'ok': True}
        redirect = client.get('/probe', headers=headers, follow_redirects=False)
        assert redirect.headers['location'] == 'https://43.142.37.199/getvideo/probe/'
        assert '/getvideo/openapi.json' in client.get('/docs', headers=headers).text
        assert client.get('/openapi.json', headers=headers).json()['servers'] == [{'url': '/getvideo'}]


def test_old_entry_is_still_available_and_arbitrary_prefix_is_ignored():
    with application() as client:
        assert client.get('/probe/').status_code == 200
        assert client.get('/probe', follow_redirects=False).headers['location'] == 'https://43.142.37.199/probe/'
        r = client.get('/probe', headers={'x-forwarded-prefix': '/getaudio'}, follow_redirects=False)
        assert r.headers['location'] == 'https://43.142.37.199/probe/'


def test_account_guard_classifies_api_paths_relative_to_mount():
    from fastapi import Depends
    from getvideo.accounts import protect_account_ids
    app = FastAPI(dependencies=[Depends(protect_account_ids)])
    app.add_middleware(PublicPathMiddleware)

    @app.post('/v2/example')
    def modern():
        return {'ok': True}

    with TestClient(app) as client:
        # Modern routes implement their own session policy; the legacy body-ID
        # guard must behave identically on both mounts.
        body = {'user_id': 'acct_fixture'}
        assert client.post('/v2/example', json=body).status_code == 200
        assert client.post('/v2/example', json=body, headers={'x-forwarded-prefix': '/getvideo'}).status_code == 200
