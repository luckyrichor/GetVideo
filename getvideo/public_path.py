"""Recognize the fixed public mount injected by the local nginx proxy.

The backend listens on loopback only. nginx MUST overwrite X-Forwarded-Prefix
on both entrypoints; no client-supplied arbitrary root path is accepted.
"""
from starlette.types import ASGIApp, Scope, Receive, Send


class PublicPathMiddleware:
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope['type'] in ('http', 'websocket'):
            headers = dict(scope.get('headers', []))
            if headers.get(b'x-forwarded-prefix') == b'/getvideo':
                scope = dict(scope)
                scope['root_path'] = '/getvideo'
                scope['path'] = '/getvideo' + scope['path']
                if 'raw_path' in scope:
                    scope['raw_path'] = b'/getvideo' + scope['raw_path']
        await self.app(scope, receive, send)
