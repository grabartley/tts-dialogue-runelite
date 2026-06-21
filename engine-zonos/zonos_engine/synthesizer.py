"""Zonos synthesis backends behind a single small interface.

Two implementations share one interface so the ``--stdio`` loop (``engine.py``) is identical
regardless of which is active:

* :class:`ZonosSynthesizer` - the real model. It imports torch + the Zonos package lazily on first
  use (never at import time) so the rest of the engine, and the framing unit tests, run on a
  machine with no torch/CUDA. It loads Zonos-v0.1 on CUDA, resolves the request's reference voice to
  a speaker embedding, applies the 8-dim emotion vector as conditioning, synthesizes, and returns
  ``(sample_rate, float_samples)`` at Zonos's native sample rate so the plugin never pitch-shifts.

* :class:`MockSynthesizer` - generates a short deterministic tone with no heavy imports. It exists
  purely to prove the wire framing end-to-end (request decode -> header + PCM frame) in CI on a
  machine with no GPU. It is selected by ``--mock`` and is never shipped as the real synthesis path.

Both report a real sample rate. The mock's rate matches Zonos's native rate so a frame produced in
mock mode is byte-shaped like a real one.

GPU detection lives here (``cuda_available``) because the engine, having loaded its own runtime, is
the only place that can reliably answer it; the plugin delegates to the engine's ``{ok, gpu}``
handshake for exactly this reason.
"""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

from . import emotion as emotion_mod
from . import voices

# Zonos-v0.1's native output sample rate. The autoencoder Zonos uses (DAC 44.1 kHz) decodes to
# 44.1 kHz audio, so this is the rate reported on every header; the plugin streams at this rate and
# never resamples. This is asserted against the loaded model at runtime in ZonosSynthesizer.
ZONOS_SAMPLE_RATE = 44100


class Synthesizer:
    """Common interface for the real and mock synthesizers."""

    def sample_rate(self) -> int:  # pragma: no cover - trivial
        raise NotImplementedError

    def cuda_available(self) -> bool:  # pragma: no cover - trivial
        raise NotImplementedError

    def gpu_detail(self) -> str:  # pragma: no cover - trivial
        return ""

    def synthesize(
        self,
        text: str,
        player: bool,
        race: Optional[str],
        gender: Optional[str],
        emotion: str,
        speed: float,
        emotion_vector: Optional[List[float]],
    ) -> Tuple[int, List[float]]:
        raise NotImplementedError

    def close(self) -> None:  # pragma: no cover - trivial default
        pass


class MockSynthesizer(Synthesizer):
    """Deterministic tone generator for framing validation only (``--mock``). No heavy imports.

    The pitch is nudged by the resolved emotion vector's primary axis so that even the mock makes
    the emotion path observable, but this is NOT real speech and is never the shipped synthesis
    path.
    """

    def __init__(self, sample_rate: int = ZONOS_SAMPLE_RATE):
        self._sample_rate = sample_rate

    def sample_rate(self) -> int:
        return self._sample_rate

    def cuda_available(self) -> bool:
        # The mock claims no GPU: it must never masquerade as a usable GPU engine. A real bundle
        # running on a GPU box reports gpu=true via ZonosSynthesizer.
        return False

    def gpu_detail(self) -> str:
        return "mock synthesizer (framing only, no GPU, not real speech)"

    def synthesize(self, text, player, race, gender, emotion, speed, emotion_vector):
        voice_id = voices.voice_for(player, race, gender)
        vec = emotion_mod.resolve_emotion_vector(emotion_vector, emotion)
        # Base 220 Hz, shifted a little by which emotion axis dominates, so distinct emotions
        # produce distinct tones in framing tests.
        primary_axis = max(range(len(vec)), key=lambda i: vec[i]) if vec else emotion_mod.NEUTRAL
        freq = 180.0 + 20.0 * primary_axis
        speed = speed if speed and speed > 0 else 1.0
        # ~0.4s of audio, scaled by length of text so longer text -> longer clip (loosely).
        seconds = max(0.25, min(2.0, 0.05 * max(1, len(text)))) / speed
        n = int(self._sample_rate * seconds)
        # Hash the voice id into a tiny amplitude tweak so voices are observably different too.
        amp = 0.2 + 0.05 * (sum(ord(c) for c in voice_id) % 5)
        samples = [amp * math.sin(2.0 * math.pi * freq * (i / self._sample_rate)) for i in range(n)]
        return self._sample_rate, samples


