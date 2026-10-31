"""Deployment must expose only account endpoints with application authentication."""
import re
from pathlib import Path


def test_nginx_account_route_allowlist():
    config = Path('deploy/tx-https/getvideo-path.conf').read_text()
    pattern = re.search(r'location ~ (\S+) \{', config).group(1)
    exact = {path for path, body in re.findall(r'location = (\S+) \{([^{}]*)\}', config)
             if 'auth_basic off;' in body}

    def exposed(path):
        return path in exact or re.search(pattern, path)

    for path in ['/account/register', '/account/login', '/account/me', '/account/logout',
                 '/account/platforms', '/account/platform-bindings',
                 '/account/platform-auth/start', '/account/platform-auth/status',
                 '/account/platform-auth/image', '/account/platform-auth/complete',
                 '/account/platform-auth/cancel', '/account/platform-auth/douyin/callback',
                 '/account/platform-bindings/bilibili/unbind', '/account/platform-bindings/douyin/unbind',
                 '/v2/sync', '/v2/platforms/qr/abc/image', '/agent/v2/actions']:
        assert exposed('/getvideo' + path), path
        assert not exposed(path), path  # Never expose the same routes at the shared host root.
    for path in ['/tracks', '/sync', '/audio/1', '/credentials', '/account/login-extra', '/agent/plan', '/account/platform-auth/complete-extra',
                 '/account/platform-bindings/other/unbind']:
        assert not exposed('/getvideo' + path), path
    assert not exposed('/getaudio/account/login')
    assert not exposed('/getvideo-extra/account/login')
    assert 'location ^~ /getvideo/' in config
    assert 'listen 8443' not in config
    assert 'auth_basic_user_file /etc/nginx/getvideo.htpasswd;' in config
    assert 'proxy_set_header X-Forwarded-For $remote_addr;' in config
    assert 'proxy_pass http://127.0.0.1:8900;' in config
