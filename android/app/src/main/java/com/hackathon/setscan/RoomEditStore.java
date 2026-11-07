package com.hackathon.setscan;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Separate sidecar avoids the existing scan directory's autosave/swap replacing newer planning edits. */
final class RoomEditStore {
    private static AtomicFile file(Context c,File scan) throws Exception {
        ItemCatalog.require(scan!=null&&scan.getName().matches("scan_[a-zA-Z0-9_-]+"),"Save the scan before editing");
        File root=new File(c.getFilesDir(),"room-plans");if(!root.exists()&&!root.mkdirs())throw new IOException("Cannot create planning storage");return new AtomicFile(new File(root,scan.getName()+".json"));
    }
    static String read(Context c,File scan) throws Exception {AtomicFile f=file(c,scan);if(!f.getBaseFile().exists()&&!new File(f.getBaseFile()+".bak").exists())return null;try(InputStream in=f.openRead()){return ItemCatalog.read(in);}}
    static void write(Context c,File scan,String json) throws Exception {AtomicFile f=file(c,scan);FileOutputStream out=null;try{out=f.startWrite();out.write(json.getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}}
}
