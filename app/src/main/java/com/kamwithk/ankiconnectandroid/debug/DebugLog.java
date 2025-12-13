package com.kamwithk.ankiconnectandroid.debug;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DebugLog {
    private static final String FILE_NAME = "ankiconnectandroid-debug.log";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private DebugLog() {
    }

    public static File getFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static void append(Context context, String line) {
        if (context == null || line == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            try {
                File file = getFile(appContext);
                String stamped = timestampFormat.format(new Date()) + " " + line + "\n";
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(stamped.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // Never crash the app due to logging.
            }
        });
    }

    public static boolean clear(Context context) {
        try {
            File file = getFile(context.getApplicationContext());
            return !file.exists() || file.delete();
        } catch (Exception ignored) {
            return false;
        }
    }
}
