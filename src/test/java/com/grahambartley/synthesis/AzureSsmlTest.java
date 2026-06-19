package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;

/** Emotion -> SSML style mapping, style degradation, XML escaping, and well-formedness. */
public class AzureSsmlTest {

  /** A voice that supports the full emotional style set (from AzureVoiceStyles). */
  private static final String FULL_VOICE = "en-US-AriaNeural";

  /** A voice that supports no emotional styles, used to assert degradation. */
  private static final String PLAIN_VOICE = "en-GB-ThomasNeural";

  private static void assertWellFormed(String ssml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory
          .newDocumentBuilder()
          .parse(
              new java.io.ByteArrayInputStream(
                  ssml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new AssertionError("SSML is not well-formed XML: " + ssml, e);
    }
  }

  @Test
  public void happyMapsToCheerfulWithStyleDegree() {
    String ssml = AzureSsml.build("Hello there", FULL_VOICE, Emotion.HAPPY);
    assertTrue("happy -> cheerful", ssml.contains("style=\"cheerful\""));
    assertTrue("styled output carries a styledegree", ssml.contains("styledegree="));
    assertTrue(ssml.contains("mstts:express-as"));
    assertWellFormed(ssml);
  }

  @Test
  public void sadMapsToSad() {
    String ssml = AzureSsml.build("Hello there", FULL_VOICE, Emotion.SAD);
    assertTrue(ssml.contains("style=\"sad\""));
    assertWellFormed(ssml);
  }

  @Test
  public void angryMapsToAngry() {
    String ssml = AzureSsml.build("Hello there", FULL_VOICE, Emotion.ANGRY);
    assertTrue(ssml.contains("style=\"angry\""));
    assertWellFormed(ssml);
  }

  @Test
  public void scaredMapsToTerrified() {
    String ssml = AzureSsml.build("Hello there", FULL_VOICE, Emotion.SCARED);
    assertTrue(ssml.contains("style=\"terrified\""));
    assertWellFormed(ssml);
  }

  @Test
  public void neutralHasNoExpressAsWrapper() {
    String ssml = AzureSsml.build("Hello there", FULL_VOICE, Emotion.NEUTRAL);
    assertFalse("neutral renders plain", ssml.contains("express-as"));
    assertFalse(ssml.contains("style="));
    assertTrue("plain text still present", ssml.contains("Hello there"));
    assertWellFormed(ssml);
  }

  @Test
  public void styleForCoversEveryEmotion() {
    assertEquals("cheerful", AzureSsml.styleFor(Emotion.HAPPY));
    assertEquals("sad", AzureSsml.styleFor(Emotion.SAD));
    assertEquals("angry", AzureSsml.styleFor(Emotion.ANGRY));
    assertEquals("terrified", AzureSsml.styleFor(Emotion.SCARED));
    assertNotNull("neutral has no style", AzureSsml.styleFor(Emotion.HAPPY));
    assertEquals(null, AzureSsml.styleFor(Emotion.NEUTRAL));
  }

  @Test
  public void unsupportedStyleDegradesToPlain() {
    // ANGRY is a supported emotion, but this voice does not expose the angry style, so it must
    // degrade to plain rather than emit a style Azure would reject.
    String ssml = AzureSsml.build("Hello there", PLAIN_VOICE, Emotion.ANGRY);
    assertFalse("degrades to plain when the voice lacks the style", ssml.contains("express-as"));
    assertTrue(ssml.contains("Hello there"));
    assertWellFormed(ssml);
  }

  @Test
  public void mssttsNamespaceDeclaredOnStyledOutput() {
    String ssml = AzureSsml.build("Hi", FULL_VOICE, Emotion.HAPPY);
    assertTrue("mstts namespace declared", ssml.contains("xmlns:mstts="));
  }

  @Test
  public void userTextIsXmlEscaped() {
    String ssml = AzureSsml.build("Tom & Jerry <said> \"hi\"", FULL_VOICE, Emotion.NEUTRAL);
    assertTrue(ssml.contains("Tom &amp; Jerry"));
    assertTrue(ssml.contains("&lt;said&gt;"));
    assertFalse("raw ampersand must not leak", ssml.contains("Tom & Jerry"));
    assertWellFormed(ssml);
  }

  @Test
  public void escapeXmlHandlesAllEntities() {
    assertEquals("&amp;&lt;&gt;&quot;&apos;", AzureSsml.escapeXml("&<>\"'"));
  }
}
