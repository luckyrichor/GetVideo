"""Per-request output ownership, without mutating process-wide settings."""
from contextlib import contextmanager
from contextvars import ContextVar
import hashlib

_scope = ContextVar('getvideo_audio_scope', default='legacy')


@contextmanager
def audio_scope(scope):
    token = _scope.set(scope)
    try:
        yield
    finally:
        _scope.reset(token)


def output_root(root):
    scope = _scope.get()
    return root if scope == 'legacy' else root / 'accounts' / hashlib.sha256(scope.encode()).hexdigest()
