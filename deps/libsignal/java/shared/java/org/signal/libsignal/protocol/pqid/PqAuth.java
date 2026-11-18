//
// Copyright 2026 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

package org.signal.libsignal.protocol.pqid;

import static org.signal.libsignal.internal.FilterExceptions.filterExceptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.signal.libsignal.internal.Native;
import org.signal.libsignal.internal.NativeHandleGuard;
import org.signal.libsignal.protocol.IdentityKey;

/**
 * Post-quantum authentication of the monocles PQ-OMEMO2 handshake ({@code <pq-auth>}).
 *
 * <p>The initiator signs, with its ML-DSA-87 identity ({@link PqIdentityKeyPair#sign}), one
 * transcript covering every pre-key message a stanza carries; the receiver recomputes it and
 * verifies ({@link PqIdentityKey#verify}) before processing the handshake. The transcript covers
 * handshake material only, never message content.
 */
public final class PqAuth {
  private PqAuth() {}

  /** One {@code <key kex='true'>} element of the stanza being authenticated. */
  public static final class Entry {
    public final String recipientJid;
    public final int recipientDeviceId;
    public final byte[] preKeyMessage;

    /**
     * @param recipientJid the bare JID of the {@code <keys jid='…'>} block the key sits in
     * @param recipientDeviceId the key element's {@code rid}
     * @param preKeyMessage the serialized {@code PreKeySignalMessage} (the decoded key content)
     */
    public Entry(String recipientJid, int recipientDeviceId, byte[] preKeyMessage) {
      this.recipientJid = recipientJid;
      this.recipientDeviceId = recipientDeviceId;
      this.preKeyMessage = preKeyMessage;
    }
  }

  /**
   * Build the handshake-authentication transcript. Delegates to the Rust {@code
   * pq_kex_auth_transcript}, which the desktop client calls directly, so the two clients cannot
   * drift apart. Entries may be passed in any order; the transcript sorts them.
   *
   * @param senderJid the sender's bare JID
   * @param senderDeviceId the sender's device id ({@code <header sid>})
   * @param senderIdentityKey the sender's classical identity key
   * @param senderPqIdentityKey the sender's serialized ML-DSA-87 public key
   * @param entries every {@code <key kex='true'>} of the stanza
   */
  public static byte[] transcript(
      String senderJid,
      int senderDeviceId,
      IdentityKey senderIdentityKey,
      byte[] senderPqIdentityKey,
      List<Entry> entries) {
    final byte[] records = encode(entries);
    try (NativeHandleGuard ikGuard = new NativeHandleGuard(senderIdentityKey.getPublicKey())) {
      return filterExceptions(
          () ->
              Native.PqAuth_Transcript(
                  senderJid,
                  senderDeviceId,
                  ikGuard.nativeHandle(),
                  senderPqIdentityKey,
                  records));
    }
  }

  /**
   * Verify a {@code <pq-auth>}: the ML-DSA-87 signature by {@code senderPqIdentityKey} AND the
   * classical signature by {@code senderIdentityKey}, both over {@link #transcript}. Delegates to
   * the Rust {@code pq_kex_auth_verify}, the one routine both clients use, so no caller can check
   * one signature and forget the other. Malformed keys or signatures yield false.
   */
  public static boolean verify(
      String senderJid,
      int senderDeviceId,
      IdentityKey senderIdentityKey,
      byte[] senderPqIdentityKey,
      byte[] pqSignature,
      byte[] ikSignature,
      List<Entry> entries) {
    final byte[] records = encode(entries);
    try (NativeHandleGuard ikGuard = new NativeHandleGuard(senderIdentityKey.getPublicKey())) {
      return filterExceptions(
          () ->
              Native.PqAuth_Verify(
                  senderJid,
                  senderDeviceId,
                  ikGuard.nativeHandle(),
                  senderPqIdentityKey,
                  pqSignature,
                  ikSignature,
                  records));
    }
  }

  private static byte[] encode(List<Entry> entries) {
    final ByteArrayOutputStream records = new ByteArrayOutputStream();
    for (final Entry e : entries) {
      final byte[] jid = e.recipientJid.getBytes(StandardCharsets.UTF_8);
      records.write(u32be(jid.length), 0, 4);
      records.write(jid, 0, jid.length);
      records.write(u32be(e.recipientDeviceId), 0, 4);
      records.write(u32be(e.preKeyMessage.length), 0, 4);
      records.write(e.preKeyMessage, 0, e.preKeyMessage.length);
    }
    return records.toByteArray();
  }

  private static byte[] u32be(int value) {
    return ByteBuffer.allocate(4).putInt(value).array();
  }
}
