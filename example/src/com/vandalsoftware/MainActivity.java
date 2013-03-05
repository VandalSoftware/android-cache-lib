/*
 * Copyright (C) 2013 Jonathan Le
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.vandalsoftware;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import com.vandalsoftware.io.DiskLruCache;
import com.vandalsoftware.io.IoUtils;
import com.vandalsoftware.io.Streams;

import java.io.File;
import java.io.IOException;

/**
 * @author Jonathan Le
 */
public class MainActivity extends Activity {
    private static final String TAG = "Example";
    private DiskLruCache mDiskCache;
    private EditText mKeyEdit;
    private EditText mValueEdit;
    private Button mSetValueBtn;
    private Handler mHandler = new Handler();
    private Runnable mFetchRunnable = new FetchRunnable();

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mKeyEdit = (EditText) findViewById(R.id.key);
        mKeyEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (!hasFocus && TextUtils.isEmpty(mValueEdit.getText())) {
                    fetchValue(mKeyEdit.getText().toString());
                }
            }
        });
        mKeyEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                mValueEdit.getText().clear();
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mHandler.removeCallbacks(mFetchRunnable);
                mHandler.postDelayed(mFetchRunnable, 500);
            }
        });
        mValueEdit = (EditText) findViewById(R.id.value);
        mSetValueBtn = (Button) findViewById(R.id.btn_set_value);
        mSetValueBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new CacheSaveTask().execute(new KeyValue(mKeyEdit.getText().toString(),
                        mValueEdit.getText().toString()));
            }
        });
        new CacheLoadTask().execute();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        IoUtils.closeQuietly(mDiskCache);
    }

    private void fetchValue(String s) {
        new CacheReadTask().execute(s);
    }

    private class FetchRunnable implements Runnable {
        @Override
        public void run() {
            fetchValue(mKeyEdit.getText().toString());
        }
    }

    private static class KeyValue {
        public final String key;
        public final String value;
        public boolean hasErrors;

        private KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private class CacheSaveTask extends AsyncTask<KeyValue, Void, KeyValue> {
        @Override
        protected void onPostExecute(KeyValue kv) {
            if (kv.hasErrors) {
                Toast.makeText(MainActivity.this, getString(R.string.save_error_msg_fmt, kv.key),
                        Toast.LENGTH_SHORT).show();
            } else {
                if (TextUtils.isEmpty(kv.value)) {
                    Toast.makeText(MainActivity.this, getString(R.string.removed_msg_fmt, kv.key),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.saved_msg_fmt, kv.key),
                            Toast.LENGTH_SHORT).show();
                }
                mKeyEdit.setText(null);
                mKeyEdit.requestFocus();
                mValueEdit.setText(null);
            }
        }

        @Override
        protected KeyValue doInBackground(KeyValue... kvs) {
            final KeyValue kv = kvs[0];
            try {
                if (TextUtils.isEmpty(kv.value)) {
                    mDiskCache.remove(kv.key);
                } else {
                    DiskLruCache.Editor e = mDiskCache.edit(kv.key);
                    Streams.writeStringTo(e.newOutputStream(0), kv.value);
                    e.commit();
                }
                return kv;
            } catch (IOException e) {
                kv.hasErrors = true;
                return kv;
            } finally {
                try {
                    mDiskCache.flush();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private class CacheReadTask extends  AsyncTask<String, Void, String> {
        @Override
        protected void onPreExecute() {
            mHandler.removeCallbacks(mFetchRunnable);
        }

        @Override
        protected void onPostExecute(String s) {
            if (s != null && TextUtils.isEmpty(mValueEdit.getText())) {
                mValueEdit.setText(s);
                mValueEdit.setSelection(s.length());
            }
        }

        @Override
        protected String doInBackground(String... strings) {
            String key = strings[0];
            DiskLruCache.Snapshot s = null;
            try {
                s = mDiskCache.get(key);
                if (s != null) {
                    return Streams.readStringFrom(s.getInputStream(0));
                } else {
                    return null;
                }
            } catch (IOException e) {
                return null;
            } finally {
                IoUtils.closeQuietly(s);
            }
        }
    }

    private class CacheLoadTask extends AsyncTask<Void, Void, DiskLruCache> {
        @Override
        protected void onPreExecute() {
            setEnabled(false);
        }

        @Override
        protected void onPostExecute(DiskLruCache diskLruCache) {
            if (diskLruCache != null) {
                setEnabled(true);
            } else {
                Toast.makeText(MainActivity.this, R.string.error, Toast.LENGTH_LONG).show();
            }
            mDiskCache = diskLruCache;
        }

        @Override
        protected DiskLruCache doInBackground(Void... voids) {
            File dir = new File(getCacheDir(), "example");
            try {
                return DiskLruCache.open(dir, 1, 1, 5 * 1024);
            } catch (IOException e) {
                Log.w(TAG, "open", e);
                return null;
            }
        }

        private void setEnabled(boolean enabled) {
            mKeyEdit.setEnabled(enabled);
            mValueEdit.setEnabled(enabled);
            mSetValueBtn.setEnabled(enabled);
        }
    }
}
