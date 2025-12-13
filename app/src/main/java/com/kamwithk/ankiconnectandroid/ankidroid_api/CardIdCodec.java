package com.kamwithk.ankiconnectandroid.ankidroid_api;

/**
 * Encodes a (noteId, cardOrd) pair into a single long.
 *
 * Rationale: Some AnkiDroid providers expose /notes/<nid>/cards without a stable card row id.
 * They do expose the note id and card ordinal, which is enough for clients like Yomitan to
 * round-trip a per-card identifier back to this service.
 */
public final class CardIdCodec {
    private CardIdCodec() {
    }

    // Store card ordinal in low 8 bits (0-255), noteId in the remaining high bits.
    // Note IDs are millisecond timestamps and comfortably fit within the remaining bits.
    public static long pack(long noteId, int cardOrd) {
        long ord = (long) (cardOrd & 0xFF);
        return (noteId << 8) | ord;
    }

    public static boolean looksPacked(long value) {
        // Heuristic: packed IDs will have non-trivial high bits.
        return (value >>> 8) > 0;
    }

    public static long unpackNoteId(long packed) {
        return (packed >>> 8);
    }

    public static int unpackCardOrd(long packed) {
        return (int) (packed & 0xFF);
    }
}
