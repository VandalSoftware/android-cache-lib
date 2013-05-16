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

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

/**
 * @author Jonathan Le
 */
public class MainActivity extends ListActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        final ListItem[] items = {
                new ListItem(new Intent(this, SetValueActivity.class),
                        getString(R.string.set_value_label))
        };
        setListAdapter(new ListItemAdapter(items));
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        final ListItem item = (ListItem) l.getItemAtPosition(position);
        startActivityForResult(item.intent, 0);
    }

    private static class ListItem {
        public final Intent intent;
        public final String label;

        private ListItem(Intent intent, String label) {
            this.intent = intent;
            this.label = label;
        }
    }

    private static class ListItemAdapter extends BaseAdapter {
        private final ListItem[] mItems;

        public ListItemAdapter(ListItem[] items) {
            mItems = items;
        }

        @Override
        public int getCount() {
            return mItems.length;
        }

        @Override
        public ListItem getItem(int pos) {
            return mItems[pos];
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.list_row_view, parent, false);
            }
            final TextView v = (TextView) convertView;
            v.setText(mItems[pos].label);
            return convertView;
        }
    }
}