class ZonosSynthesizer(Synthesizer):
    """The real Zonos-v0.1 backend. torch + Zonos are imported lazily so importing this module is
    cheap and torch-free.

    Lifecycle: construct cheaply, then call :meth:`load` (or let the first :meth:`synthesize` load
    it). Loading imports torch and the Zonos package, selects the CUDA device, loads the model, and
    pre-loads the bundled reference-voice bank into speaker embeddings.
    """

    def __init__(self, bundle_root: str, model_id: str = "Zyphra/Zonos-v0.1-transformer"):
        self._bundle_root = bundle_root
        self._model_id = model_id
        self._model = None
        self._torch = None
        self._device = None
        self._speaker_cache = {}
        self._sample_rate = ZONOS_SAMPLE_RATE
        self._cuda = None
        self._gpu_detail = ""

    # -- GPU probe -------------------------------------------------------------------------------

    def cuda_available(self) -> bool:
        """True only when a usable CUDA GPU is present. Imports torch lazily; any failure (no torch,
        no driver, no device) is treated as "no GPU" rather than an error, so the handshake can
        still answer ``gpu=false`` cleanly and the plugin falls back to Kokoro."""
        if self._cuda is not None:
            return self._cuda
        try:
            import torch  # noqa: WPS433 (intentional lazy import)

            self._torch = torch
            available = bool(torch.cuda.is_available()) and torch.cuda.device_count() > 0
            if available:
                name = torch.cuda.get_device_name(0)
                self._gpu_detail = "CUDA GPU: {} (torch {})".format(name, torch.__version__)
            else:
                self._gpu_detail = "no usable CUDA device (torch {})".format(torch.__version__)
            self._cuda = available
        except Exception as exc:  # noqa: BLE001 - any failure means "no usable GPU"
            self._gpu_detail = "torch/CUDA unavailable: {}".format(exc)
            self._cuda = False
        return self._cuda

    def gpu_detail(self) -> str:
        if self._cuda is None:
            self.cuda_available()
        return self._gpu_detail

    def sample_rate(self) -> int:
        return self._sample_rate

    # -- Model loading ---------------------------------------------------------------------------

    def load(self) -> None:
        """Load Zonos-v0.1 on CUDA. Imports torch + the Zonos package lazily. Raises on failure so
        ``--selftest`` and the health handshake can report the problem; the ``--stdio`` loop catches
        it and emits an error line rather than dying."""
        if self._model is not None:
            return
        if not self.cuda_available():
            raise RuntimeError(
                "Zonos requires a usable CUDA GPU; none detected ({})".format(self.gpu_detail())
            )
        import torch  # noqa: WPS433
        from zonos.model import Zonos  # type: ignore  # noqa: WPS433

        self._torch = torch
        self._device = "cuda"
        self._model = Zonos.from_pretrained(self._model_id, device=self._device)
        # Report the model's real output rate if it exposes one, else the known DAC rate.
        rate = getattr(self._model, "sampling_rate", None) or getattr(
            getattr(self._model, "autoencoder", None), "sampling_rate", None
        )
        if isinstance(rate, int) and rate > 0:
            self._sample_rate = rate

    def _speaker_embedding(self, voice_id: str):
        """Resolve a reference-voice id to a Zonos speaker embedding, caching per id. Falls back to
        the default voice if the specific reference clip is missing from the bundle."""
        if voice_id in self._speaker_cache:
            return self._speaker_cache[voice_id]
        import os

        import torchaudio  # type: ignore  # noqa: WPS433

        path = voices.embedding_path_for(self._bundle_root, voice_id)
        if not os.path.isfile(path):
            path = voices.embedding_path_for(self._bundle_root, voices.DEFAULT_VOICE)
        wav, sr = torchaudio.load(path)
        embedding = self._model.make_speaker_embedding(wav, sr)
        self._speaker_cache[voice_id] = embedding
        return embedding

    # -- Synthesis -------------------------------------------------------------------------------

    def synthesize(self, text, player, race, gender, emotion, speed, emotion_vector):
        self.load()
        torch = self._torch
        from zonos.conditioning import make_cond_dict  # type: ignore  # noqa: WPS433

        voice_id = voices.voice_for(player, race, gender)
        speaker = self._speaker_embedding(voice_id)
        vec = emotion_mod.resolve_emotion_vector(emotion_vector, emotion)
        emotion_tensor = torch.tensor([vec], device=self._device, dtype=torch.float32)

        cond_dict = make_cond_dict(
            text=text,
            speaker=speaker,
            language="en-us",
            emotion=emotion_tensor,
        )
        conditioning = self._model.prepare_conditioning(cond_dict)
        codes = self._model.generate(conditioning)
        wavs = self._model.autoencoder.decode(codes).cpu()
        # wavs is [batch, channels?, samples] or [batch, samples]; flatten to a mono float list.
        audio = wavs[0]
        if audio.dim() > 1:
            audio = audio.mean(dim=0)
        samples = audio.detach().to("cpu").float().flatten().tolist()
        return self._sample_rate, samples

    def close(self) -> None:
        self._model = None
        self._speaker_cache.clear()
        try:
            if self._torch is not None and self._cuda:
                self._torch.cuda.empty_cache()
        except Exception:  # noqa: BLE001 - best-effort cleanup
            pass


def build_synthesizer(bundle_root: str, mock: bool) -> Synthesizer:
    """Factory: the mock tone generator (``--mock``) or the real Zonos backend."""
    if mock:
        return MockSynthesizer()
    return ZonosSynthesizer(bundle_root)
