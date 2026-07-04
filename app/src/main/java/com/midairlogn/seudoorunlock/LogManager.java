package com.midairlogn.seudoorunlock;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class LogManager {

    private static final String TAG = "ZL_LogManager";
    private static final long MAX_FILE_SIZE = 500 * 1024;
    private static final int FLUSH_INTERVAL = 20;

    private static volatile LogManager instance;

    private volatile boolean enabled = false;
    private File logDir;
    private File logFile;
    private File logFileOld;
    private final ExecutorService executor;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private final AtomicInteger pendingCount = new AtomicInteger(0);

    private LogManager(Context context, ExecutorService executor) {
        this.executor = executor;
        logDir = context.getExternalFilesDir("logs");
        if (logDir != null) {
            logFile = new File(logDir, "app.log");
            logFileOld = new File(logDir, "app.log.1");
        }
    }

    public static LogManager getInstance() {
        return instance;
    }

    public static void init(Context context, ExecutorService executor) {
        if (instance == null) {
            synchronized (LogManager.class) {
                if (instance == null) {
                    instance = new LogManager(context, executor);
                }
            }
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            executor.execute(this::drainQueue);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public File getLogFile() {
        return logFile;
    }

    public boolean hasLogFile() {
        return logFile != null && logFile.exists() && logFile.length() > 0;
    }

    public void flush() {
        if (!enabled) return;
        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                drainQueue();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static int d(String tag, String msg) {
        int result = Log.d(tag, msg);
        if (instance != null && instance.enabled) {
            instance.enqueue('D', tag, msg, null);
        }
        return result;
    }

    public static int d(String tag, String msg, Throwable tr) {
        int result = Log.d(tag, msg, tr);
        if (instance != null && instance.enabled) {
            instance.enqueue('D', tag, msg, tr);
        }
        return result;
    }

    public static int w(String tag, String msg) {
        int result = Log.w(tag, msg);
        if (instance != null && instance.enabled) {
            instance.enqueue('W', tag, msg, null);
        }
        return result;
    }

    public static int w(String tag, String msg, Throwable tr) {
        int result = Log.w(tag, msg, tr);
        if (instance != null && instance.enabled) {
            instance.enqueue('W', tag, msg, tr);
        }
        return result;
    }

    public static int e(String tag, String msg) {
        int result = Log.e(tag, msg);
        if (instance != null && instance.enabled) {
            instance.enqueue('E', tag, msg, null);
        }
        return result;
    }

    public static int e(String tag, String msg, Throwable tr) {
        int result = Log.e(tag, msg, tr);
        if (instance != null && instance.enabled) {
            instance.enqueue('E', tag, msg, tr);
        }
        return result;
    }

    public static int i(String tag, String msg) {
        int result = Log.i(tag, msg);
        if (instance != null && instance.enabled) {
            instance.enqueue('I', tag, msg, null);
        }
        return result;
    }

    public static int i(String tag, String msg, Throwable tr) {
        int result = Log.i(tag, msg, tr);
        if (instance != null && instance.enabled) {
            instance.enqueue('I', tag, msg, tr);
        }
        return result;
    }

    private void enqueue(char level, String tag, String msg, Throwable tr) {
        String timestamp = LocalDateTime.now().format(DATE_FORMAT);
        StringBuilder sb = new StringBuilder(timestamp.length() + 1 + tag.length() + 2 + msg.length() + 20);
        sb.append(timestamp).append(' ').append(level).append('/').append(tag).append(": ").append(msg);
        if (tr != null) {
            sb.append('\n').append(Log.getStackTraceString(tr));
        }
        queue.offer(sb.toString());

        if (pendingCount.incrementAndGet() >= FLUSH_INTERVAL) {
            pendingCount.set(0);
            executor.execute(this::drainQueue);
        }
    }

    private void drainQueue() {
        if (logDir == null || logFile == null) return;
        if (!logDir.exists() && !logDir.mkdirs()) return;

        String line;
        while ((line = queue.poll()) != null) {
            appendToFile(line);
        }
    }

    private void appendToFile(String line) {
        rotateIfNeeded();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            Log.w(TAG, "Failed to write log to file", e);
        }
    }

    private void rotateIfNeeded() {
        if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
            if (logFileOld.exists()) {
                logFileOld.delete();
            }
            logFile.renameTo(logFileOld);
        }
    }
}
