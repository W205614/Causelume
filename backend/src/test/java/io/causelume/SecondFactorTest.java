package io.causelume;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class SecondFactorTest {
    private final SecondFactor mfa=new SecondFactor(new MockEnvironment()
        .withProperty("APP_MFA_KEY",Base64.getEncoder().encodeToString(new byte[32])));

    @Test void encryptedSeedRoundTripsAndAuthenticatesItsAccount() {
        byte[] seed=mfa.newSeed();
        String envelope=mfa.encrypt(7,seed);
        assertArrayEquals(seed,mfa.decrypt(7,envelope));
        assertEquals(503,assertThrows(Problem.class,()->mfa.decrypt(8,envelope)).status);
        byte[] altered=Base64.getDecoder().decode(envelope);
        altered[altered.length-1]^=1;
        assertEquals(503,assertThrows(Problem.class,()->mfa.decrypt(7,Base64.getEncoder().encodeToString(altered))).status);
    }

    @Test void repeatedEncryptionUsesIndependentNonces() {
        byte[] seed=mfa.newSeed();
        String first=mfa.encrypt(7,seed),second=mfa.encrypt(7,seed);
        assertNotEquals(first,second);
        assertArrayEquals(seed,mfa.decrypt(7,first));
        assertArrayEquals(seed,mfa.decrypt(7,second));
    }
}
