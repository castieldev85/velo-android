//
// Copyright 2026 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

package org.signal.libsignal.protocol.pqid;

import static org.signal.libsignal.internal.FilterExceptions.filterExceptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import org.signal.libsignal.internal.Native;
import org.signal.libsignal.internal.NativeHandleGuard;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.ecc.ECPublicKey;

/** Helpers for the monocles PQ-OMEMO2 hybrid-identity bundle signature. */
public final class PqBundle {
  private PqBundle() {}

  /** One published one-time ML-KEM pre-key: its id and serialized public key. */
  public static final class KemOneTimeKey {
    public final int id;
    public final byte[] publicKey;

    public KemOneTimeKey(int id, byte[] publicKey) {
      this.id = id;
      this.publicKey = publicKey;
    }
  }

  /**
   * Build the canonical v4 transcript that a device signs with BOTH its {@link PqIdentityKeyPair}
   * and its classical identity key (and that {@code process_prekey_bundle} verifies with both). It
   * binds the post-quantum identity key to the classical {@link IdentityKey}, the EC signed
   * pre-key, the fixed algorithm identifiers, and — via {@code kemBinding} ({@link #kemBinding}) —
   * all of the bundle's ML-KEM pre-keys. The ML-DSA-87 signature stops a quantum adversary from
   * substituting any of those; the classical signature stops anyone without the device's
   * classical private key from attaching a post-quantum identity of their own.
   *
   * @param identityKey the device's classical identity key
   * @param pqIdentityKey the serialized ML-DSA-87 public key
   * @param signedPreKeyId the EC signed pre-key id published in the bundle
   * @param signedPreKey the EC signed pre-key public key published in the bundle
   * @param kemBinding the 64-byte ML-KEM binding digest from {@link #kemBinding}
   */
  public static byte[] transcript(
      IdentityKey identityKey,
      byte[] pqIdentityKey,
      int signedPreKeyId,
      ECPublicKey signedPreKey,
      byte[] kemBinding) {
    try (NativeHandleGuard ikGuard = new NativeHandleGuard(identityKey.getPublicKey());
        NativeHandleGuard spkGuard = new NativeHandleGuard(signedPreKey)) {
      return filterExceptions(
          () ->
              Native.PqBundle_Transcript(
                  ikGuard.nativeHandle(),
                  pqIdentityKey,
                  signedPreKeyId,
                  spkGuard.nativeHandle(),
                  kemBinding));
    }
  }

  /**
   * Verify both signatures over a bundle's v4 transcript: the ML-DSA-87 {@code pqSignature} by
   * {@code pqIdentityKey} and the XEdDSA {@code ikSignature} by {@code identityKey}. This is the
   * same check {@code process_prekey_bundle} performs, for verification paths outside session
   * establishment (out-of-band pin reconciliation). Malformed keys or signatures yield false.
   */
  public static boolean verify(
      IdentityKey identityKey,
      byte[] pqIdentityKey,
      byte[] pqSignature,
      byte[] ikSignature,
      int signedPreKeyId,
      ECPublicKey signedPreKey,
      byte[] kemBinding) {
    try (NativeHandleGuard ikGuard = new NativeHandleGuard(identityKey.getPublicKey());
        NativeHandleGuard spkGuard = new NativeHandleGuard(signedPreKey)) {
      return filterExceptions(
          () ->
              Native.PqBundle_Verify(
                  ikGuard.nativeHandle(),
                  pqIdentityKey,
                  pqSignature,
                  ikSignature,
                  signedPreKeyId,
                  spkGuard.nativeHandle(),
                  kemBinding));
    }
  }

  /**
   * Compute the 64-byte ML-KEM binding digest that ties a bundle's post-quantum signature to all of
   * its ML-KEM pre-keys.
   *
   * <p>The last-resort {@code <kem-spk>} is bound directly; the one-time {@code <kem-pk>} set is
   * folded into a manifest hash (an empty set yields an all-zero manifest). {@code oneTime} MUST be
   * in bundle document order.
   *
   * <p>This delegates to the Rust {@code pq_kem_binding} (see {@code
   * rust/protocol/src/state/bundle.rs}) rather than reimplementing it. That is deliberate: the
   * digest must match, byte for byte, the value {@code process_prekey_bundle} verifies against, and
   * a second implementation here could silently drift from it. It also keeps the SHA3-512 out of
   * the JCA, which has no {@code SHA3-512} provider below API 29.
   *
   * @param kemSignedPreKeyId the id of the signed ("last-resort") KEM pre-key
   * @param kemSignedPreKeyPublic the serialized public key of the signed KEM pre-key
   * @param oneTime every published one-time KEM pre-key, in bundle document order
   */
  public static byte[] kemBinding(
      int kemSignedPreKeyId, byte[] kemSignedPreKeyPublic, List<KemOneTimeKey> oneTime) {
    // The one-time set crosses the bridge as one flat buffer of
    // u32_be(id) || u32_be(len) || publicKey records, in document order.
    final ByteArrayOutputStream records = new ByteArrayOutputStream();
    for (final KemOneTimeKey k : oneTime) {
      records.write(u32be(k.id), 0, 4);
      records.write(u32be(k.publicKey.length), 0, 4);
      records.write(k.publicKey, 0, k.publicKey.length);
    }
    return filterExceptions(
        () -> Native.PqBundle_KemBinding(kemSignedPreKeyId, kemSignedPreKeyPublic, records.toByteArray()));
  }

  private static byte[] u32be(int value) {
    return ByteBuffer.allocate(4).putInt(value).array();
  }
}
