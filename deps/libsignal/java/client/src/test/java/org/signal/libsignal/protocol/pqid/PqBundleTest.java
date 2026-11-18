//
// Copyright 2026 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

package org.signal.libsignal.protocol.pqid;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import junit.framework.TestCase;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;

public class PqBundleTest extends TestCase {

  private static byte[] filled(int length, int value) {
    final byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  private static String hex(byte[] bytes) {
    final StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (final byte b : bytes) {
      builder.append(Character.forDigit((b >> 4) & 0xF, 16));
      builder.append(Character.forDigit(b & 0xF, 16));
    }
    return builder.toString();
  }

  /**
   * {@link KEMKeyType#MLKEM1024} must actually produce FIPS 203 ML-KEM-1024, and {@link
   * KEMKeyType#KYBER_1024} must still produce Round-3 Kyber-1024.
   *
   * <p>The two have identical key and ciphertext sizes and differ only in how the shared secret is
   * derived, so nothing observable distinguishes them except the serialized type byte — 0x0A for
   * ML-KEM, 0x08 for Kyber. monocles PQ-OMEMO2 specifies FIPS 203, and shipped the wrong algorithm
   * for months because no test asserted this. The Kyber assertion is here too because the two
   * generate through different bridge entry points and it would be easy to redirect both by
   * accident.
   */
  public void testKemKeyTypesMapToTheRightAlgorithm() {
    final byte[] mlkem = KEMKeyPair.generate(KEMKeyType.MLKEM1024).getPublicKey().serialize();
    assertEquals("ML-KEM-1024 type byte", 0x0A, mlkem[0] & 0xFF);
    assertEquals("ML-KEM-1024 public key length", 1 + 1568, mlkem.length);

    final byte[] kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024).getPublicKey().serialize();
    assertEquals("Kyber-1024 type byte", 0x08, kyber[0] & 0xFF);
  }

  /**
   * Known-answer test for the monocles PQ-OMEMO2 ML-KEM binding digest, pinning the vector
   * documented in proto-XEP §4.9.1 and asserted by the Rust {@code pq_kem_binding_known_answer}.
   *
   * <p>This runs the digest through JNI, which is the point: {@link PqBundle#kemBinding} does not
   * reimplement the construction, it serialises the one-time set and calls the Rust. That is what
   * keeps it from drifting from the value {@code process_prekey_bundle} verifies against — but it
   * also means the marshalling is only as correct as this test proves. JNI binds on the short
   * method name, so a mismatched declaration would pass garbage arguments rather than fail to
   * link, and the fault would surface as every PQ session failing to establish.
   */
  public void testKemBindingKnownAnswer() {
    final List<PqBundle.KemOneTimeKey> oneTime =
        Arrays.asList(
            new PqBundle.KemOneTimeKey(2, filled(3, 0xBB)),
            new PqBundle.KemOneTimeKey(3, filled(5, 0xCC)));
    final byte[] binding = PqBundle.kemBinding(1, filled(4, 0xAA), oneTime);
    assertEquals(64, binding.length);
    assertEquals(
        "7f93e76279a9e5c4dffe34e20c809fb2d1ef8dafaebf26742725aa4a1eba4d0f"
            + "fac892689509fa655a04fb8a6d6c0beff91f757ab714b6cb8c8cd3303c8c98f2",
        hex(binding));
  }

  /** An empty one-time set is well defined (all-zero manifest) and distinct from a populated one. */
  public void testKemBindingWithNoOneTimeKeys() {
    final byte[] empty = PqBundle.kemBinding(1, filled(4, 0xAA), Collections.emptyList());
    final byte[] populated =
        PqBundle.kemBinding(
            1, filled(4, 0xAA), Collections.singletonList(new PqBundle.KemOneTimeKey(2, filled(3, 0xBB))));
    assertEquals(64, empty.length);
    assertFalse(Arrays.equals(empty, populated));
  }

