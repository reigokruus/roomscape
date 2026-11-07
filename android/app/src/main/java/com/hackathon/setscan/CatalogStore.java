package com.hackathon.setscan;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** App-private offline database; imports validate completely before an atomic cache replacement. */
final class CatalogStore {
    private final Context context;
    private final AtomicFile cache;
    CatalogStore(Context c){context=c.getApplicationContext();cache=new AtomicFile(new File(c.getFilesDir(),"equipment-catalog.json"));}
    synchronized List<ItemCatalog.Item> load() throws Exception {
        if(cache.getBaseFile().exists() || new File(cache.getBaseFile()+".bak").exists())try(InputStream in=cache.openRead()){return ItemCatalog.parse(ItemCatalog.read(in));}
        try(InputStream in=context.getAssets().open("equipment-catalog.json")){return ItemCatalog.parse(ItemCatalog.read(in));}
    }
    synchronized int importText(String text) throws Exception {
        List<ItemCatalog.Item> incoming=ItemCatalog.parse(text), merged=ItemCatalog.merge(load(),incoming);byte[] bytes=ItemCatalog.encode(merged).getBytes(StandardCharsets.UTF_8);
        FileOutputStream out=null;try{out=cache.startWrite();out.write(bytes);cache.finishWrite(out);}catch(Exception e){if(out!=null)cache.failWrite(out);throw e;}return incoming.size();
    }
    static String download(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)ItemCatalog.feedUri(url).toURL().openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestProperty("Accept","application/json, text/plain");
        try{if(c.getResponseCode()!=200)throw new IOException("Feed HTTP "+c.getResponseCode()+" (redirects not followed)");if(c.getContentLengthLong()>ItemCatalog.MAX_BYTES)throw new IOException("Feed exceeds 256 KiB");try(InputStream in=c.getInputStream()){String s=ItemCatalog.read(in);ItemCatalog.parse(s);return s;}}finally{c.disconnect();}
    }
}
