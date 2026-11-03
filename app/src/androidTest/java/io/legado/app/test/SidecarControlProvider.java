package io.legado.app.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Bootstrap control channel; every call checks the debug caller UID in the document provider. */
public final class SidecarControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        return SidecarDocumentsProvider.control(method, arg, extras);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
