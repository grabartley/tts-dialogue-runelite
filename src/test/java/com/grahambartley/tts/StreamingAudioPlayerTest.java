package com.grahambartley.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class StreamingAudioPlayerTest {

  /** Makes a mock line whose write() reports it accepted everything it was handed. */
  private static SourceDataLine lineThatAcceptsEverything() {
    SourceDataLine line = mock(SourceDataLine.class);
    when(line.write(any(byte[].class), anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(2));
    return line;
  }

  @Test
  public void streamsAllPcmThenDrainsAndClosesLine() throws Exception {
    SourceDataLine line = lineThatAcceptsEverything();
    StreamingAudioPlayer player = new StreamingAudioPlayer(format -> line);

    // 3 mono samples -> 6 bytes of 16-bit PCM, a single chunk.
    player.stream(new float[] {0f, 0f, 0f}, 24_000, 100);

    // AudioFormat has no value equality, so capture and assert its fields.
    ArgumentCaptor<AudioFormat> format = ArgumentCaptor.forClass(AudioFormat.class);
    verify(line).open(format.capture());
    assertEquals(24_000f, format.getValue().getSampleRate(), 0f);
    assertEquals(16, format.getValue().getSampleSizeInBits());
    assertEquals(1, format.getValue().getChannels());
    verify(line).start();
    verify(line).write(any(byte[].class), eq(0), eq(6));
    verify(line).drain();
    verify(line).close();
  }

  @Test
  public void stopMidStreamHaltsFurtherWritesAndSkipsDrain() {
    SourceDataLine line = mock(SourceDataLine.class);
    StreamingAudioPlayer player = new StreamingAudioPlayer(format -> line);
    // Interrupt as soon as the first chunk is written: the loop should bail before the next one.
    when(line.write(any(byte[].class), anyInt(), anyInt()))
        .thenAnswer(
            inv -> {
              player.stop();
              return inv.getArgument(2);
            });

    // ~10000 bytes spans multiple 4096-byte chunks, so an uninterrupted run would write 3 times.
    player.stream(new float[5_000], 24_000, 100);

    verify(line, times(1)).write(any(byte[].class), anyInt(), anyInt());
    verify(line, never()).drain();
  }

  @Test
  public void emptySamplesNeverTouchTheAudioLine() {
    StreamingAudioPlayer.LineFactory factory = mock(StreamingAudioPlayer.LineFactory.class);
    StreamingAudioPlayer player = new StreamingAudioPlayer(factory);

    player.stream(new float[0], 24_000, 100);
    player.stream(null, 24_000, 100);

    verifyNoInteractions(factory);
  }

  @Test
  public void appliesProportionalGainWhenMasterGainIsSupported() {
    SourceDataLine line = lineThatAcceptsEverything();
    FloatControl gain = mock(FloatControl.class);
    when(gain.getMinimum()).thenReturn(-80f);
    when(gain.getMaximum()).thenReturn(6f);
    when(line.isControlSupported(FloatControl.Type.MASTER_GAIN)).thenReturn(true);
    when(line.getControl(FloatControl.Type.MASTER_GAIN)).thenReturn(gain);
    StreamingAudioPlayer player = new StreamingAudioPlayer(format -> line);

    player.stream(new float[] {0f}, 24_000, 50);

    ArgumentCaptor<Float> applied = ArgumentCaptor.forClass(Float.class);
    verify(gain).setValue(applied.capture());
    // 50% volume is 20*log10(0.5) ~= -6.02 dB, and must stay inside the control's range.
    assertTrue(applied.getValue() <= 0f && applied.getValue() >= -80f);
  }

  @Test
  public void zeroVolumeSetsTheMinimumGain() {
    SourceDataLine line = lineThatAcceptsEverything();
    FloatControl gain = mock(FloatControl.class);
    when(gain.getMinimum()).thenReturn(-80f);
    when(line.isControlSupported(FloatControl.Type.MASTER_GAIN)).thenReturn(true);
    when(line.getControl(FloatControl.Type.MASTER_GAIN)).thenReturn(gain);
    StreamingAudioPlayer player = new StreamingAudioPlayer(format -> line);

    player.stream(new float[] {0f}, 24_000, 0);

    verify(gain).setValue(-80f);
  }

  @Test
  public void unsupportedGainControlDoesNotBreakPlayback() {
    SourceDataLine line = lineThatAcceptsEverything();
    when(line.isControlSupported(any(FloatControl.Type.class))).thenReturn(false);
    StreamingAudioPlayer player = new StreamingAudioPlayer(format -> line);

    player.stream(new float[] {0f}, 24_000, 100);

    // Playback still completes without ever asking for the (absent) gain control.
    verify(line).write(any(byte[].class), anyInt(), anyInt());
    verify(line).drain();
  }
}
