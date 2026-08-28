package com.pan.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 轻量文件 Provider：把 /sdcard/Download 下的文件以 content:// 形式暴露给
 * 其它 App（系统"打开/安装"），从而规避 API 24+ 的 FileUriExposedException。
 * 用法：content://com.pan.mobile.pan/file?path=<绝对路径>
 */
public class PanProvider extends ContentProvider {

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getQueryParameter("path");
        if (path == null) throw new FileNotFoundException("no path");
        File f = new File(path);
        if (!f.exists() || !f.canRead()) throw new FileNotFoundException(path);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) { return null; }

    @Override
    public String getType(Uri uri) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
