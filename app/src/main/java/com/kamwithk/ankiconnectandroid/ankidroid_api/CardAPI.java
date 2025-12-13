package com.kamwithk.ankiconnectandroid.ankidroid_api;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

import com.ichi2.anki.FlashCardsContract;
import com.kamwithk.ankiconnectandroid.debug.DebugLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CardAPI {
    private final ContentResolver resolver;
    private final Context context;

    private static final String TAG = "AnkiConnectAndroid";

    // Some AnkiDroid builds do not support the notes_v2 URI at all.
    private static volatile Boolean NOTES_V2_SUPPORTED = null;
    private static volatile Boolean NOTES_V2_CARDS_SUPPORTED = null;

    private static final String[] CARD_INFO_PROJECTION = {
            FlashCardsContract.Note._ID,
            FlashCardsContract.Note.TAGS,
            FlashCardsContract.Note.FLAGS
    };

    public CardAPI(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = context.getContentResolver();
    }

    static class CardInfo {
        private final long cardId;
        private final long note;
        private final int flags;
        private final List<String> tags;

        public CardInfo(long cardId, long note, int flags, List<String> tags) {
            this.cardId = cardId;
            this.note = note;
            this.flags = flags;
            this.tags = tags;
        }

        public long getCardId() {
            return cardId;
        }

        public long getNote() {
            return note;
        }

        public int getFlags() {
            return flags;
        }

        public List<String> getTags() {
            return tags;
        }
    }

    /**
     * Best-effort implementation of AnkiConnect's cardsInfo.
     *
     * AnkiDroid's public FlashCardsContract API does not expose the card row id directly.
     * We therefore resolve a card id to its note via the Anki browser search syntax (cid:<id>)
     * on the Note content provider.
     */
    public List<CardInfo> cardsInfo(ArrayList<Long> cardIds) {
        List<CardInfo> result = new ArrayList<>(cardIds.size());

        for (long cardId : cardIds) {
            CardInfo info = getSingleCardInfo(cardId);
            result.add(info);
        }

        return result;
    }

    /**
     * Best-effort implementation of AnkiConnect's findCards.
     *
     * We query notes using the same browser-search syntax and then expand each note to its cards
     * via the /notes/<nid>/cards sub-URI.
     */
    public List<Long> findCards(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        Cursor noteCursor = resolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                new String[]{FlashCardsContract.Note._ID},
                query,
                null,
                null
        );

        if (noteCursor == null) {
            DebugLog.append(context, "findCards: query='" + query + "' -> noteCursor=null");
            return Collections.emptyList();
        }

        try (noteCursor) {
            int nidIdx = noteCursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID);
            List<Long> cardIds = new ArrayList<>();
            while (noteCursor.moveToNext()) {
                long noteId = noteCursor.getLong(nidIdx);
                cardIds.addAll(getCardIdsForNote(noteId));
            }
            DebugLog.append(context, "findCards: query='" + query + "' -> cards=" + cardIds);
            return cardIds;
        } catch (Exception e) {
            DebugLog.append(context, "findCards: query='" + query + "' -> exception=" + e);
            return Collections.emptyList();
        }
    }

    private CardInfo getSingleCardInfo(long cardId) {
        // First try a true Anki card id lookup (cid:<id>). If it fails, fall back to decoding
        // a synthetic id created from (noteId, ord) (see CardIdCodec).
        Cursor cursor = null;
        boolean resolvedViaCid = false;
        try {
            cursor = resolver.query(
                    FlashCardsContract.Note.CONTENT_URI,
                    CARD_INFO_PROJECTION,
                    "cid:" + cardId,
                    null,
                    null
            );
            resolvedViaCid = (cursor != null && cursor.moveToFirst());
        } catch (Exception ignored) {
            // ignore
        }

        long noteId = 0;
        int flags = 0;
        String rawTags = null;

        try {
            if (resolvedViaCid) {
                int idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID);
                int tagsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS);
                int flagsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.FLAGS);

                noteId = cursor.getLong(idIdx);
                flags = cursor.getInt(flagsIdx);
                rawTags = cursor.getString(tagsIdx);
            } else {
                if (cursor != null) {
                    cursor.close();
                    cursor = null;
                }

                if (!CardIdCodec.looksPacked(cardId)) {
                    DebugLog.append(context, "cardsInfo: cardId=" + cardId + " -> unresolved (not packed, cid failed)");
                    return new CardInfo(cardId, 0, 0, Collections.emptyList());
                }

                noteId = CardIdCodec.unpackNoteId(cardId);
                String noteIdStr = Long.toString(noteId);
                Uri noteUriV2 = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI_V2, noteIdStr);
                Uri noteUriLegacy = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteIdStr);

                Boolean v2Supported = NOTES_V2_SUPPORTED;
                if (v2Supported == null || v2Supported) {
                    try {
                        cursor = resolver.query(noteUriV2, CARD_INFO_PROJECTION, null, null, null);
                        NOTES_V2_SUPPORTED = true;
                    } catch (IllegalArgumentException e) {
                        NOTES_V2_SUPPORTED = false;
                        DebugLog.append(context, "cardsInfo: notes_v2 not supported (disabling v2): " + e.getMessage());
                        cursor = null;
                    } catch (Exception e) {
                        DebugLog.append(context, "cardsInfo: cardId=" + cardId + " -> note(v2) query exception=" + e);
                        cursor = null;
                    }
                }
                if (cursor == null) {
                    cursor = resolver.query(noteUriLegacy, CARD_INFO_PROJECTION, null, null, null);
                }
                if (cursor == null || !cursor.moveToFirst()) {
                    DebugLog.append(context, "cardsInfo: cardId=" + cardId + " -> note lookup failed noteId=" + noteId);
                    return new CardInfo(cardId, noteId, 0, Collections.emptyList());
                }

                int tagsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS);
                int flagsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.FLAGS);
                flags = cursor.getInt(flagsIdx);
                rawTags = cursor.getString(tagsIdx);

                Integer cardLevelFlags = tryGetCardLevelFlagsByOrd(noteId, CardIdCodec.unpackCardOrd(cardId));
                if (cardLevelFlags != null) {
                    flags = cardLevelFlags;
                }
            }

            List<String> tags;
            if (rawTags == null || rawTags.trim().isEmpty()) {
                tags = Collections.emptyList();
            } else {
                String[] split = Utility.splitTags(rawTags);
                tags = split == null ? Collections.emptyList() : Arrays.asList(split);
            }

            // Best-effort: if this was a true card id, try to find per-card flags by scanning the
            // /notes/<nid>/cards cursor row for that id.
            if (resolvedViaCid) {
                Integer cardLevelFlags = tryGetCardLevelFlags(noteId, cardId);
                if (cardLevelFlags != null) {
                    flags = cardLevelFlags;
                }
            }

            Log.d(TAG, "cardsInfo: cardId=" + cardId + " noteId=" + noteId + " flags=" + flags);
            DebugLog.append(context, "cardsInfo: cardId=" + cardId + " noteId=" + noteId + " flags=" + flags);
            return new CardInfo(cardId, noteId, flags, tags);
        } catch (Exception e) {
            DebugLog.append(context, "cardsInfo: cardId=" + cardId + " -> exception=" + e);
            return new CardInfo(cardId, noteId, 0, Collections.emptyList());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private Integer tryGetCardLevelFlagsByOrd(long noteId, int ord) {
        String noteIdStr = Long.toString(noteId);
        Uri noteUriV2 = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI_V2, noteIdStr);
        Uri cardsUriV2 = Uri.withAppendedPath(noteUriV2, "cards");
        Uri noteUriLegacy = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteIdStr);
        Uri cardsUriLegacy = Uri.withAppendedPath(noteUriLegacy, "cards");

        Cursor cursor = null;
        Boolean v2CardsSupported = NOTES_V2_CARDS_SUPPORTED;
        if (v2CardsSupported == null || v2CardsSupported) {
            try {
                cursor = resolver.query(cardsUriV2, null, null, null, null);
                NOTES_V2_CARDS_SUPPORTED = true;
            } catch (IllegalArgumentException e) {
                NOTES_V2_CARDS_SUPPORTED = false;
                DebugLog.append(context, "cardsInfo: notes_v2/cards not supported (disabling v2): " + e.getMessage());
            } catch (Exception e) {
                DebugLog.append(context, "cardsInfo: noteId=" + noteId + " ord=" + ord + " -> cards(v2) query exception=" + e);
            }
        }

        if (cursor == null) {
            try {
                cursor = resolver.query(cardsUriLegacy, null, null, null, null);
            } catch (Exception e) {
                DebugLog.append(context, "cardsInfo: noteId=" + noteId + " ord=" + ord + " -> cards(legacy) query exception=" + e);
                return null;
            }
        }

        if (cursor == null) {
            return null;
        }

        final Cursor finalCursor = cursor;
        try (finalCursor) {
            int ordIdx = finalCursor.getColumnIndex(FlashCardsContract.Card.CARD_ORD);
            if (ordIdx < 0) {
                return null;
            }

            int colCount = finalCursor.getColumnCount();
            String[] colNames = finalCursor.getColumnNames();
            int flagsIdx = -1;
            for (int i = 0; i < colCount; i++) {
                String name = colNames[i];
                if (name == null) continue;
                String lc = name.toLowerCase(Locale.ROOT);
                if (lc.equals("flag") || lc.equals("flags") || lc.contains("flag")) {
                    flagsIdx = i;
                    break;
                }
            }
            if (flagsIdx < 0) {
                DebugLog.append(
                        context,
                        "cardsInfo: noteId=" + noteId + " ord=" + ord + " -> no flag-like column; cols=" + summarizeColumns(colNames)
                );
            }

            while (finalCursor.moveToNext()) {
                int rowOrd;
                try {
                    rowOrd = (int) finalCursor.getLong(ordIdx);
                } catch (Exception ignored) {
                    continue;
                }
                if (rowOrd != ord) {
                    continue;
                }

                // 1) Preferred: a named flags column
                if (flagsIdx >= 0) {
                    try {
                        if (finalCursor.getType(flagsIdx) == Cursor.FIELD_TYPE_INTEGER) {
                            int value = (int) finalCursor.getLong(flagsIdx);
                            DebugLog.append(context, "cardsInfo: noteId=" + noteId + " ord=" + ord + " -> flags=" + value + " (col=" + safeColName(colNames, flagsIdx) + ")");
                            return value;
                        }
                    } catch (Exception ignored) {
                        return null;
                    }
                }

                // 2) Heuristic: many schemas store card flag as a small int 0..7.
                // If we can find exactly one such integer column on the ord-matching row,
                // treat it as the flag value.
                Integer heuristic = guessFlagValueFromRow(finalCursor, ordIdx);
                if (heuristic != null) {
                    DebugLog.append(context, "cardsInfo: noteId=" + noteId + " ord=" + ord + " -> flags=" + heuristic + " (heuristic)");
                    return heuristic;
                }

                return null;
            }
            return null;
        }
    }

    private static String safeColName(String[] colNames, int idx) {
        if (colNames == null || idx < 0 || idx >= colNames.length) {
            return "?";
        }
        String v = colNames[idx];
        return v == null ? "?" : v;
    }

    private static String summarizeColumns(String[] colNames) {
        if (colNames == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < colNames.length; i++) {
            if (i > 0) sb.append(',');
            String name = colNames[i];
            if (name == null) name = "?";
            // Keep log reasonably small
            if (sb.length() + name.length() > 240) {
                sb.append("…");
                break;
            }
            sb.append(name);
        }
        sb.append(']');
        return sb.toString();
    }

    private static Integer guessFlagValueFromRow(Cursor cursor, int ordIdx) {
        int colCount = cursor.getColumnCount();
        Integer found = null;
        for (int i = 0; i < colCount; i++) {
            if (i == ordIdx) continue;
            try {
                if (cursor.getType(i) != Cursor.FIELD_TYPE_INTEGER) {
                    continue;
                }
                long v = cursor.getLong(i);
                if (v < 0 || v > 7) {
                    continue;
                }
                int candidate = (int) v;
                if (found == null) {
                    found = candidate;
                } else if (!found.equals(candidate)) {
                    // Ambiguous: multiple small-int columns with different values
                    return null;
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return found;
    }

    private Integer tryGetCardLevelFlags(long noteId, long cardId) {
        // Best-effort: query /notes/<nid>/cards and try to find the row corresponding to cardId,
        // then read a flag/flags column if present.
        Uri noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, Long.toString(noteId));
        Uri cardsUri = Uri.withAppendedPath(noteUri, "cards");

        final Cursor cursor;
        try {
            cursor = resolver.query(cardsUri, null, null, null, null);
        } catch (Exception e) {
            DebugLog.append(context, "cardsInfo: cardId=" + cardId + " -> cards query exception=" + e);
            return null;
        }

        if (cursor == null) {
            return null;
        }

        try (cursor) {
            int colCount = cursor.getColumnCount();
            String[] colNames = cursor.getColumnNames();
            int flagsIdx = -1;
            for (int i = 0; i < colCount; i++) {
                String name = colNames[i];
                if (name == null) continue;
                String lc = name.toLowerCase(Locale.ROOT);
                if (lc.equals("flag") || lc.equals("flags") || lc.contains("flag")) {
                    flagsIdx = i;
                    break;
                }
            }
            if (flagsIdx < 0) {
                return null;
            }

            while (cursor.moveToNext()) {
                if (rowLooksLikeCard(cursor, cardId)) {
                    try {
                        if (cursor.getType(flagsIdx) == Cursor.FIELD_TYPE_INTEGER) {
                            return (int) cursor.getLong(flagsIdx);
                        }
                    } catch (Exception ignored) {
                        return null;
                    }
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean rowLooksLikeCard(Cursor cursor, long cardId) {
        int colCount = cursor.getColumnCount();
        for (int i = 0; i < colCount; i++) {
            try {
                if (cursor.getType(i) != Cursor.FIELD_TYPE_INTEGER) {
                    continue;
                }
                if (cursor.getLong(i) == cardId) {
                    return true;
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return false;
    }

    private List<Long> getCardIdsForNote(long noteId) {
        String noteIdStr = Long.toString(noteId);
        Uri noteUriV2 = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI_V2, noteIdStr);
        Uri cardsUriV2 = Uri.withAppendedPath(noteUriV2, "cards");
        Uri noteUriLegacy = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteIdStr);
        Uri cardsUriLegacy = Uri.withAppendedPath(noteUriLegacy, "cards");

        // Some AnkiDroid builds/providers reject projections like "_id" here.
        // Query without a projection and discover the id column from returned names.
        Cursor cursor = null;
        Boolean v2CardsSupported = NOTES_V2_CARDS_SUPPORTED;
        if (v2CardsSupported == null || v2CardsSupported) {
            try {
                cursor = resolver.query(cardsUriV2, null, null, null, null);
                NOTES_V2_CARDS_SUPPORTED = true;
            } catch (IllegalArgumentException e) {
                NOTES_V2_CARDS_SUPPORTED = false;
                DebugLog.append(context, "findCards/cards: notes_v2/cards not supported (disabling v2): " + e.getMessage());
            } catch (Exception e) {
                DebugLog.append(context, "findCards/cards: noteId=" + noteId + " -> query(v2) exception=" + e);
            }
        }

        if (cursor == null) {
            try {
                cursor = resolver.query(cardsUriLegacy, null, null, null, null);
            } catch (Exception e) {
                DebugLog.append(context, "findCards/cards: noteId=" + noteId + " -> query(legacy) exception=" + e);
                return Collections.emptyList();
            }
        }

        if (cursor == null) {
            return Collections.emptyList();
        }

        final Cursor finalCursor = cursor;
        try (finalCursor) {
            List<Long> ids = new ArrayList<>();
            int ordIdx = finalCursor.getColumnIndex(FlashCardsContract.Card.CARD_ORD);
            if (ordIdx < 0) {
                ids.add(noteId);
                return ids;
            }

            while (finalCursor.moveToNext()) {
                int ord;
                try {
                    ord = (int) finalCursor.getLong(ordIdx);
                } catch (Exception ignored) {
                    continue;
                }
                ids.add(CardIdCodec.pack(noteId, ord));
            }
            return ids;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }
}
