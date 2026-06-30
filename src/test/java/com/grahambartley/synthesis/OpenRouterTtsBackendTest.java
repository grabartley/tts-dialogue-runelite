package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grahambartley.TTSDialogueConfig;
import com.grahambartley.tts.Pcm;
import com.grahambartley.voice.VoiceManager.NPCGender;
import com.grahambartley.voice.VoiceManager.NPCRace;
import java.util.EnumSet;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * HTTP path, headers, JSON body, decode, availability gating, cache variant, and graceful failure.
 */
@RunWith(JUnitParamsRunner.class)
public class OpenRouterTtsBackendTest {

  /** Config with a settable key, char cap, and pace; everything else uses interface defaults. */
  private static final class TestConfig implements TTSDialogueConfig {
    String key = "";
    int maxChars = 600;
    int speedPercent = 100;
    TTSDialogueConfig.SpokenLanguage language = TTSDialogueConfig.SpokenLanguage.ENGLISH;
    TTSDialogueConfig.SpeakingStyle playerQuirk = TTSDialogueConfig.SpeakingStyle.NONE;
    TTSDialogueConfig.SpeakingStyle npcQuirk = TTSDialogueConfig.SpeakingStyle.NONE;

    @Override
    public String openRouterApiKey() {
      return key;
    }

    @Override
    public int cloudMaxChars() {
      return maxChars;
    }

    @Override
    public int speakingPace() {
      return speedPercent;
    }

    @Override
    public TTSDialogueConfig.SpokenLanguage cloudLanguage() {
      return language;
    }

    @Override
    public TTSDialogueConfig.SpeakingStyle cloudPlayerSpeakingStyle() {
      return playerQuirk;
    }

    @Override
    public TTSDialogueConfig.SpeakingStyle cloudNpcSpeakingStyle() {
      return npcQuirk;
    }
  }

  private static String chatResponse(String content) {
    JsonObject message = new JsonObject();
    message.addProperty("role", "assistant");
    message.addProperty("content", content);
    JsonObject choice = new JsonObject();
    choice.add("message", message);
    com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
    choices.add(choice);
    JsonObject body = new JsonObject();
    body.add("choices", choices);
    return body.toString();
  }

