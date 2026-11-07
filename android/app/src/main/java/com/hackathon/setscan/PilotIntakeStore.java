package com.hackathon.setscan;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.function.UnaryOperator;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Separate encrypted no-backup notebook. It is never enumerated by room/scan storage or export. */
final class PilotIntakeStore {
    private static final String ALIAS="cinex.pilot.notebook.v1";
    private final AtomicFile file;
    PilotIntakeStore(Context c){file=new AtomicFile(new File(c.getNoBackupFilesDir(),"pilot-interest-v1.enc"));}
    synchronized PilotIntake.Notebook load()throws Exception{
        if(!file.getBaseFile().exists()&&!new File(file.getBaseFile()+".bak").exists())return new PilotIntake.Notebook();
        try(InputStream in=file.openRead()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];for(int n;(n=in.read(buf))!=-1;){if(out.size()+n>PilotIntake.MAX_BYTES+32)throw new IOException("Notebook storage limit.");out.write(buf,0,n);}
            return PilotIntake.decode(new String(PilotCipher.decrypt(out.toByteArray(),key(false)),StandardCharsets.UTF_8));
        } catch(Exception e){throw new IOException("Pilot notebook could not be opened. Device key or file may be unavailable. Nothing was reset or overwritten.");}
    }
    synchronized PilotIntake.Notebook change(UnaryOperator<PilotIntake.Notebook> edit)throws Exception{
        PilotIntake.Notebook next=edit.apply(load());byte[] plain=PilotIntake.encode(next).getBytes(StandardCharsets.UTF_8);byte[] encrypted=PilotCipher.encrypt(plain,key(true));
        FileOutputStream out=null;try{out=file.startWrite();out.write(encrypted);file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw new IOException("Pilot notebook was not saved. Previous stored records were kept.");}return next;
    }
    private SecretKey key(boolean create)throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        if(!create)throw new IOException("Device encryption key unavailable.");
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).setKeySize(256).build());return gen.generateKey();
    }
}
