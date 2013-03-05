/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.vandalsoftware.io;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A cache that uses a bounded amount of space on a filesystem. Each cache entry has a string key
 * and a fixed number of values. Values are byte sequences, accessible as streams or files. Each
 * value must be between {@code 0} and {@code Integer.MAX_VALUE} bytes in length.
 * <p/>
 * <p>The cache stores its data in a directory on the filesystem. This directory must be exclusive
 * to the cache; the cache may delete or overwrite files from its directory. It is an error for
 * multiple processes to use the same cache directory at the same time.
 * <p/>
 * <p>This cache limits the number of bytes that it will store on the filesystem. When the number of
 * stored bytes exceeds the limit, the cache will remove entries in the background until the limit
 * is satisfied. The limit is not strict: the cache may temporarily exceed it while waiting for
 * files to be deleted. The limit does not include filesystem overhead or the cache journal so
 * space-sensitive applications should set a conservative limit.
 * <p/>
 * <p>Clients call {@link #edit} to create or update the values of an entry. An entry may have only
 * one editor at one time; if a value is not available to be edited then {@link #edit} will return
 * null. <ul> <li>When an entry is being <strong>created</strong> it is necessary to supply a full
 * set of values; the empty value should be used as a placeholder if necessary. <li>When an entry is
 * being <strong>created</strong>, it is not necessary to supply data for every value; values
 * default to their previous value. </ul> Every {@link #edit} call must be matched by a call to
 * {@link Editor#commit} or {@link Editor#abort}. Committing is atomic: a read observes the full set
 * of values as they were before or after the commit, but never a mix of values.
 * <p/>
 * <p>Clients call {@link #get} to read a snapshot of an entry. The read will observe the value at
 * the time that {@link #get} was called. Updates and removals after the call do not impact ongoing
 * reads.
 * <p/>
 * <p>This class is tolerant of some I/O errors. If files are missing from the filesystem, the
 * corresponding entries will be dropped from the cache. If an error occurs while writing a cache
 * value, the edit will fail silently. Callers should handle other problems by catching {@code
 * IOException} and responding appropriately.
 */
public final class DiskLruCache implements Closeable {
    private static final String JOURNAL_FILE = "journal";
    private static final String JOURNAL_FILE_TMP = "journal.tmp";
    private static final long MAGIC = 0x814A4C450D0A1A0Al;
    private static final int VERSION = 2;
    private static final int CLEAN = 1;
    private static final int DIRTY = 2;
    private static final int REMOVE = 3;
    private static final int READ = 4;
    private static final int REDUNDANT_OP_COMPACT_THRESHOLD = 2000;

    /*
     * This cache uses a journal file named "journal". A typical journal file
     * looks like this:
     *    <magic><version><appVersion><valueCount>
     *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
     *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
     *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
     *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
     *     DIRTY 1ab96a171faeeee38496d8b330771a7a
     *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
     *     READ 335c4c6028171cfddfbaae1a9c313c52
     *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
     *
     * The first five lines of the journal form its header. They are the
     * constant string "libcore.io.DiskLruCache", the disk cache's version,
     * the application's version, the value count, and a blank line.
     *
     * Each of the subsequent lines in the file is a record of the state of a
     * cache entry. Each line contains space-separated values: a state, a key,
     * and optional state-specific values.
     *   o DIRTY lines track that an entry is actively being created or updated.
     *     Every successful DIRTY action should be followed by a CLEAN or REMOVE
     *     action. DIRTY lines without a matching CLEAN or REMOVE indicate that
     *     temporary files may need to be deleted.
     *   o CLEAN lines track a cache entry that has been successfully published
     *     and may be read. A publish line is followed by the lengths of each of
     *     its values.
     *   o READ lines track accesses for LRU.
     *   o REMOVE lines track entries that have been deleted.
     *
     * The journal file is appended to as cache operations occur. The journal may
     * occasionally be compacted by dropping redundant lines. A temporary file named
     * "journal.tmp" will be used during compaction; that file should be deleted if
     * it exists when the cache is opened.
     */
    private final File directory;
    private final File journalFile;
    private final File journalFileTmp;
    private final int appVersion;
    private final long maxSize;
    private final int valueCount;
    private final LinkedHashMap<String, Entry> lruEntries
            = new LinkedHashMap<String, Entry>(0, 0.75f, true);
    /**
     * This cache uses a single background thread to evict entries.
     */
    private final ExecutorService executorService = new ThreadPoolExecutor(0, 1,
            60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    private final Callable<Void> cleanupCallable = new Callable<Void>() {
        @Override
        public Void call() throws Exception {
            synchronized (DiskLruCache.this) {
                if (journalStream == null) {
                    return null; // closed
                }
                trimToSize();
                if (journalRebuildRequired()) {
                    rebuildJournal();
                }
            }
            return null;
        }
    };
    private long size = 0;
    private DataOutputStream journalStream;
    private int redundantOpCount;

    private DiskLruCache(File directory, int appVersion, int valueCount, long maxSize) {
        this.directory = directory;
        this.appVersion = appVersion;
        this.journalFile = new File(directory, JOURNAL_FILE);
        this.journalFileTmp = new File(directory, JOURNAL_FILE_TMP);
        this.valueCount = valueCount;
        this.maxSize = maxSize;
    }

    /**
     * Opens the cache in {@code directory}, creating a cache if none exists there.
     *
     * @param directory  a writable directory
     * @param appVersion application-specific version
     * @param valueCount the number of values per cache entry. Must be positive.
     * @param maxSize    the maximum number of bytes this cache should use to store
     * @throws IOException if reading or writing the cache directory fails
     */
    public static DiskLruCache open(File directory, int appVersion, int valueCount, long maxSize)
            throws IOException {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        if (valueCount <= 0) {
            throw new IllegalArgumentException("valueCount <= 0");
        }

        // prefer to pick up where we left off
        DiskLruCache cache = new DiskLruCache(directory, appVersion, valueCount, maxSize);
        if (cache.journalFile.exists()) {
            try {
                final int journalEntries = cache.readJournal();
                cache.processJournal();
                cache.journalStream = new DataOutputStream(
                        new BufferedOutputStream(new FileOutputStream(cache.journalFile, true)));
                cache.redundantOpCount = journalEntries - cache.lruEntries.size();
                return cache;
            } catch (IOException journalIsCorrupt) {
                Log.w("DiskLruCache", directory + " is corrupt: "
                        + journalIsCorrupt.getMessage() + ", removing");
                cache.delete();
            }
        }

        // create a new empty cache
        if (directory.mkdirs() || directory.exists()) {
            cache = new DiskLruCache(directory, appVersion, valueCount, maxSize);
            cache.rebuildJournal();
            return cache;
        } else {
            throw new FileNotFoundException("directory not found " + directory);
        }
    }

    private static boolean deleteIfExists(File file) throws IOException {
        return file.exists() && file.delete();
    }

    /**
     * Reads the journal file.
     *
     * @return number of journal entries
     * @throws IOException
     */
    private int readJournal() throws IOException {
        final DataInputStream in =
                new DataInputStream(new BufferedInputStream(new FileInputStream(journalFile)));
        try {
            long fileMagic = in.readLong();
            int fileVersion = in.readUnsignedByte();
            int fileAppVersion = in.readInt();
            int fileValueCount = in.readInt();
            byte blank = in.readByte();
            if (MAGIC != fileMagic
                    || VERSION != fileVersion
                    || appVersion != fileAppVersion
                    || valueCount != fileValueCount
                    || blank != '\n') {
                throw new IOException("unexpected journal header: ["
                        + fileMagic + ", " + fileVersion + ", " + fileValueCount + ", " + blank + "]");
            }

            int entries = 0;
            while (true) {
                try {
                    readJournalLine(in);
                    entries += 1;
                } catch (EOFException endOfJournal) {
                    break;
                }
            }
            return entries;
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private void readJournalLine(DataInput in) throws IOException {
        int op = in.readUnsignedByte();
        String key = in.readUTF();
        if (op == REMOVE && in.readByte() == '\n') {
            lruEntries.remove(key);
            return;
        }

        Entry entry = lruEntries.get(key);
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        }

        if (op == CLEAN) {
            final long[] temp = new long[valueCount];
            for (int i = 0; i < valueCount; i++) {
                temp[i] = in.readLong();
            }
            if (in.readByte() != '\n') {
                throw new IOException("unexpected journal entry: " + op + " " + key);
            }
            entry.readable = true;
            entry.currentEditor = null;
            System.arraycopy(temp, 0, entry.lengths, 0, valueCount);
        } else if (op == DIRTY && in.readByte() == '\n') {
            entry.currentEditor = new Editor(entry);
        } else if (op == READ && in.readByte() == '\n') {
            // this work was already done by calling lruEntries.get()
        } else {
            throw new IOException("unexpected journal entry: " + op + " " + key);
        }
    }

    /**
     * Computes the initial size and collects garbage as a part of opening the cache. Dirty entries
     * are assumed to be inconsistent and will be deleted.
     */
    private void processJournal() throws IOException {
        deleteIfExists(journalFileTmp);
        for (Iterator<Entry> i = lruEntries.values().iterator(); i.hasNext(); ) {
            Entry entry = i.next();
            if (entry.currentEditor == null) {
                for (int t = 0; t < valueCount; t++) {
                    size += entry.lengths[t];
                }
            } else {
                entry.currentEditor = null;
                for (int t = 0; t < valueCount; t++) {
                    deleteIfExists(entry.getCleanFile(t));
                    deleteIfExists(entry.getDirtyFile(t));
                }
                i.remove();
            }
        }
    }

    /**
     * Creates a new journal that omits redundant information. This replaces the current journal if
     * it exists.
     */
    private synchronized void rebuildJournal() throws IOException {
        if (journalStream != null) {
            journalStream.close();
        }

        final DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(journalFileTmp)));
        try {
            out.writeLong(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(appVersion);
            out.writeInt(valueCount);
            out.writeByte('\n');

            for (Entry entry : lruEntries.values()) {
                if (entry.currentEditor != null) {
                    writeEntryKey(out, DIRTY, entry.key);
                } else {
                    writeCleanEntry(out, entry);
                }
            }
        } finally {
            out.close();
        }
        IoUtils.renameFileOrThrow(journalFileTmp, journalFile);
        journalStream = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(journalFile, true)));
        redundantOpCount = 0;
    }

    private void writeEntryKey(DataOutput out, int s, String key) throws IOException {
        out.writeByte(s);
        out.writeUTF(key);
        out.writeByte('\n');
    }

    private void writeCleanEntry(DataOutput out, Entry entry) throws IOException {
        out.writeByte(CLEAN);
        out.writeUTF(entry.key);
        for (long len : entry.lengths) {
            out.writeLong(len);
        }
        out.writeByte('\n');
    }

    /**
     * Returns a snapshot of the entry named {@code key}, or null if it doesn't exist is not
     * currently readable. If a value is returned, it is moved to the head of the LRU queue.
     */
    public synchronized Snapshot get(String key) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null) {
            return null;
        }

        if (!entry.readable) {
            return null;
        }

        /*
         * Open all streams eagerly to guarantee that we see a single published
         * snapshot. If we opened streams lazily then the streams could come
         * from different edits.
         */
        InputStream[] ins = new InputStream[valueCount];
        try {
            for (int i = 0; i < valueCount; i++) {
                ins[i] = new FileInputStream(entry.getCleanFile(i));
            }
        } catch (FileNotFoundException e) {
            // a file must have been deleted manually!
            return null;
        }

        redundantOpCount += 1;
        writeEntryKey(journalStream, READ, key);
        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }

        return new Snapshot(ins);
    }

    /**
     * Returns an editor for the entry named {@code key}, or null if it cannot currently be edited.
     */
    public synchronized Editor edit(String key) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        } else if (entry.currentEditor != null) {
            return null;
        }

        Editor editor = new Editor(entry);
        entry.currentEditor = editor;

        // flush the journal before creating files to prevent file leaks
        writeEntryKey(journalStream, DIRTY, key);
        journalStream.flush();
        return editor;
    }

    /**
     * Returns the directory where this cache stores its data.
     */
    public File getDirectory() {
        return directory;
    }

    /**
     * Returns the maximum number of bytes that this cache should use to store its data.
     */
    public long maxSize() {
        return maxSize;
    }

    /**
     * Returns the number of bytes currently being used to store the values in this cache. This may
     * be greater than the max size if a background deletion is pending.
     */
    public synchronized long size() {
        return size;
    }

    private synchronized void completeEdit(Editor editor, boolean success) throws IOException {
        Entry entry = editor.entry;
        if (entry.currentEditor != editor) {
            throw new IllegalStateException();
        }

        // if this edit is creating the entry for the first time, every index must have a value
        if (success && !entry.readable) {
            for (int i = 0; i < valueCount; i++) {
                if (!entry.getDirtyFile(i).exists()) {
                    editor.abort();
                    throw new IllegalStateException("edit didn't create file " + i);
                }
            }
        }

        for (int i = 0; i < valueCount; i++) {
            File dirty = entry.getDirtyFile(i);
            if (success) {
                if (dirty.exists()) {
                    File clean = entry.getCleanFile(i);
                    IoUtils.renameFileOrThrow(dirty, clean);
                    long oldLength = entry.lengths[i];
                    long newLength = clean.length();
                    entry.lengths[i] = newLength;
                    size = size - oldLength + newLength;
                }
            } else {
                deleteIfExists(dirty);
            }
        }

        redundantOpCount += 1;
        entry.currentEditor = null;
        if (entry.readable | success) {
            entry.readable = true;
            writeCleanEntry(journalStream, entry);
        } else {
            lruEntries.remove(entry.key);
            writeEntryKey(journalStream, REMOVE, entry.key);
        }

        if (size > maxSize || journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }
    }

    /**
     * We only rebuild the journal when it will halve the size of the journal and eliminate at least
     * 2000 ops.
     */
    private boolean journalRebuildRequired() {
        return redundantOpCount >= REDUNDANT_OP_COMPACT_THRESHOLD
                && redundantOpCount >= lruEntries.size();
    }

    /**
     * Drops the entry for {@code key} if it exists and can be removed. Entries actively being
     * edited cannot be removed.
     *
     * @return true if an entry was removed.
     */
    public synchronized boolean remove(String key) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null || entry.currentEditor != null) {
            return false;
        }

        for (int i = 0; i < valueCount; i++) {
            File file = entry.getCleanFile(i);
            IoUtils.deleteFileOrThrow(file);
            size -= entry.lengths[i];
            entry.lengths[i] = 0;
        }

        redundantOpCount += 1;
        writeEntryKey(journalStream, REMOVE, key);
        lruEntries.remove(key);

        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }

        return true;
    }

    /**
     * Returns true if this cache has been closed.
     */
    public boolean isClosed() {
        return journalStream == null;
    }

    private void checkNotClosed() {
        if (journalStream == null) {
            throw new IllegalStateException("cache is closed");
        }
    }

    /**
     * Force buffered operations to the filesystem.
     */
    public synchronized void flush() throws IOException {
        checkNotClosed();
        trimToSize();
        journalStream.flush();
    }

    /**
     * Closes this cache. Stored values will remain on the filesystem.
     */
    public synchronized void close() throws IOException {
        if (journalStream == null) {
            return; // already closed
        }
        for (Entry entry : new ArrayList<Entry>(lruEntries.values())) {
            if (entry.currentEditor != null) {
                entry.currentEditor.abort();
            }
        }
        trimToSize();
        journalStream.close();
        journalStream = null;
    }

    private void trimToSize() throws IOException {
        while (size > maxSize) {
            Map.Entry<String, Entry> toEvict = lruEntries.entrySet().iterator().next();
            remove(toEvict.getKey());
        }
    }

    /**
     * Closes the cache and deletes all of its stored values. This will delete all files in the
     * cache directory including files that weren't created by the cache.
     */
    public void delete() throws IOException {
        close();
        IoUtils.deleteContents(directory);
    }

    private void validateKey(String key) {
        if (key.contains(" ") || key.contains("\n") || key.contains("\r")) {
            throw new IllegalArgumentException(
                    "keys must not contain spaces or newlines: \"" + key + "\"");
        }
    }

    /**
     * A snapshot of the values for an entry.
     */
    public static final class Snapshot implements Closeable {
        private final InputStream[] ins;

        private Snapshot(InputStream[] ins) {
            this.ins = ins;
        }

        /**
         * Returns the unbuffered stream with the value for {@code index}.
         */
        public InputStream getInputStream(int index) {
            return ins[index];
        }

        @Override
        public void close() {
            for (InputStream in : ins) {
                IoUtils.closeQuietly(in);
            }
        }
    }

    /**
     * Edits the values for an entry.
     */
    public final class Editor {
        private final Entry entry;
        private boolean hasErrors;

        private Editor(Entry entry) {
            this.entry = entry;
        }

        /**
         * Returns a new unbuffered output stream to write the value at {@code index}. If the
         * underlying output stream encounters errors when writing to the filesystem, this edit will
         * be aborted when {@link #commit} is called.
         */
        public OutputStream newOutputStream(int index) throws IOException {
            synchronized (DiskLruCache.this) {
                if (entry.currentEditor != this) {
                    throw new IllegalStateException();
                }
                return new ErrorCatchingOutputStream(
                        new FileOutputStream(entry.getDirtyFile(index)));
            }
        }

        /**
         * Commits this edit so it is visible to readers.  This releases the edit lock so another
         * edit may be started on the same key.
         */
        public void commit() throws IOException {
            if (hasErrors) {
                completeEdit(this, false);
                remove(entry.key); // the previous entry is stale
            } else {
                completeEdit(this, true);
            }
        }

        /**
         * Aborts this edit. This releases the edit lock so another edit may be started on the same
         * key.
         */
        public void abort() throws IOException {
            completeEdit(this, false);
        }

        private class ErrorCatchingOutputStream extends FilterOutputStream {
            private ErrorCatchingOutputStream(OutputStream out) {
                super(out);
            }

            @Override
            public void write(int oneByte) throws IOException {
                try {
                    out.write(oneByte);
                } catch (IOException e) {
                    hasErrors = true;
                    throw e;
                }
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                try {
                    out.write(buffer, offset, length);
                } catch (IOException e) {
                    hasErrors = true;
                    throw e;
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    out.close();
                } catch (IOException e) {
                    hasErrors = true;
                    throw e;
                }
            }

            @Override
            public void flush() throws IOException {
                try {
                    out.flush();
                } catch (IOException e) {
                    hasErrors = true;
                    throw e;
                }
            }
        }
    }

    private final class Entry {
        private final String key;
        /**
         * Lengths of this entry's files.
         */
        private final long[] lengths;
        /**
         * True if this entry has ever been published
         */
        private boolean readable;
        /**
         * The ongoing edit or null if this entry is not being edited.
         */
        private Editor currentEditor;

        private Entry(String key) {
            this.key = key;
            this.lengths = new long[valueCount];
        }

        public File getCleanFile(int i) {
            return new File(directory, key + "." + i);
        }

        public File getDirtyFile(int i) {
            return new File(directory, key + "." + i + ".tmp");
        }
    }
}
