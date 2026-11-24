"""Qualify filter registration in the pinned Windows libmpv (no GPU or media required)."""

import ctypes
import locale
from pathlib import Path

locale.setlocale(locale.LC_NUMERIC, "C")
runtime = (
    Path(__file__).resolve().parents[1]
    / "composeApp/src/desktopMain/native/windows/runtime/libmpv-2.dll"
)
mpv = ctypes.CDLL(str(runtime))
mpv.mpv_create.restype = ctypes.c_void_p
mpv.mpv_set_option_string.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
mpv.mpv_set_option_string.restype = ctypes.c_int
mpv.mpv_initialize.argtypes = [ctypes.c_void_p]
mpv.mpv_initialize.restype = ctypes.c_int
mpv.mpv_get_property_string.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
mpv.mpv_get_property_string.restype = ctypes.c_void_p
mpv.mpv_free.argtypes = [ctypes.c_void_p]
mpv.mpv_terminate_destroy.argtypes = [ctypes.c_void_p]
mpv.mpv_error_string.argtypes = [ctypes.c_int]
mpv.mpv_error_string.restype = ctypes.c_char_p


def require_success(result, operation):
    if result < 0:
        raise RuntimeError(f"{operation}: {mpv.mpv_error_string(result).decode()}")


filter_chains = (
    b"d3d11vpp=nvidia-true-hdr",
    b"d3d11vpp=scale=2:scaling-mode=nvidia:nvidia-true-hdr",
)
for chain in filter_chains:
    player = mpv.mpv_create()
    if not player:
        raise RuntimeError("Could not create pinned libmpv client")
    try:
        for option, value in ((b"config", b"no"), (b"vo", b"null"), (b"ao", b"null")):
            require_success(mpv.mpv_set_option_string(player, option, value), option.decode())
        require_success(mpv.mpv_set_option_string(player, b"vf", chain), chain.decode())
        require_success(mpv.mpv_initialize(player), "libmpv initialization")
        version = mpv.mpv_get_property_string(player, b"mpv-version")
        if version:
            try:
                print(ctypes.string_at(version).decode(), "registered:", chain.decode())
            finally:
                mpv.mpv_free(version)
    finally:
        mpv.mpv_terminate_destroy(player)
