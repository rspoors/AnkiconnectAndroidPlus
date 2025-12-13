package com.kamwithk.ankiconnectandroid.ankidroid_api;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;

import com.ichi2.anki.FlashCardsContract;
import com.ichi2.anki.api.AddContentApi;
import com.kamwithk.ankiconnectandroid.debug.DebugLog;

import java.util.*;

public class NoteAPI {
    private Context context;
    private final ContentResolver resolver;
    private final AddContentApi api;

    private static final String TAG = "AnkiConnectAndroid";

    // Some AnkiDroid builds do not support the notes_v2 URI at all.
    // Cache support so we don't spam logs with repeated IllegalArgumentException.
    private static volatile Boolean NOTES_V2_CARDS_SUPPORTED = null;

    private static final String[] MODEL_PROJECTION = {FlashCardsContract.Note.MID};
    private static final String[] NOTE_ID_PROJECTION = {FlashCardsContract.Note._ID};
    private static final String[] NOTES_INFO_PROJECTION = {FlashCardsContract.Note._ID, FlashCardsContract.Note.MID, FlashCardsContract.Note.TAGS, FlashCardsContract.Note.FLDS};

    public NoteAPI(Context context) {
        this.context = context;
        this.resolver = context.getContentResolver();
        api = new AddContentApi(context);
    }

