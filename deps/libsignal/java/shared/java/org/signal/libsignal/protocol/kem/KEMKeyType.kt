//
// Copyright 2023 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

package org.signal.libsignal.protocol.kem

public enum class KEMKeyType(
  public val type: Int,
) {
  /** CRYSTALS-Kyber-1024 as submitted to NIST PQC Round 3 — *not* the FIPS 203 standard. */
  KYBER_1024(8),

  /**
   * ML-KEM-1024 (NIST FIPS 203).
   *
   * Not interoperable with [KYBER_1024] despite identical key and ciphertext sizes: Round 3
   * derives the shared secret as `KDF(K̄ ‖ H(ct))` while FIPS 203 outputs it directly, so a
   * mismatched pair completes the exchange and then disagrees on the secret. The distinct type
   * byte is what keeps that from happening silently.
   */
  MLKEM1024(10),
}
