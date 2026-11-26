package com.runzone;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only, per-URI sharing of FIT files and share-card images. No storage permission or public directory. */
public class ExportProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException {
        String name=uri.getLastPathSegment();if(name==null||!name.matches("runzone-[a-zA-Z0-9_-]+\\.(fit|png)")||uri.getPathSegments().size()!=1)throw new FileNotFoundException();
        File file=new File(new File(getContext().getCacheDir(),"exports"),name);if(!file.isFile())throw new FileNotFoundException();return file;
    }
    @Override public String getType(Uri uri){String name=uri.getLastPathSegment();return name!=null&&name.endsWith(".png")?"image/png":FitExporter.MIME_TYPE;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String sort){
        try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=file.getName();else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();}cursor.addRow(values);return cursor;}catch(FileNotFoundException e){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
}
