package com.crm.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shartnoma shabloni uchun cheklangan HTML (leaves-exams-contracts §6.3, C-07).
 *
 * <p>{@link #sanitize} natijasi — <b>yaxshi shakllangan XHTML bo'lagi</b> (OpenHTMLtoPDF XML
 * parseri va brauzer uchun bir xil):
 * <ul>
 *   <li>faqat {@link #ALLOWED} teglar, <b>atributsiz</b> (on*, style, href — hammasi tashlanadi);</li>
 *   <li>{@code script/style} bloklari mazmuni bilan o'chiriladi, izohlar ham;</li>
 *   <li>matn har doim qayta escape qilinadi (avval ma'lum entitylar ochiladi — funksiya idempotent:
 *       {@code sanitize(sanitize(x)) == sanitize(x)});</li>
 *   <li>yopilmagan teglar yopiladi, ortiqcha yopuvchi teg tashlanadi;</li>
 *   <li>shablonda birorta ham ruxsat etilgan teg bo'lmasa — oddiy matn: yangi qator → {@code <br/>}.</li>
 * </ul>
 * Tashqi kutubxona (jsoup) ataylab ishlatilmaydi — atributsiz oq ro'yxat kichik va to'liq tekshiriladi.
 */
public final class ContractHtml {

    public static final Set<String> ALLOWED = Set.of(
        "p", "b", "strong", "i", "em", "u", "br", "h1", "h2", "h3",
        "table", "thead", "tbody", "tr", "td", "th", "ul", "ol", "li");

    private static final Pattern DANGEROUS_BLOCK =
        Pattern.compile("(?is)<\\s*(script|style)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    /** Teg nomi {@code <} dan darhol keyin; "a < b > c" kabi matn teg emas. */
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)(\\s[^<>]*|/)?>");
    private static final Pattern ENTITY =
        Pattern.compile("&(amp|lt|gt|quot|apos|nbsp|#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6});");

    private ContractHtml() {
    }

    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String source = COMMENT.matcher(DANGEROUS_BLOCK.matcher(input).replaceAll("")).replaceAll("");
        boolean markup = hasAllowedTag(source);
        StringBuilder out = new StringBuilder(source.length() + 32);
        Deque<String> open = new ArrayDeque<>();
        Matcher m = TAG.matcher(source);
        int pos = 0;
        while (m.find()) {
            appendText(out, source.substring(pos, m.start()), markup);
            pos = m.end();
            String name = m.group(2).toLowerCase(Locale.ROOT);
            if (!ALLOWED.contains(name)) {
                continue;
            }
            boolean closing = !m.group(1).isEmpty();
            boolean selfClosing = m.group(3) != null && m.group(3).trim().endsWith("/");
            if ("br".equals(name)) {
                if (!closing) {
                    out.append("<br/>");
                }
                continue;
            }
            if (closing) {
                if (open.contains(name)) {
                    while (!open.isEmpty()) {
                        String top = open.pop();
                        out.append("</").append(top).append('>');
                        if (top.equals(name)) {
                            break;
                        }
                    }
                }
                continue;
            }
            out.append('<').append(name).append('>');
            if (selfClosing) {
                out.append("</").append(name).append('>');
            } else {
                open.push(name);
            }
        }
        appendText(out, source.substring(pos), markup);
        while (!open.isEmpty()) {
            out.append("</").append(open.pop()).append('>');
        }
        return out.toString();
    }

    /** Matn qiymati (belgi o'rniga qo'yiladigan) — XML/HTML uchun xavfsiz. */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    // XML 1.0 da taqiqlangan boshqaruv belgilari tashlanadi (PDF parseri yiqilmasin)
                    if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean hasAllowedTag(String source) {
        Matcher m = TAG.matcher(source);
        while (m.find()) {
            if (ALLOWED.contains(m.group(2).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void appendText(StringBuilder out, String raw, boolean markup) {
        if (raw.isEmpty()) {
            return;
        }
        String escaped = escape(unescape(raw));
        if (!markup) {
            escaped = escaped.replace("\r\n", "\n").replace("\n", "<br/>\n");
        }
        out.append(escaped);
    }

    private static String unescape(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher m = ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(decode(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String decode(String entity) {
        return switch (entity) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";
            default -> {
                int cp = entity.startsWith("#x") || entity.startsWith("#X")
                    ? Integer.parseInt(entity.substring(2), 16)
                    : Integer.parseInt(entity.substring(1));
                yield Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "";
            }
        };
    }
}