    static String escapeQueryStr(String s) {
      // first replace: \ -> \\
      // second replace: " -> \"
      return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Add flashcards to AnkiDroid through instant add API
     *
     * @param data Map of (field name, field value) pairs
     */
    public Long addNote(final Map<String, String> data, Long deck_id, Long model_id, Set<String> tags) throws Exception {
        String[] allFieldNames = api.getFieldList(model_id);
        if (allFieldNames == null) {
            throw new Exception("Couldn't get fields");
        }

        // Get list in correct order
        String[] fields = new String[allFieldNames.length];

        for (int i = 0; i < allFieldNames.length; i++) {
            fields[i] = data.getOrDefault(allFieldNames[i], "");
        }

        return api.addNote(model_id, deck_id, fields, tags);
    }

    public String[] getNoteFields(long note_id) throws Exception {
        return api.getNote(note_id).getFields();
    }

    public boolean updateNoteFields(long note_id, final Map<String, String> data) throws Exception {
        long modelId = getNoteModelId(note_id);
        String[] allFieldNames = api.getFieldList(modelId);
        if (allFieldNames == null) {
            throw new Exception("Couldn't get fields");
        }

        // Get list in correct order
        String[] fields = new String[data.size()];
        for (int i = 0; i < data.size(); i++) {
            fields[i] = data.get(allFieldNames[i]);
        }

        return api.updateNoteFields(note_id, fields);
    }

    public Long getNoteModelId(long note_id) {
        // Manually queries the note with a specific projection to get the model ID
        // Code copied/pasted from getNote() in AddContentAPI:
        // https://github.com/ankidroid/Anki-Android/blob/1711e56c2b5515ab89c3424b60e60867bb65d492/api/src/main/java/com/ichi2/anki/api/AddContentApi.kt#L244

        Uri noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, Long.toString(note_id));
        Cursor cursor = this.resolver.query(noteUri, MODEL_PROJECTION, null, null, null);

        if (cursor == null) {
            return null;
        }
        try {
            if (!cursor.moveToNext()) {
                return null;
            }
            int index = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.MID);;
            return cursor.getLong(index); // mid
        } finally {
            cursor.close();
        }
    }

    public ArrayList<Long> findNotes(String query) {
        ArrayList<Long> noteIds = new ArrayList<>();

        final Cursor cursor = this.resolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                NOTE_ID_PROJECTION,
                query,
                null,
                null
        );

        if (cursor != null) {
            if (!cursor.moveToFirst()) {
                return noteIds;
            }
            for (int i = 0; i < cursor.getCount(); i++) {
                long id = cursor.getLong(0);
                noteIds.add(id);
                cursor.moveToNext();
            }
            cursor.close();
        }

        return noteIds;
    }

    static class NoteInfoField {
        private final String value;
        private final int order;

        public NoteInfoField(String value, int order) {
            this.value = value;
            this.order = order;
        }

        public String getValue() {
            return value;
        }

        public int getOrder() {
            return order;
        }
    }
    static class NoteInfo {
        private final long noteId;
        private final String modelName;
        private final List<String> tags;
        private final Map<String, NoteInfoField> fields;
        private final List<Long> cards;

        public NoteInfo(long noteId, String modelName, List<String> tags, Map<String,
                NoteInfoField> fields, List<Long> cards) {
            this.noteId = noteId;
            this.modelName = modelName;
            this.tags = tags;
            this.fields = fields;
            this.cards = cards;
        }

        public long getNoteId() {
            return noteId;
        }

        public String getModelName() {
            return modelName;
        }

        public List<String> getTags() {
            return tags;
        }

        public Map<String, NoteInfoField> getFields() {
            return fields;
        }

        public List<Long> getCards() {
            return cards;
        }
    }

    static class Model {
        private final long modelId;
        private final String modelName;
        private final String[] fieldNames;

        public Model(long modelId, String modelName, String[] fieldNames) {
            this.modelId = modelId;
            this.modelName = modelName;
            this.fieldNames = fieldNames;
        }

        public long getModelId() {
            return modelId;
        }

        public String getModelName() {
            return modelName;
        }

        public String[] getFieldNames() {
            return fieldNames;
        }
    }

    public List<NoteInfo> notesInfo(ArrayList<Long> noteIds) throws Exception {
        List<NoteInfo> notesInfoList = new ArrayList<>();
        String nidQuery = "nid:" + TextUtils.join(",", noteIds);
        Map<Long, Model> cache = new HashMap<>();

        Cursor cursor = this.resolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                NOTES_INFO_PROJECTION,
                nidQuery,
                null,
                null,
                null
                );

        if (cursor == null) {
            return null;
        }

        try (cursor) {
            while (cursor.moveToNext()) {

                int idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID);
                int midIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.MID);
                int tagsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS);
                int fldsIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.FLDS);

                long id = cursor.getLong(idIdx);
                long mid = cursor.getLong(midIdx);
                List<String> tags = Arrays.asList(Utility.splitTags(cursor.getString(tagsIdx)));
                String[] fieldValues = Utility.splitFields(cursor.getString(fldsIdx));
                Model model = null;

                if (cache.containsKey(mid)) {
                    model = cache.get(mid);
                }
                else {
                    String[] fieldNames = api.getFieldList(mid);
                    String modelName = api.getModelName(mid);

                    model = new Model(mid, modelName, fieldNames);
                    cache.put(mid, model);
                }

                Map<String, NoteInfoField> fields = new HashMap<>();
                String[] fieldNames = model.getFieldNames();

                for (int i = 0; i < fieldNames.length; i++) {
                    String fieldName = fieldNames[i];
                    String fieldValue = fieldValues[i];
                    NoteInfoField noteInfoField = new NoteInfoField(fieldValue, i);
                    fields.put(fieldName, noteInfoField);
                }

                List<Long> cards = getCardIdsForNote(id);
                Log.d(TAG, "notesInfo: noteId=" + id + " cards=" + cards);
                DebugLog.append(context, "notesInfo: noteId=" + id + " cards=" + cards);

                NoteInfo noteInfo = new NoteInfo(id, model.getModelName(), tags, fields, cards);
                notesInfoList.add(noteInfo);
            }
        }
        return notesInfoList;
    }

    private List<Long> getCardIdsForNote(long noteId) {
        String noteIdStr = Long.toString(noteId);
        Uri noteUriV2 = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI_V2, noteIdStr);
        Uri cardsUriV2 = Uri.withAppendedPath(noteUriV2, "cards");
        Uri noteUriLegacy = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteIdStr);
        Uri cardsUriLegacy = Uri.withAppendedPath(noteUriLegacy, "cards");

        // Some AnkiDroid builds/providers reject projections like "_id" here.
        // Query without a projection.
        Cursor cursor = null;
        Boolean v2Supported = NOTES_V2_CARDS_SUPPORTED;
        if (v2Supported == null || v2Supported) {
            try {
                cursor = this.resolver.query(cardsUriV2, null, null, null, null);
                NOTES_V2_CARDS_SUPPORTED = true;
            } catch (IllegalArgumentException e) {
                NOTES_V2_CARDS_SUPPORTED = false;
                DebugLog.append(context, "notesInfo: notes_v2/cards not supported (disabling v2): " + e.getMessage());
            } catch (Exception e) {
                DebugLog.append(context, "notesInfo: noteId=" + noteId + " -> cards(v2) query exception=" + e);
            }
        }

        if (cursor == null) {
            try {
                cursor = this.resolver.query(cardsUriLegacy, null, null, null, null);
            } catch (Exception e) {
                Log.d(TAG, "notesInfo: noteId=" + noteId + " -> cards query exception=" + e);
                DebugLog.append(context, "notesInfo: noteId=" + noteId + " -> cards(legacy) query exception=" + e);
                return Collections.emptyList();
            }
        }

        if (cursor == null) {
            return Collections.emptyList();
        }

        final Cursor finalCursor = cursor;
        try (finalCursor) {
            List<Long> ids = new ArrayList<>();

            // Prefer a stable per-card identifier derived from (noteId, card ordinal).
            // Many providers expose NOTE_ID + CARD_ORD but do NOT expose a card row id.
            int ordIdx = finalCursor.getColumnIndex(FlashCardsContract.Card.CARD_ORD);
            if (ordIdx < 0) {
                // Fallback: keep behavior compatible (at least enables tags/show-card), but flags
                // may be unavailable without a proper per-card key.
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