  /**
   * Every one-time key is bound, not just the one an initiator ends up selecting — substituting any
   * of them must change the digest, which is what makes the ML-DSA-87 signature cover the whole
   * published set (§4.9.1).
   */
  public void testKemBindingCoversEveryOneTimeKey() {
    final byte[] base =
        PqBundle.kemBinding(
            1,
            filled(4, 0xAA),
            Arrays.asList(
                new PqBundle.KemOneTimeKey(2, filled(3, 0xBB)),
                new PqBundle.KemOneTimeKey(3, filled(5, 0xCC))));

    // Substituting the *second* key — the one an initiator selecting the first would never use.
    final byte[] swappedKey =
        PqBundle.kemBinding(
            1,
            filled(4, 0xAA),
            Arrays.asList(
                new PqBundle.KemOneTimeKey(2, filled(3, 0xBB)),
                new PqBundle.KemOneTimeKey(3, filled(5, 0xCD))));
    assertFalse(Arrays.equals(base, swappedKey));

    // Renumbering binds too: ids are hashed alongside the keys.
    final byte[] swappedId =
        PqBundle.kemBinding(
            1,
            filled(4, 0xAA),
            Arrays.asList(
                new PqBundle.KemOneTimeKey(2, filled(3, 0xBB)),
                new PqBundle.KemOneTimeKey(4, filled(5, 0xCC))));
    assertFalse(Arrays.equals(base, swappedId));

    // As does the signed ("last-resort") key and its id.
    assertFalse(
        Arrays.equals(
            base,
            PqBundle.kemBinding(
                1,
                filled(4, 0xAB),
                Arrays.asList(
                    new PqBundle.KemOneTimeKey(2, filled(3, 0xBB)),
                    new PqBundle.KemOneTimeKey(3, filled(5, 0xCC))))));
  }

  private static byte[] sha3_512(byte[] data) throws Exception {
    return java.security.MessageDigest.getInstance("SHA3-512").digest(data);
  }

  private static byte[] prefixed(int first, int length, int value) {
    final byte[] bytes = filled(length + 1, value);
    bytes[0] = (byte) first;
    return bytes;
  }

  /**
   * Known answer for the v4 bundle transcript (proto-XEP §4.9.1), shared with the Rust {@code
   * pq_bundle_transcript_known_answer}.
   */
  public void testBundleTranscriptKnownAnswer() throws Exception {
    final byte[] transcript =
        PqBundle.transcript(
            new IdentityKey(prefixed(0x05, 32, 0x11)),
            filled(2592, 0x22),
            7,
            new ECPublicKey(prefixed(0x05, 32, 0x33)),
            filled(64, 0x44));
    assertEquals(
        "51006cbeb032541274a0222b2b2e6ef68250803ccd9f8921ffcd8a93b4be7028"
            + "dec4c46f0dfb1d50a3add5593871157dd0c1ce958d5aa35f320ffbdafcfd621a",
        hex(sha3_512(transcript)));
  }

  /** {@link PqBundle#verify} accepts only a transcript signed by BOTH identity keys. */
  public void testVerifyRequiresBothSignatures() {
    final IdentityKeyPair identity = IdentityKeyPair.generate();
    final IdentityKeyPair other = IdentityKeyPair.generate();
    final PqIdentityKeyPair pq = PqIdentityKeyPair.generate();
    final ECPublicKey spk = ECKeyPair.generate().getPublicKey();
    final byte[] binding = filled(64, 0x44);
    final byte[] transcript =
        PqBundle.transcript(identity.getPublicKey(), pq.getPublicKey().serialize(), 7, spk, binding);
    final byte[] pqSig = pq.sign(transcript);
    final byte[] ikSig = identity.getPrivateKey().calculateSignature(transcript);
    assertTrue(
        PqBundle.verify(identity.getPublicKey(), pq.getPublicKey().serialize(), pqSig, ikSig, 7, spk, binding));
    final byte[] wrongIkSig = other.getPrivateKey().calculateSignature(transcript);
    assertFalse(
        PqBundle.verify(
            identity.getPublicKey(), pq.getPublicKey().serialize(), pqSig, wrongIkSig, 7, spk, binding));
    assertFalse(
        PqBundle.verify(
            identity.getPublicKey(), pq.getPublicKey().serialize(), pqSig, new byte[0], 7, spk, binding));
    assertFalse(
        PqBundle.verify(identity.getPublicKey(), pq.getPublicKey().serialize(), pqSig, ikSig, 8, spk, binding));
  }

  /**
   * Known answer for the handshake-authentication transcript (proto-XEP §4.9.7), shared with the
   * Rust {@code kex_auth_transcript_known_answer}; also proves the bridge marshalling.
   */
  public void testKexAuthTranscriptKnownAnswer() throws Exception {
    final byte[] transcript =
        PqAuth.transcript(
            "Alice@Example.com",
            42,
            new IdentityKey(prefixed(0x05, 32, 0x11)),
            filled(2592, 0x22),
            Arrays.asList(
                new PqAuth.Entry("Bob@Example.org", 12, filled(40, 0x55)),
                new PqAuth.Entry("alice@example.com", 7, filled(50, 0x66)),
                new PqAuth.Entry("alice@example.com", 3, filled(30, 0x77))));
    assertEquals(
        "281cdeaf99a47eadaa3d51a49ae09c743afc7478b943d9995f7db71ef0533679"
            + "988c4923ebf5ed8c1e1407c199fe6a0dee8439c6f2385cbb527b924bb9859248",
        hex(sha3_512(transcript)));
  }
}
