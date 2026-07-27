package com.integration.push;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.util.Base64;

/**
 * One-off utility that prints a VAPID key pair. Run it once per environment, put the
 * output in VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY, and never rotate casually — changing
 * the public key invalidates every existing browser subscription, so all users would
 * have to re-enable alerts.
 *
 * <pre>
 *   ./gradlew -q vapidKeys
 * </pre>
 *
 * Uses plain JCA (P-256): the public key is the uncompressed point 0x04‖X‖Y and the
 * private key is the raw 32-byte scalar, both base64url-encoded without padding — the
 * exact encoding the Web Push spec and the browser's applicationServerKey expect.
 */
public final class VapidKeyGenerator {

    private VapidKeyGenerator() {}

    public static void main(String[] args) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = kpg.generateKeyPair();

        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        ECPrivateKey priv = (ECPrivateKey) pair.getPrivate();

        ECPoint w = pub.getW();
        byte[] publicKey = new byte[65];
        publicKey[0] = 0x04;
        System.arraycopy(fixedWidth(w.getAffineX(), 32), 0, publicKey, 1, 32);
        System.arraycopy(fixedWidth(w.getAffineY(), 32), 0, publicKey, 33, 32);

        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        System.out.println();
        System.out.println("VAPID_PUBLIC_KEY=" + b64.encodeToString(publicKey));
        System.out.println("VAPID_PRIVATE_KEY=" + b64.encodeToString(fixedWidth(priv.getS(), 32)));
        System.out.println();
        System.out.println("Set both as environment variables (and VAPID_SUBJECT=mailto:you@example.com).");
    }

    /** Left-pads/strips a BigInteger to exactly {@code length} bytes, dropping BigInteger's sign byte. */
    private static byte[] fixedWidth(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, out, 0, length);
        } else {
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        }
        return out;
    }
}
