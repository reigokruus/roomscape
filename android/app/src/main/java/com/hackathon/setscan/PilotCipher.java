package com.hackathon.setscan;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Authenticated file envelope. Android supplies the non-exported Keystore key; tests use a synthetic key. */
final class PilotCipher {
    private static final int MAGIC=0x43585031, IV_BYTES=12, TAG_BITS=128;
    private static final byte[] AAD="CineX pilot notebook v1".getBytes(StandardCharsets.UTF_8);
    static byte[] encrypt(byte[] plain,SecretKey key)throws Exception{
        PilotIntake.require(plain.length<=PilotIntake.MAX_BYTES,"Pilot notebook exceeds storage limit.");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(AAD);byte[] iv=c.getIV();
        PilotIntake.require(iv.length==IV_BYTES,"Unsupported encryption IV.");byte[] cipher=c.doFinal(plain);return ByteBuffer.allocate(4+IV_BYTES+cipher.length).putInt(MAGIC).put(iv).put(cipher).array();
    }
    static byte[] decrypt(byte[] encrypted,SecretKey key)throws Exception{
        PilotIntake.require(encrypted.length>=4+IV_BYTES+16&&encrypted.length<=PilotIntake.MAX_BYTES+4+IV_BYTES+16,"Invalid encrypted notebook size.");
        ByteBuffer b=ByteBuffer.wrap(encrypted);PilotIntake.require(b.getInt()==MAGIC,"Unsupported encrypted notebook version.");byte[] iv=new byte[IV_BYTES],cipher=new byte[b.remaining()-IV_BYTES];b.get(iv);b.get(cipher);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(TAG_BITS,iv));c.updateAAD(AAD);return c.doFinal(cipher);
    }
}
