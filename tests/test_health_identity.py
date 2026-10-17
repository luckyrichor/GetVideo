from fastapi.testclient import TestClient
from getvideo.api import app


def test_health_identifies_getvideo_for_phone_connection_check():
    with TestClient(app) as client:
        response = client.get('/health')
    assert response.status_code == 200
    assert response.json() == {'ok': True, 'app': 'GetVideo', 'version': '0.1.2'}
