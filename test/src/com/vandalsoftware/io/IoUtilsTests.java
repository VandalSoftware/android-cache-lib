package com.vandalsoftware.io;

import android.test.AndroidTestCase;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * @author Jonathan Le
 */
public class IoUtilsTests extends AndroidTestCase {

    public void testDeleteFileOrThrow() {
        File tempFile = new File(getContext().getFilesDir(), "temp");
        File f = tempFile;
        try {
            createTempFile(tempFile);
            IoUtils.deleteFileOrThrow(f);
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    public void testDeleteContents() {
        File sub = new File(getContext().getFilesDir(), "sub");
        File sub3 = new File(sub, "sub/sub/sub");
        File bub = new File(sub, "bub");
        File bab = new File(sub, "bab");
        File sub3File = new File(sub3, "temp");
        File subFile = new File(sub, "temp");
        File babFile = new File(bab, "temp");
        assertTrue(sub3.mkdirs() || sub3.exists());
        assertTrue(bub.mkdirs() || bub.exists());
        assertTrue(bab.mkdirs() || bab.exists());
        try {
            createTempFile(sub3File);
            createTempFile(subFile);
            createTempFile(babFile);
            IoUtils.deleteContents(sub);
        } catch (FileNotFoundException e) {
            fail(e.getMessage());
        } catch (IOException e) {
            fail(e.getMessage());
        }
        assertFalse(sub.exists());
    }

    private void createTempFile(File f) throws IOException {
        FileOutputStream fout = new FileOutputStream(f);
        fout.write(1);
        fout.close();
    }
}
