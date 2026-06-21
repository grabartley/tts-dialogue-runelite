"""Standalone Zonos GPU TTS engine for the TTS Dialogue RuneLite plugin.

Speaks the plugin's ``--stdio`` line protocol and ``{ok, gpu}`` health handshake. Packaged as a
self-contained per-OS bundle (embedded Python + PyTorch CUDA wheels + Zonos + weights) so a user
needs only an NVIDIA driver, no Python or CUDA toolkit.
"""

__all__ = ["protocol", "voices", "emotion", "synthesizer", "engine"]
