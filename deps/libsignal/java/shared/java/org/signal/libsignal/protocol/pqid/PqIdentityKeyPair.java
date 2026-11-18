//
// Copyright 2026 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

package org.signal.libsignal.protocol.pqid;

import static org.signal.libsignal.internal.FilterExceptions.filterExceptions;

import org.signal.libsignal.internal.Native;

/**
 * A monocles PQ-OMEMO2 post-quantum identity key pair (ML-DSA-87).
 *
 * <p>This is the long-lived post-quantum half of a device's hybrid identity. The serialized form is
 * the signing key bytes followed by the verification key bytes; callers persist it alongside the
 * classical identity key pair.
 */
public final class PqIdentityKeyPair {
  private final byte[] serialized;

  private PqIdentityKeyPair(byte[] serialized) {
    this.serialized = serialized;
  }

  /** Generate a fresh ML-DSA-87 identity key pair. */
  public static PqIdentityKeyPair generate() {
    return new PqIdentityKeyPair(Native.PqIdentityKeyPair_Generate());
  }

  /** Re-create a key pair from its {@link #serialize()} bytes. */
  public static PqIdentityKeyPair fromSerialized(byte[] serialized) {
    return new PqIdentityKeyPair(serialized.clone());
  }

  /** The serialized key pair (signing key bytes followed by verification key bytes). */
  public byte[] serialize() {
    return serialized.clone();
  }

  /** The public (verification) half of this key pair. */
  public PqIdentityKey getPublicKey() {
    return new PqIdentityKey(filterExceptions(() -> Native.PqIdentityKeyPair_GetPublicKey(serialized)));
  }

  /** Sign {@code message} under the PQ-OMEMO2 domain-separation context. */
  public byte[] sign(byte[] message) {
    return filterExceptions(() -> Native.PqIdentity_Sign(serialized, message));
  }
}
