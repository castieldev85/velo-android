//
// Copyright 2026 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

package org.signal.libsignal.protocol.pqid;

import static org.signal.libsignal.internal.FilterExceptions.filterExceptions;

import java.util.Arrays;
import org.signal.libsignal.internal.Native;

/**
 * A monocles PQ-OMEMO2 post-quantum identity public key (ML-DSA-87 verification key).
 *
 * <p>Paired with the classical {@link org.signal.libsignal.protocol.IdentityKey}, this forms the
 * device's hybrid identity: published pre-key bundles are signed by both keys, so forging a bundle
 * requires breaking both Ed25519 and ML-DSA-87. The verified fingerprint commits to both keys.
 */
public final class PqIdentityKey {
  private final byte[] serialized;

  public PqIdentityKey(byte[] serialized) {
    this.serialized = serialized.clone();
  }

  /** The serialized ML-DSA-87 public (verification) key. */
  public byte[] serialize() {
    return serialized.clone();
  }

  /** Verify an ML-DSA-87 {@code signature} over {@code message}. */
  public boolean verify(byte[] message, byte[] signature) {
    return filterExceptions(() -> Native.PqIdentity_Verify(serialized, message, signature));
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof PqIdentityKey)) return false;
    return Arrays.equals(serialized, ((PqIdentityKey) other).serialized);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(serialized);
  }
}
