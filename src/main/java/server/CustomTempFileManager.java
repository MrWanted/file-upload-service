package server;

import fi.iki.elonen.NanoHTTPD.TempFile;
import fi.iki.elonen.NanoHTTPD.TempFileManager;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class CustomTempFileManager implements TempFileManager {
    private final Path tempDir;
    private final Map<Path, FileHandle> activeFiles;
    private final ScheduledExecutorService cleanupExecutor;
    private static final int CLEANUP_DELAY_MS = 1000;

    public CustomTempFileManager() {
        this.tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        this.activeFiles = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TempFile-Cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void clear() {
        cleanupExecutor.execute(this::performCleanup);
    }

    private void performCleanup() {
        List<Path> filesToRemove = new ArrayList<>();

        activeFiles.forEach((path, handle) -> {
            if (handle.canDelete.get()) {
                try {
                    handle.closeAll();
                    if (tryDelete(path)) {
                        filesToRemove.add(path);
                    }
                } catch (Exception e) {
                    System.err.println("Error during cleanup of " + path);
                    e.printStackTrace();
                }
            }
        });

        filesToRemove.forEach(activeFiles::remove);
    }

    private boolean tryDelete(Path path) {
        try {
            // Ensure the file is closed and released
            FileHandle handle = activeFiles.get(path);
            if (handle != null) {
                handle.closeAll();
            }

            // Try to obtain lock and delete
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                // Try to get exclusive lock
                try (FileLock lock = channel.tryLock()) {
                    if (lock != null) {
                        channel.close();
                        return Files.deleteIfExists(path);
                    }
                }
            } catch (Exception e) {
                // Ignore lock errors and try direct deletion
            }

            // Direct deletion attempt
            return Files.deleteIfExists(path);

        } catch (Exception e) {
            System.err.println("Failed to delete: " + path);
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public TempFile createTempFile(String filename_hint) throws Exception {
        String suffix = Optional.ofNullable(filename_hint)
                .filter(f -> f.contains("."))
                .map(f -> f.substring(f.lastIndexOf(".")))
                .orElse(".tmp");

        Path tempFile = Files.createTempFile(tempDir, "NanoHTTPD-", suffix);
        FileHandle handle = new FileHandle(tempFile);
        activeFiles.put(tempFile, handle);

        // Schedule delayed cleanup attempt
        cleanupExecutor.schedule(() -> {
            handle.canDelete.set(true);
            performCleanup();
        }, CLEANUP_DELAY_MS, TimeUnit.MILLISECONDS);

        return new CustomTempFile(handle);
    }

    private static class FileHandle {
        private final Path path;
        private final Set<OutputStream> streams;
        private final AtomicBoolean canDelete;

        FileHandle(Path path) {
            this.path = path;
            this.streams = Collections.synchronizedSet(new HashSet<>());
            this.canDelete = new AtomicBoolean(false);
        }

        void closeAll() {
            synchronized (streams) {
                streams.forEach(stream -> {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        // Ignore close errors
                    }
                });
                streams.clear();
            }
        }

        OutputStream createOutputStream() throws IOException {
            OutputStream baseStream = Files.newOutputStream(path);
            streams.add(baseStream);
            return new FilterOutputStream(baseStream) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        streams.remove(baseStream);
                    }
                }
            };
        }
    }

    private static class CustomTempFile implements TempFile {
        private final FileHandle handle;

        CustomTempFile(FileHandle handle) {
            this.handle = handle;
        }

        @Override
        public void delete() throws Exception {
            handle.closeAll();
            handle.canDelete.set(true);
        }

        @Override
        public String getName() {
            return handle.path.toString();
        }

        @Override
        public OutputStream open() throws Exception {
            return handle.createOutputStream();
        }
    }

    public void shutdown() {
        try {
            cleanupExecutor.shutdown();
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}