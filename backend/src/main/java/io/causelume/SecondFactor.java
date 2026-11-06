package io.causelume;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
class SecondFactor {
    private final SecureRandom random=new SecureRandom();
    private final TimeBasedOneTimePasswordGenerator totp=new TimeBasedOneTimePasswordGenerator();
    private final SecretKeySpec key;
    final boolean required;

    SecondFactor(Environment env) {
        required=env.acceptsProfiles(Profiles.of("prod"));
        String encoded=env.getProperty("APP_MFA_KEY","");
        if (encoded.isBlank()) {
            if(required) throw new IllegalStateException("prod 需要 APP_MFA_KEY");
            key=null;
        } else {
            byte[] raw=Base64.getDecoder().decode(encoded.strip());
            if(raw.length!=32) throw new IllegalStateException("APP_MFA_KEY 需为 base64 编码的 32 字节随机密钥");
            key=new SecretKeySpec(raw,"AES");
        }
    }

    byte[] newSeed() {
        if(key==null) throw new Problem(503,"请管理员配置 MFA 加密密钥");
        byte[] bytes=new byte[20];
        random.nextBytes(bytes);
        return bytes;
    }
    String base32(byte[] seed) { return new Base32().encodeAsString(seed); }

    String encrypt(long account,byte[] seed) {
        try {
            byte[] iv=new byte[12];
            random.nextBytes(iv);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            cipher.updateAAD(ByteBuffer.allocate(8).putLong(account).array());
            byte[] ciphertext=cipher.doFinal(seed);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length+ciphertext.length).put(iv).put(ciphertext).array());
        } catch (GeneralSecurityException e) { throw new IllegalStateException("MFA seed 加密失败",e); }
    }
    byte[] decrypt(long account,String envelope) {
        if(key==null) throw new Problem(503,"MFA 密钥不可用");
        try {
            byte[] bytes=Base64.getDecoder().decode(envelope);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,bytes,0,12));
            cipher.updateAAD(ByteBuffer.allocate(8).putLong(account).array());
            return cipher.doFinal(bytes,12,bytes.length-12);
        } catch(GeneralSecurityException | IllegalArgumentException e) {
            throw new Problem(503,"MFA 密钥或账户绑定记录不可用");
        }
    }
    long match(byte[] seed,String code,long lastStep) {
        if(code==null || !code.matches("[0-9]{6}")) return -1;
        long now=Instant.now().getEpochSecond()/30;
        var secret=new SecretKeySpec(seed,"HmacSHA1");
        for(long step=now-1;step<=now+1;step++) {
            if(step<=lastStep) continue;
            try {
                if(totp.validateOneTimePassword(secret,Instant.ofEpochSecond(step*30),code)) return step;
            } catch(InvalidKeyException e) { throw new IllegalStateException("MFA seed 无效",e); }
        }
        return -1;
    }
    List<String> issueRecoveryCodes(Store store,long id) {
        List<String> codes=new ArrayList<>();
        store.db.update("delete from recovery_codes where account_id=?",id);
        for(int i=0;i<8;i++) {
            byte[] bytes=new byte[16];
            random.nextBytes(bytes);
            String code=HexFormat.of().formatHex(bytes);
            store.db.update("insert into recovery_codes(account_id,code_hash) values (?,?)",id,DigestUtils.sha256Hex(code));
            codes.add(code);
        }
        return codes;
    }
    boolean consume(Store store,Map<String,Object> row,String code) {
        long id=(long)row.get("id");
        if(code.matches("[a-f0-9]{32}"))
            return store.db.update("delete from recovery_codes where account_id=? and code_hash=?",id,DigestUtils.sha256Hex(code))==1;
        long step=match(decrypt(id,(String)row.get("mfaSeed")),code,(long)row.get("lastTotpStep"));
        return step>=0 && store.db.update("update accounts set last_totp_step=? where id=? and last_totp_step<?",step,id,step)==1;
    }
}
