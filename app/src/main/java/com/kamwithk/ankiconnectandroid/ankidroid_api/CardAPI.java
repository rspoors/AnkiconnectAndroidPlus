package com.kamwithk.ankiconnectandroid.ankidroid_api;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;

import com.ichi2.anki.FlashCardsContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CardAPI {
    private final ContentResolver resolver;

    private static final String[] CARD_INFO_PROJECTION = {
            FlashCardsContract.Note._ID,
            FlashCardsContract.Note.TAGS,
            FlashCardsContract.Note.FLAGS
    };

    public CardAPI(Context context) {
        this.resolver = context.getContentResolver();
    }

    static class CardInfo {
        private final long cardId;
        private final long note;
        private final long flags;
        private final List<String> tags;

        public CardInfo(long cardId, long note, long flags, List<String> tags) {
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

        public long getFlags() {
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

    private CardInfo getSingleCardInfo(long cardId) {
        Cursor cursor = resolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                CARD_INFO_PROJECTION,
                "cid:" + cardId,
                null,
                null
        );

        if (cursor == null) {
            return new CardInfo(cardId, 0, 0, Collections.emptyList());
        }

        try (cursor) {
            if (!cursor.moveToFirst()) {
                return new CardInfo(cardId, 0, 0, Collections.emptyList());
            }

            int idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID);
            int tagsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS);
            int flagsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.FLAGS);

            long noteId = cursor.getLong(idIdx);
            long flags = cursor.getLong(flagsIdx);

            String rawTags = cursor.getString(tagsIdx);
            List<String> tags;
            if (rawTags == null || rawTags.trim().isEmpty()) {
                tags = Collections.emptyList();
            } else {
                String[] split = Utility.splitTags(rawTags);
                tags = split == null ? Collections.emptyList() : Arrays.asList(split);
            }

            return new CardInfo(cardId, noteId, flags, tags);
        }
    }
}
