package com.grahambartley.synthesis;

/**
 * Builds the SSML document POSTed to the Azure Speech REST endpoint.
 *
 * <p>{@link Emotion} maps to an Azure {@code mstts:express-as} style: {@code HAPPY→cheerful},
 * {@code SAD→sad}, {@code ANGRY→angry}, {@code SCARED→terrified}. {@link Emotion#NEUTRAL} (and any
 * emotion the chosen voice does not support) renders as plain delivery with no {@code express-as}
 * wrapper. Styled output carries a {@code styledegree} for a sensible intensity. The {@code mstts}
 * namespace is declared on the root {@code speak} element and all user text is XML-escaped.
 *
 * <p>{@link BackendProvider} already downgrades unsupported emotions to {@link Emotion#NEUTRAL}
 * before synthesis; the additional per-voice style check here is the documented Azure exception:
 * even a supported emotion degrades to plain when the resolved voice lacks that specific style.
 */
final class AzureSsml {

  /**
   * Intensity applied to every emitted style. 1.0 is the Azure default; this is a touch stronger.
   */
  private static final String STYLE_DEGREE = "1.5";

  private AzureSsml() {}

  /** The Azure style name for an emotion, or {@code null} for neutral / no style. */
  static String styleFor(Emotion emotion) {
    if (emotion == null) {
      return null;
    }
    switch (emotion) {
      case HAPPY:
        return "cheerful";
      case SAD:
        return "sad";
      case ANGRY:
        return "angry";
      case SCARED:
        return "terrified";
      case NEUTRAL:
      default:
        return null;
    }
  }

  /**
   * Builds an SSML document for the given text, voice, and emotion. The emotion is rendered as a
   * style only when it is non-neutral and the voice supports that style; otherwise the inner text
   * is emitted plainly.
   */
  static String build(String text, String voice, Emotion emotion) {
    String escaped = escapeXml(text == null ? "" : text);
    String style = styleFor(emotion);
    boolean styled = style != null && AzureVoiceStyles.supports(voice, style);

    StringBuilder sb = new StringBuilder();
    sb.append("<speak version=\"1.0\"")
        .append(" xmlns=\"http://www.w3.org/2001/10/synthesis\"")
        .append(" xmlns:mstts=\"https://www.w3.org/2001/mstts\"")
        .append(" xml:lang=\"en-US\">");
    sb.append("<voice name=\"").append(escapeXml(voice)).append("\">");
    if (styled) {
      sb.append("<mstts:express-as style=\"")
          .append(style)
          .append("\" styledegree=\"")
          .append(STYLE_DEGREE)
          .append("\">")
          .append(escaped)
          .append("</mstts:express-as>");
    } else {
      sb.append(escaped);
    }
    sb.append("</voice>");
    sb.append("</speak>");
    return sb.toString();
  }

  /** Minimal XML escaping for text and attribute values placed inside the SSML document. */
  static String escapeXml(String raw) {
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      switch (c) {
        case '&':
          sb.append("&amp;");
          break;
        case '<':
          sb.append("&lt;");
          break;
        case '>':
          sb.append("&gt;");
          break;
        case '"':
          sb.append("&quot;");
          break;
        case '\'':
          sb.append("&apos;");
          break;
        default:
          sb.append(c);
      }
    }
    return sb.toString();
  }
}
