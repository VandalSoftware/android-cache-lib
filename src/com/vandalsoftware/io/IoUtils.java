/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedList;

public final class IoUtils {
    public static final String UTF_8 = "UTF-8";

    private IoUtils() {
    }

    /**
     * Closes 'closeable', ignoring any checked exceptions. Does nothing if 'closeable' is null.
     */
    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Closes 'socket', ignoring any exceptions. Does nothing if 'socket' is null.
     */
    public static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Returns the contents of 'path' as a byte array.
     */
    public static byte[] readFileAsByteArray(String path) throws IOException {
        return readFileAsBytes(path).toByteArray();
    }

    /**
     * Returns the contents of 'path' as a string. The contents are assumed to be UTF-8.
     */
    public static String readFileAsString(String path) throws IOException {
        return readFileAsBytes(path).toString(UTF_8);
    }

    private static UnsafeByteSequence readFileAsBytes(String path) throws IOException {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(path, "r");
            UnsafeByteSequence bytes = new UnsafeByteSequence((int) f.length());
            byte[] buffer = new byte[8192];
            while (true) {
                int byteCount = f.read(buffer);
                if (byteCount == -1) {
                    return bytes;
                }
                bytes.write(buffer, 0, byteCount);
            }
        } finally {
            IoUtils.closeQuietly(f);
        }
    }

    /**
     * Recursively delete everything in {@code dir}.
     */
    public static void deleteContents(File dir) throws IOException {
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
        final LinkedList<File> dirs = new LinkedList<File>();
        dirs.add(dir);
        final ArrayList<File> emptyDirs = new ArrayList<File>();
        while (!dirs.isEmpty()) {
            final File d = dirs.remove();
            final File[] fs = d.listFiles();
            for (File f : fs) {
                if (f.isDirectory()) {
                    dirs.add(f);
                } else {
                    deleteFileOrThrow(f);
                }
            }
            emptyDirs.add(d);
        }
        for (int i = emptyDirs.size() - 1; i >= 0; i--) {
            final File d = emptyDirs.get(i);
            deleteFileOrThrow(d);
        }
    }

    public static void deleteFileOrThrow(File f) throws IOException {
        if (f.exists() && !f.delete()) {
            throw new IOException("failed to delete file: " + f);
        }
    }

    public static void renameFileOrThrow(File src, File dst) throws IOException {
        if (!src.renameTo(dst)) {
            throw new IOException("file not renamed " + src + " " + dst);
        }
    }
}
