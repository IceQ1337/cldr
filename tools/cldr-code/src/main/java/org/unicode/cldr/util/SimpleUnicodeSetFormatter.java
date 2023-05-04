package org.unicode.cldr.util;

import com.google.common.base.Splitter;
import com.ibm.icu.lang.CharSequences;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.text.UTF16;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.ULocale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Goal is a very simple format for UnicodeSet, that keeps vetters from having to know about \ for
 * quoting or {...} for strings, or $ for FFFF. We do this by using spaces to always separate
 * different characters, and special syntax for ranges, escaped hex, and named entities. There are 2
 * special characters:
 *
 * <ul>
 *   <li>➖ a range, but if between two code points
 *   <li>⦕ start of hex or named escape, but only if followed by [A-Fa-f0-9]+ ⦖
 * </ul>
 *
 * <b>EBNF</b><br>
 * result = item (" " item)*<br>
 * item = string | range | codePoint<br>
 * string = codePoint+<br>
 * range = codePoint "➖" codePoint<br>
 * codepoint = literal // excludes " ", "⦕", "⦖"<br>
 * codepoint = "⦕" (namedEscape | hex) "⦖"<br>
 * namedEscape = [A-Fa-f0-9]+ // as per CodePointEscape<br>
 * hex = [A-Fa-f0-9]{2,6} // must be valid code point 0x0..0x10FFFF<br>
 * ⦕ was chosen to be avoid special use of \\u or \x<br>
 *
 * @author markdavis
 */
public class SimpleUnicodeSetFormatter {
    public static Normalizer2 nfc = Normalizer2.getNFCInstance();

    public static final Comparator<String> BASIC_COLLATOR;

    private static final int DEFAULT_MAX_DISALLOW_RANGES = 199;

    static {
        Collator temp = Collator.getInstance(ULocale.ROOT);
        temp.setStrength(Collator.IDENTICAL);
        temp.freeze();
        BASIC_COLLATOR = (Comparator<String>) (Comparator<?>) temp;
    }

    private final Comparator<String> comparator;
    private final UnicodeSet forceHex;
    private final int maxDisallowRanges;
    private final UTF16.StringComparator codepointComparator =
            new UTF16.StringComparator(true, false, 0);

    /**
     * Create a simple formatter, with a comparator for the ordering and a UnicodeSet of characters
     * that are to use hex. Immutable (if the collator is).
     *
     * @param col — collator. The default is BASIC_COLLATOR, which is the root collator.
     * @param forceHex - UnicodeSet to force to be hex. It will be frozen if not already. Warning:
     *     may not round-trip unless it includes all of CodePointEscaper.getNamedEscapes()
     * @param maxDisallowRanges — under this number, there will be no ranges; at or above there may
     *     be ranges, and the collator will be disregarded.
     */
    public SimpleUnicodeSetFormatter(
            Comparator<String> col, UnicodeSet forceHex, int maxDisallowRanges) {
        // collate, but preserve non-equivalents
        this.comparator =
                new MultiComparator(col == null ? BASIC_COLLATOR : col, codepointComparator);
        this.forceHex = forceHex == null ? CodePointEscaper.FORCE_ESCAPE : forceHex.freeze();
        this.maxDisallowRanges = maxDisallowRanges;
    }

    public SimpleUnicodeSetFormatter(Comparator<String> col, UnicodeSet forceHex) {
        this(col, forceHex, DEFAULT_MAX_DISALLOW_RANGES);
    }

    public SimpleUnicodeSetFormatter(Comparator<String> col) {
        this(col, null, 255);
    }

    public SimpleUnicodeSetFormatter() {
        this(null, null, 255);
    }

    public String format(UnicodeSet input) {
        final boolean allowRanges = input.size() >= maxDisallowRanges;
        StringBuilder result = new StringBuilder();
        Collection<String> sorted =
                input.addAllTo(allowRanges ? new ArrayList<>() : new TreeSet<>(comparator));
        //                : transformAndAddAllTo(
        //                        input, null, new TreeSet<>(comparator)); // x -> nfc.normalize(x)
        int firstOfRange = -2;
        int lastOfRange = -2;
        for (String item : sorted) {
            int cp = CharSequences.getSingleCodePoint(item);
            if (cp == Integer.MAX_VALUE) { // string
                if (lastOfRange >= 0) {
                    if (firstOfRange != lastOfRange) {
                        result.append(
                                firstOfRange + 1 != lastOfRange
                                        ? CodePointEscaper.RANGE_SYNTAX
                                        : ' ');
                        appendWithHex(result, lastOfRange, forceHex);
                    }
                    firstOfRange = lastOfRange = -2;
                }
                if (result.length() > 0) {
                    result.append(' ');
                }
                appendWithHex(result, item, forceHex);
            } else if (allowRanges && lastOfRange == cp - 1) {
                ++lastOfRange;
            } else {
                if (firstOfRange != lastOfRange) {
                    result.append(
                            firstOfRange + 1 != lastOfRange ? CodePointEscaper.RANGE_SYNTAX : ' ');
                    appendWithHex(result, lastOfRange, forceHex);
                }
                if (result.length() > 0) {
                    result.append(' ');
                }
                appendWithHex(result, cp, forceHex);
                firstOfRange = lastOfRange = cp;
            }
        }
        if (firstOfRange != lastOfRange) {
            result.append(firstOfRange + 1 != lastOfRange ? CodePointEscaper.RANGE_SYNTAX : ' ');
            appendWithHex(result, lastOfRange, forceHex);
        }
        return result.toString();
    }