  private MockWebServer server;
  private OkHttpClient client;
  private final Gson gson = new Gson();

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new OkHttpClient();
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
  }

  private OpenRouterTtsBackend backend(TestConfig config) {
    // Point the backend at the mock server while keeping the real header/body/decode/error logic.
    return new OpenRouterTtsBackend(
        client, config, gson, server.url("/api/v1/audio/speech").toString());
  }

  private static SynthesisRequest req() {
    return new SynthesisRequest(
        "Hello & welcome", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);
  }

  @Test
  public void availabilityRequiresKey() {
    TestConfig config = new TestConfig();
    assertFalse("blank key -> unavailable", backend(config).isAvailable());

    config.key = "   ";
    assertFalse("whitespace-only key -> unavailable", backend(config).isAvailable());

    config.key = "sk-or-abc";
    assertTrue("a key set -> available", backend(config).isAvailable());
  }

  @Test
  public void advertisesTheFullGeminiEmotionSet() {
    assertEquals(
        "every chat-head emotion is renderable, so none is downgraded away",
        EnumSet.of(Emotion.NEUTRAL, Emotion.HAPPY, Emotion.SAD, Emotion.ANGRY, Emotion.SCARED),
        backend(new TestConfig()).supportedEmotions());
  }

  @Test
  public void prependsTheInlineStyleTagForEachEmotion() throws Exception {
    assertEquals("[happy] Hello & welcome", inputForEmotion(Emotion.HAPPY));
    assertEquals("[sad] Hello & welcome", inputForEmotion(Emotion.SAD));
    assertEquals("[angry] Hello & welcome", inputForEmotion(Emotion.ANGRY));
    assertEquals("[fearful] Hello & welcome", inputForEmotion(Emotion.SCARED));
  }

  @Test
  public void neutralEmotionSendsThePlainTextWithNoTag() throws Exception {
    assertEquals("Hello & welcome", inputForEmotion(Emotion.NEUTRAL));
  }

  /**
   * Synthesizes one line at the given emotion and returns the {@code input} field actually sent.
   */
  private String inputForEmotion(Emotion emotion) throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    SynthesisRequest request =
        new SynthesisRequest(
            "Hello & welcome", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), emotion);
    backend(config).synthesize(request);

    JsonObject body =
        new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
    return body.get("input").getAsString();
  }

  @Test
  public void successfulResponseDecodesRawPcmAt24k() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";

    short[] samples = {0, 16384, -16384, 32767};
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(samples))));

    Pcm pcm = backend(config).synthesize(req());

    assertNotNull("a 200 with raw PCM yields audio", pcm);
    assertEquals(24_000, pcm.getSampleRate());
    assertEquals(samples.length, pcm.getSamples().length);
  }

  @Test
  public void sendsBearerAuthAndJsonBody() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-secret";
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    backend(config).synthesize(req());

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertEquals("/api/v1/audio/speech", recorded.getPath());
    assertEquals("Bearer sk-or-secret", recorded.getHeader("Authorization"));
    assertTrue(
        "a JSON content type is sent",
        recorded.getHeader("Content-Type").startsWith("application/json"));
    assertNotNull("a User-Agent is sent", recorded.getHeader("User-Agent"));

    JsonObject body = new JsonParser().parse(recorded.getBody().readUtf8()).getAsJsonObject();
    assertEquals("google/gemini-3.1-flash-tts-preview", body.get("model").getAsString());
    assertEquals("Hello & welcome", body.get("input").getAsString());
    assertEquals("pcm", body.get("response_format").getAsString());
    assertEquals("Charon", body.get("voice").getAsString());
  }

  @Test
  public void voiceFieldComesFromTheGeminiVoiceMap() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    SynthesisRequest female =
        new SynthesisRequest("Hi", VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE), Emotion.NEUTRAL);
    backend(config).synthesize(female);

    JsonObject body =
        new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
    assertEquals(
        "the voice is whatever the map resolves for the spec",
        new GeminiVoiceMap().voiceFor(female.voice()),
        body.get("voice").getAsString());
  }

  @Test
  public void cacheVariantFoldsInModelAndVoiceSoRendersNeverCollide() {
    OpenRouterTtsBackend backend = backend(new TestConfig());

    SynthesisRequest humanMale =
        new SynthesisRequest("a", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);
    SynthesisRequest elfFemale =
        new SynthesisRequest("a", VoiceSpec.npc(NPCRace.ELF, NPCGender.FEMALE), Emotion.NEUTRAL);

    String variant = backend.cacheVariant(humanMale);
    assertTrue(
        "the variant carries the fixed model id so no future model switch can replay its audio",
        variant.contains("google/gemini-3.1-flash-tts-preview"));
    assertTrue(
        "the variant carries the resolved Gemini voice",
        variant.contains(new GeminiVoiceMap().voiceFor(humanMale.voice())));
    assertNotEquals(
        "two specs that map to different voices never share a variant",
        backend.cacheVariant(humanMale),
        backend.cacheVariant(elfFemale));
  }

  private static final CharacterProfile PROFILE =
      new CharacterProfile(
          "Troll",
          "British English, South London Brixton accent.",
          "A huge, slow, simple-minded troll.",
          "Slow and heavy.");

  @Test
  public void profilePrependsTheAudioProfileBlockBeforeTheEmotionTaggedTranscript()
      throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    backend(config)
        .synthesize(
            new SynthesisRequest(
                "You no take candle!",
                VoiceSpec.npc(NPCRace.TROLL, NPCGender.MALE),
                Emotion.ANGRY,
                PROFILE));

    JsonObject body =
        new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
    String input = body.get("input").getAsString();
    assertTrue(
        "the static guard line leads the block",
        input.startsWith("VOICE ONLY THE TRANSCRIPT BELOW THE DIVIDER"));
    assertTrue("the AUDIO PROFILE block follows the guard", input.contains("AUDIO PROFILE: Troll"));
    assertTrue("the director's notes carry the accent", input.contains("Brixton"));
    assertTrue(
        "the emotion-tagged transcript follows the divider, so the two layers compose",
        input.contains("#### TRANSCRIPT\n[angry] You no take candle!"));
  }

  @Test
  public void noProfileLeavesTheInputByteForByteUnchanged() throws Exception {
    // Same request as inputForEmotion: a null profile must produce exactly the pre-profile input.
    assertEquals("Hello & welcome", inputForEmotion(Emotion.NEUTRAL));
  }

  @Test
  public void cacheVariantFoldsInProfileSoDifferentProfilesNeverCollide() {
    OpenRouterTtsBackend backend = backend(new TestConfig());
    VoiceSpec voice = VoiceSpec.npc(NPCRace.TROLL, NPCGender.MALE);
    SynthesisRequest noProfile = new SynthesisRequest("a", voice, Emotion.NEUTRAL);
    SynthesisRequest withProfile = new SynthesisRequest("a", voice, Emotion.NEUTRAL, PROFILE);
    SynthesisRequest otherProfile =
        new SynthesisRequest(
            "a",
            voice,
            Emotion.NEUTRAL,
            new CharacterProfile("Goblin", "East London.", "Mischievous.", "Quick."));

    assertFalse(
        "a line with no profile carries no profile fragment, so existing cache stays valid",
        backend.cacheVariant(noProfile).contains("|p"));
    assertEquals(
        "the profiled variant is exactly the unprofiled one plus the profile content key",
        backend.cacheVariant(noProfile) + "|p" + PROFILE.cacheKey(),
        backend.cacheVariant(withProfile));
    assertNotEquals(
        "a profiled line never shares a variant with the same unprofiled line",
        backend.cacheVariant(noProfile),
        backend.cacheVariant(withProfile));
    assertNotEquals(
        "two different profiles never share a variant",
        backend.cacheVariant(withProfile),
        backend.cacheVariant(otherProfile));
  }

  @Test
  public void cacheVariantChangesWithSpeedSoStaleAudioIsNeverServed() {
    TestConfig config = new TestConfig();
    OpenRouterTtsBackend backend = backend(config);
    SynthesisRequest line =
        new SynthesisRequest("a", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);

    String atDefaultPace = backend.cacheVariant(line);
    config.speedPercent = 150;
    assertNotEquals(
        "a non-default pace must re-key so cached normal-pace audio is not served",
        atDefaultPace,
        backend.cacheVariant(line));
  }

  @Test
  public void cacheVariantFoldsInCapOnlyForLinesItWouldTruncate() {
    TestConfig config = new TestConfig();
    OpenRouterTtsBackend backend = backend(config);
    SynthesisRequest shortLine =
        new SynthesisRequest("ab", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);
    SynthesisRequest longLine =
        new SynthesisRequest(
            "abcdef", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);

    String shortAtDefault = backend.cacheVariant(shortLine);
    String longAtDefault = backend.cacheVariant(longLine);
    config.maxChars = 3;
    assertEquals(
        "a cap that cannot truncate this line leaves its key stable, avoiding needless re-bills",
        shortAtDefault,
        backend.cacheVariant(shortLine));
    assertNotEquals(
        "a cap that truncates this line must re-key so the full-length audio is not served",
        longAtDefault,
        backend.cacheVariant(longLine));
  }

  @Test
  @Parameters(method = "capLengthUnchangedCases")
  public void capLengthLeavesShortLinesAndDisabledCapUntouched(
      String text, int cap, String expected) {
    assertEquals(expected, OpenRouterTtsBackend.capLength(text, cap));
  }

  private Object[] capLengthUnchangedCases() {
    return new Object[] {
      new Object[] {"Hello there", 600, "Hello there"},
      new Object[] {"long", 0, "long"},
    };
  }

  @Test
  public void capLengthTruncatesAtSentenceBoundary() {
    String text = "First sentence is here. Second sentence runs on and on and on.";
    String capped = OpenRouterTtsBackend.capLength(text, 40);
    assertTrue("stays within the cap", capped.length() <= 40);
    assertEquals("cuts at the sentence boundary", "First sentence is here.", capped);
  }

  @Test
  public void capLengthFallsBackToWordBoundaryWhenNoSentenceEnd() {
    String text = "one two three four five six seven eight nine ten";
    String capped = OpenRouterTtsBackend.capLength(text, 20);
    assertTrue("stays within the cap", capped.length() <= 20);
    assertFalse("does not end on a dangling space", capped.endsWith(" "));
    assertTrue("cuts at a word boundary, not mid-word", text.startsWith(capped));
  }

  @Test
  public void capLengthHardCutsWhenThereIsNoBoundary() {
    String capped = OpenRouterTtsBackend.capLength("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 10);
    assertEquals("a single huge token is hard-cut to the cap", 10, capped.length());
  }

  @Test
  public void longLineIsCappedBeforeSending() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    config.maxChars = 30;
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    String longLine = "This is a long sentence. More text that should be dropped beyond the cap.";
    backend(config)
        .synthesize(
            new SynthesisRequest(
                longLine, VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL));

    JsonObject body =
        new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
    String input = body.get("input").getAsString();
    assertTrue("the sent input respects the cap", input.length() <= 30);
    assertEquals("it is truncated at the sentence boundary", "This is a long sentence.", input);
  }

  @Test
  public void speedParamIsSentOnlyWhenNonDefault() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));
    backend(config).synthesize(req());
    JsonObject defaultBody =
        new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
    assertFalse("normal pace sends no speed param", defaultBody.has("speed"));

    config.speedPercent = 150;
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));
    backend(config).synthesize(req());
    JsonObject fastBody =
        new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
    assertEquals(
        "a non-default pace is sent as a fractional speed",
        1.5,
        fastBody.get("speed").getAsDouble(),
        0.0001);
  }

  @Test
  public void everyRequestRoutesForThroughput() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    backend(config).synthesize(req());

    JsonObject body =
        new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
    assertEquals(
        "every TTS call asks for the fastest provider",
        "throughput",
        body.getAsJsonObject("provider").get("sort").getAsString());
  }

  @Test
  public void englishWithNoQuirkBypassesTheTranslationModel() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    // Default language English, default quirk None: the line must go straight to speech with no
    // translation hop, so a single enqueued speech response is enough.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    assertNotNull(backend(config).synthesize(req()));
    assertEquals("English + no quirk makes exactly one (speech) call", 1, server.getRequestCount());
    assertTrue(
        "the only request is the speech call, never the translation model",
        server.takeRequest().getPath().endsWith("/audio/speech"));
  }

  @Test
  @Parameters(method = "combineLanguageCases")
  public void combineLanguageAppendsTheQuirkOnlyWhenSet(
      String language, TTSDialogueConfig.SpeakingStyle style, String expected) {
    assertEquals(expected, OpenRouterTtsBackend.combineLanguage(language, style));
  }

  private Object[] combineLanguageCases() {
    return new Object[] {
      new Object[] {"English", TTSDialogueConfig.SpeakingStyle.NONE, "English"},
      new Object[] {"English", TTSDialogueConfig.SpeakingStyle.GEN_Z, "English Gen Z slang"},
      new Object[] {"French", TTSDialogueConfig.SpeakingStyle.PIRATE, "French pirate speak"},
      new Object[] {"  ", TTSDialogueConfig.SpeakingStyle.GEN_Z, "English Gen Z slang"},
      new Object[] {
        "English", TTSDialogueConfig.SpeakingStyle.UK_SLANG, "English with London Roadman Slang"
      },
      new Object[] {
        "English", TTSDialogueConfig.SpeakingStyle.IRISH_SLANG, "English with Dublin Slang"
      },
    };
  }

  @Test
  public void globalQuirkRoutesEnglishThroughTranslationAndKeepsTheBaseLanguageCode()
      throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    config.language = TTSDialogueConfig.SpokenLanguage.ENGLISH;
    config.npcQuirk = TTSDialogueConfig.SpeakingStyle.GEN_Z;
    // Even with English as the base, the NPC style forces the translation hop; it is served first.
    server.enqueue(
        new MockResponse().setResponseCode(200).setBody(chatResponse("no cap, well met")));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    backend(config)
        .synthesize(
            new SynthesisRequest(
                "Well met.", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL));

    RecordedRequest translation = server.takeRequest();
    assertTrue(
        "the quirk routes English through the translation hop",
        translation.getPath().endsWith("/chat/completions"));
    assertTrue(
        "the quirk is carried in the system prompt as a styled English target",
        translation.getBody().readUtf8().contains("Gen Z slang"));

    JsonObject speech =
        new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
    assertEquals(
        "the rewritten line is what is voiced",
        "no cap, well met",
        speech.get("input").getAsString());
    assertEquals(
        "the language_code stays the base language, not the quirk",
        "en-GB",
        speech.get("language_code").getAsString());
  }

  @Test
  public void globalQuirkPartitionsTheCacheKey() {
    TestConfig config = new TestConfig();
    OpenRouterTtsBackend backend = backend(config);
    SynthesisRequest line =
        new SynthesisRequest("a", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);

    String plain = backend.cacheVariant(line);
    assertFalse("plain English with no style adds no language fragment", plain.contains("|l"));

    config.npcQuirk = TTSDialogueConfig.SpeakingStyle.GEN_Z;
    assertNotEquals(
        "a style must not collide with the unstyled line", plain, backend.cacheVariant(line));
  }

  @Test
  public void speakerClassPicksTheStyleForTranslation() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    config.playerQuirk = TTSDialogueConfig.SpeakingStyle.GEN_Z;
    config.npcQuirk = TTSDialogueConfig.SpeakingStyle.NONE;
    VoiceSpec voice = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE);

    // The NPC line: NPC style None -> straight to speech, a single call, no translation hop.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));
    backend(config)
        .synthesize(
            new SynthesisRequest(
                "Well met.", voice, Emotion.NEUTRAL, null, false, /* player= */ false));
    assertEquals("an NPC line with NPC style None skips translation", 1, server.getRequestCount());
    assertTrue(
        "the NPC line's only request is the speech call",
        server.takeRequest().getPath().endsWith("/audio/speech"));

    // The player line: player style Gen Z -> translation hop first, then speech.
    server.enqueue(
        new MockResponse().setResponseCode(200).setBody(chatResponse("no cap, well met")));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));
    backend(config)
        .synthesize(
            new SynthesisRequest(
                "Well met.", voice, Emotion.NEUTRAL, null, false, /* player= */ true));
    RecordedRequest translation = server.takeRequest();
    assertTrue(
        "the player line routes through translation because the player style is set",
        translation.getPath().endsWith("/chat/completions"));
    assertTrue(
        "the player style is carried into the prompt",
        translation.getBody().readUtf8().contains("Gen Z slang"));
    assertTrue(
        "then the player line's speech call",
        server.takeRequest().getPath().endsWith("/audio/speech"));
  }

  @Test
  public void perSpeakerClassStylePartitionsTheCacheKey() {
    TestConfig config = new TestConfig();
    config.playerQuirk = TTSDialogueConfig.SpeakingStyle.GEN_Z;
    config.npcQuirk = TTSDialogueConfig.SpeakingStyle.PIRATE;
    OpenRouterTtsBackend backend = backend(config);
    VoiceSpec voice = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE);
    SynthesisRequest playerLine =
        new SynthesisRequest("a", voice, Emotion.NEUTRAL, null, false, /* player= */ true);
    SynthesisRequest npcLine =
        new SynthesisRequest("a", voice, Emotion.NEUTRAL, null, false, /* player= */ false);

    assertNotEquals(
        "a player-styled and an NPC-styled line of the same text get distinct cache keys",
        backend.cacheVariant(playerLine),
        backend.cacheVariant(npcLine));
  }

  @Test
  public void styleOnOneClassLeavesTheOtherClassUntranslated() {
    TestConfig config = new TestConfig();
    config.playerQuirk = TTSDialogueConfig.SpeakingStyle.NONE;
    config.npcQuirk = TTSDialogueConfig.SpeakingStyle.GEN_Z;
    OpenRouterTtsBackend backend = backend(config);
    VoiceSpec voice = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE);
    SynthesisRequest playerLine =
        new SynthesisRequest("a", voice, Emotion.NEUTRAL, null, false, /* player= */ true);
    SynthesisRequest npcLine =
        new SynthesisRequest("a", voice, Emotion.NEUTRAL, null, false, /* player= */ false);

    assertFalse(
        "the player line, player style None, carries no language fragment so it skips translation",
        backend.cacheVariant(playerLine).contains("|l"));
    assertTrue(
        "the NPC line, NPC style Gen Z, folds the styled language into its key",
        backend.cacheVariant(npcLine).contains("|l"));
  }

  @Test
  @Parameters(method = "needsTranslationCases")
  public void needsTranslationTreatsBlankAndEnglishAsNoTranslation(
      String language, boolean expected) {
    assertEquals(expected, OpenRouterTtsBackend.needsTranslation(language));
  }

  private Object[] needsTranslationCases() {
    return new Object[] {
      new Object[] {"English", false},
      new Object[] {"  english  ", false},
      new Object[] {"", false},
      new Object[] {null, false},
      new Object[] {"French", true},
    };
  }

  @Test
  public void nonEnglishTargetFoldsLanguageIntoTheCacheVariant() {
    TestConfig config = new TestConfig();
    OpenRouterTtsBackend backend = backend(config);
    SynthesisRequest line =
        new SynthesisRequest("a", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);

    String english = backend.cacheVariant(line);
    assertFalse("English (default) adds no language fragment", english.contains("|l"));

    config.language = TTSDialogueConfig.SpokenLanguage.FRENCH;
    String french = backend.cacheVariant(line);
    assertNotEquals(
        "the same line in another language must not share a cache key", english, french);
    assertTrue("the language is folded in", french.contains("|lfrench"));
  }

  @Test
  public void nonEnglishTargetTranslatesBeforeVoicingAndSetsLanguageCode() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    config.language = TTSDialogueConfig.SpokenLanguage.FRENCH;
    // The translator call is served first, then the speech call (same mock server, queue order).
    server.enqueue(new MockResponse().setResponseCode(200).setBody(chatResponse("Bonjour")));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    backend(config)
        .synthesize(
            new SynthesisRequest(
                "Hello", VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL));

    RecordedRequest first = server.takeRequest();
    assertTrue("the translation hop runs first", first.getPath().endsWith("/chat/completions"));
    RecordedRequest second = server.takeRequest();
    assertTrue("then the speech call", second.getPath().endsWith("/audio/speech"));
    JsonObject body = new JsonParser().parse(second.getBody().readUtf8()).getAsJsonObject();
    assertEquals(
        "the spoken transcript is the translation, not the source",
        "Bonjour",
        body.get("input").getAsString());
    assertEquals(
        "the BCP-47 language_code matches the target",
        "fr-FR",
        body.get("language_code").getAsString());
  }

  @Test
  public void skipTranslationVoicesVerbatimUnderANonEnglishTarget() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    config.language = TTSDialogueConfig.SpokenLanguage.FRENCH;
    config.npcQuirk = TTSDialogueConfig.SpeakingStyle.GEN_Z;
    // A skip-translation line bypasses the hop entirely, so only the speech call is enqueued.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    backend(config)
        .synthesize(
            new SynthesisRequest(
                "Hello",
                VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE),
                Emotion.NEUTRAL,
                null,
                true));

    assertEquals(
        "skip-translation makes exactly one (speech) call, never the translation model",
        1,
        server.getRequestCount());
    RecordedRequest speech = server.takeRequest();
    assertTrue("the only request is the speech call", speech.getPath().endsWith("/audio/speech"));
    JsonObject body = new JsonParser().parse(speech.getBody().readUtf8()).getAsJsonObject();
    assertEquals(
        "the transcript is the source text exactly as typed, untranslated",
        "Hello",
        body.get("input").getAsString());
    assertFalse("an untranslated line carries no language_code", body.has("language_code"));
  }

  @Test
  public void normalLineStillTranslatesWhenSkipTranslationIsOff() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    config.language = TTSDialogueConfig.SpokenLanguage.FRENCH;
    server.enqueue(new MockResponse().setResponseCode(200).setBody(chatResponse("Bonjour")));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));

    backend(config)
        .synthesize(
            new SynthesisRequest(
                "Hello",
                VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE),
                Emotion.NEUTRAL,
                null,
                false));

    assertTrue(
        "a normal request still runs the translation hop first",
        server.takeRequest().getPath().endsWith("/chat/completions"));
    assertTrue("then the speech call", server.takeRequest().getPath().endsWith("/audio/speech"));
  }

  @Test
  public void skipTranslationOmitsTheLanguageFragmentFromTheCacheVariant() {
    TestConfig config = new TestConfig();
    config.language = TTSDialogueConfig.SpokenLanguage.FRENCH;
    OpenRouterTtsBackend backend = backend(config);
    VoiceSpec voice = VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE);

    SynthesisRequest dialogue = new SynthesisRequest("a", voice, Emotion.NEUTRAL, null, false);
    SynthesisRequest publicChat = new SynthesisRequest("a", voice, Emotion.NEUTRAL, null, true);

    assertTrue(
        "a translated dialogue line still folds the language in",
        backend.cacheVariant(dialogue).contains("|lfrench"));
    assertFalse(
        "a skip-translation line keeps the plain pre-translation key",
        backend.cacheVariant(publicChat).contains("|l"));
    assertNotEquals(
        "so an untranslated public-chat clip never collides with a translated dialogue line of the"
            + " same text",
        backend.cacheVariant(dialogue),
        backend.cacheVariant(publicChat));
  }

  @Test
  public void translationFailureFailsTheLineWithoutCallingSpeech() {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    config.language = TTSDialogueConfig.SpokenLanguage.FRENCH;
    server.enqueue(new MockResponse().setResponseCode(500).setBody("translation down"));

    int[] notices = {0};
    OpenRouterTtsBackend backend = backend(config);
    backend.setNotice(msg -> notices[0]++);

    assertNull("a failed translation fails the line gracefully", backend.synthesize(req()));
    assertEquals("only the translation call was attempted", 1, server.getRequestCount());
    assertEquals("the failure surfaces one notice", 1, notices[0]);
  }

  @Test
  public void rateLimitThrottlesThenClearsOnACleanCall() {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    OpenRouterTtsBackend backend = backend(config);
    assertFalse("a fresh backend is not throttled", backend.isThrottled());

    server.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));
    backend.synthesize(req());
    assertTrue("a 429 opens a back-off window so prefetch holds off", backend.isThrottled());

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1}))));
    backend.synthesize(req());
    assertFalse("a clean call clears the back-off", backend.isThrottled());
  }

  @Test
  public void nonSuccessResponseReturnsNullWithOneNotice() {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized"));

    int[] notices = {0};
    OpenRouterTtsBackend backend = backend(config);
    backend.setNotice(msg -> notices[0]++);

    Pcm pcm = backend.synthesize(req());

    assertNull("a non-2xx fails the line gracefully", pcm);
    assertEquals("the failure surfaces a one-time notice", 1, notices[0]);
  }

  @Test
  public void transientEmptyBodyIsRetriedOnceAndRecovers() throws Exception {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    // First call comes back as an empty 200 (the transient glitch); the immediate retry succeeds.
    server.enqueue(new MockResponse().setResponseCode(200).setBody(""));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(RawPcmDecoderTest.raw(new short[] {1, 2, 3}))));

    int[] notices = {0};
    OpenRouterTtsBackend backend = backend(config);
    backend.setNotice(msg -> notices[0]++);

    assertNotNull("a single empty 200 is recovered by the retry", backend.synthesize(req()));
    assertEquals("the line was attempted twice", 2, server.getRequestCount());
    assertEquals("a recovered line surfaces no failure notice", 0, notices[0]);
  }

  @Test
  public void repeatedEmptyBodyFailsAfterOneRetry() {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(new MockResponse().setResponseCode(200).setBody(""));
    server.enqueue(new MockResponse().setResponseCode(200).setBody(""));

    int[] notices = {0};
    OpenRouterTtsBackend backend = backend(config);
    backend.setNotice(msg -> notices[0]++);

    assertNull("two empty bodies in a row fail the line", backend.synthesize(req()));
    assertEquals("it retries exactly once, never storms", 2, server.getRequestCount());
    assertEquals("the persistent failure surfaces one notice", 1, notices[0]);
  }

  @Test
  public void emptyBodyReturnsNull() {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    // A 200 whose body is an odd byte count is not whole 16-bit PCM, so it fails to decode.
    server.enqueue(
        new MockResponse().setResponseCode(200).setBody(new Buffer().write(new byte[] {1, 2, 3})));

    assertNull("undecodable audio fails the line gracefully", backend(config).synthesize(req()));
  }

  @Test
  public void unavailableBackendDoesNotCallNetwork() {
    TestConfig config = new TestConfig(); // no key
    OpenRouterTtsBackend backend = backend(config);

    String[] last = {null};
    int[] notices = {0};
    backend.setNotice(
        msg -> {
          notices[0]++;
          last[0] = msg;
        });

    assertNull(backend.synthesize(req()));
    assertEquals("no HTTP request when unavailable", 0, server.getRequestCount());
    assertEquals("the missing-key notice fires", 1, notices[0]);
    assertEquals(
        "it surfaces the shared no-key message", OpenRouterTtsBackend.NO_KEY_NOTICE, last[0]);
  }

  @Test
  public void missingKeyNoticeFiresOnEveryAttempt() {
    TestConfig config = new TestConfig(); // no key
    OpenRouterTtsBackend backend = backend(config);

    int[] notices = {0};
    backend.setNotice(msg -> notices[0]++);

    for (int i = 0; i < 3; i++) {
      assertNull("each no-key line fails gracefully", backend.synthesize(req()));
    }

    assertEquals("the no-key notice is not deduped: it fires on every attempt", 3, notices[0]);
    assertEquals("still never hits the network", 0, server.getRequestCount());
  }

  @Test
  public void noticeFiresAtMostOnceAcrossRepeatedFailures() {
    TestConfig config = new TestConfig();
    config.key = "sk-or-abc";
    server.enqueue(new MockResponse().setResponseCode(500));
    server.enqueue(new MockResponse().setResponseCode(500));

    int[] notices = {0};
    OpenRouterTtsBackend backend = backend(config);
    backend.setNotice(msg -> notices[0]++);

    backend.synthesize(req());
    backend.synthesize(req());

    assertEquals("repeated failures warn once", 1, notices[0]);
  }
}