    public static final StringBuilder appendWithHex(
            StringBuilder ap, CharSequence s, UnicodeSet forceHex) {
        for (int cp : With.codePointArray(s)) {
            appendWithHex(ap, cp, forceHex);
        }
        return ap;
    }

    public static StringBuilder appendWithHex(StringBuilder ap, int cp, UnicodeSet forceHex) {
        if (!forceHex.contains(cp)) {
            ap.appendCodePoint(cp);
        } else {
            ap.append(CodePointEscaper.ESCAPE_START)
                    .append(CodePointEscaper.toAbbreviationOrHex(cp))
                    .append(CodePointEscaper.ESCAPE_END);
        }
        return ap;
    }

    static final Splitter SPACE_SPLITTER = Splitter.on(' ').omitEmptyStrings();

    public UnicodeSet parse(String input) {
        UnicodeSet result = new UnicodeSet();
        // Note: could be optimized but probably not worth the effort

        for (String word : SPACE_SPLITTER.split(input)) {
            // parts between spaces can be single code points, or strings, or ranges of single code
            // points
            int rangePos = word.indexOf(CodePointEscaper.RANGE_SYNTAX);
            if (rangePos < 0) {
                result.add(unescape(word));
            } else {
                int range2Pos = word.indexOf(CodePointEscaper.RANGE_SYNTAX, rangePos + 1);
                if (range2Pos >= 0) {
                    throw new IllegalArgumentException("Two range marks: " + word);
                } else if (rangePos == 0 || rangePos == word.length() - 1) {
                    throw new IllegalArgumentException(
                            "A range mark must have characters on both sides: " + word);
                }
                // get the code points on either side
                int first = CharSequences.getSingleCodePoint(unescape(word.substring(0, rangePos)));
                int second =
                        CharSequences.getSingleCodePoint(unescape(word.substring(rangePos + 1)));
                if (first == Integer.MAX_VALUE || second == Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "A range mark must have single code points on both sides: " + word);
                }
                result.add(first, second);
            }
        }
        return result;
    }

    /** Unescape a whole string. */
    private CharSequence unescape(String word) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < word.length(); ) {
            int escapeStart = word.indexOf(CodePointEscaper.ESCAPE_START, i);
            if (escapeStart < 0) {
                final String toAppend = i == 0 ? word : word.substring(i);
                if (toAppend.indexOf(CodePointEscaper.ESCAPE_END) >= 0) {
                    throw new IllegalArgumentException(
                            "Escape end "
                                    + CodePointEscaper.ESCAPE_END
                                    + " without escape start "
                                    + CodePointEscaper.ESCAPE_START
                                    + ": "
                                    + word);
                }
                result.append(toAppend);
                break;
            }
            final String toAppend = word.substring(i, escapeStart);
            if (toAppend.indexOf(CodePointEscaper.ESCAPE_END) >= 0) {
                throw new IllegalArgumentException(
                        "Escape end "
                                + CodePointEscaper.ESCAPE_END
                                + " without escape start "
                                + CodePointEscaper.ESCAPE_START
                                + ": "
                                + word);
            }
            result.append(toAppend);
            int interiorStart = escapeStart + 1;
            int escapeEnd = word.indexOf(CodePointEscaper.ESCAPE_END, interiorStart);
            if (escapeEnd < 0) {
                throw new IllegalArgumentException(
                        "Escape start "
                                + CodePointEscaper.ESCAPE_START
                                + " without escape end "
                                + CodePointEscaper.ESCAPE_END
                                + ": "
                                + word);
            }
            result.appendCodePoint(
                    CodePointEscaper.fromAbbreviationOrHex(
                            word.substring(interiorStart, escapeEnd)));
            i = escapeEnd + 1;
        }
        return result;
    }

    public static UnicodeSet transform(UnicodeSet expected, Function<String, String> function) {
        UnicodeSet result = new UnicodeSet();
        for (String s : expected) {
            String t = function.apply(s);
            result.add(t);
        }
        return result;
    }

    public static <T extends Collection<String>> T transformAndAddAllTo(
            UnicodeSet expected, Function<String, String> function, T target) {
        for (String s : expected) {
            String t = function == null ? s : function.apply(s);
            target.add(t);
        }
        return target;
    }
}