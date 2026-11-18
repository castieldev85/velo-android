//
// Copyright 2026 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Post-quantum authentication of the PQ-OMEMO2 handshake (`<pq-auth>`).
//!
//! PQXDH authenticates the initiator only through the classical Diffie-Hellman
//! of its identity key, so an adversary able to compute discrete logarithms can
//! recover that key and impersonate the device, even to a peer that verified it.
//! In the monocles profile the initiator therefore also signs, with its
//! ML-DSA-87 identity key ([`crate::PqIdentityKeyPair`]), every
//! `PreKeySignalMessage` a stanza carries. The receiver checks that signature
//! against the post-quantum key it pinned for the sender before it processes the
//! handshake.
//!
//! One signature covers the whole stanza (all of its `<key kex='true'>`
//! elements), not one per key: an ML-DSA-87 signature is 4627 bytes, and a group
//! message to many fresh devices must stay well inside server stanza limits.
//!
//! The signature covers handshake material only, never message content: after
//! the handshake both parties hold the same symmetric keys, so either could have
//! produced any later message. What becomes provable is that this device opened
//! a session with these recipient devices — metadata the senders' and
//! recipients' servers already see.
//!
//! This module builds the transcript and verifies `<pq-auth>`
//! ([`pq_kex_auth_verify`] — the one verification routine both clients call, so
//! neither can check one signature and forget the other). Signing uses the plain
//! primitives; the policy (when a signature is required, pinning) lives in the
//! application.

use sha3::{Digest, Sha3_512};

use crate::{
    IdentityKey, PQ_IDENTITY_PUBLIC_KEY_LEN, PQ_IDENTITY_SIGNATURE_LEN, PQ_IK_SIGNATURE_LEN,
    PqIdentityKey, PqSignature,
};

/// Domain-separation prefix for the handshake-authentication transcript.
const KEX_AUTH_LABEL: &[u8] = b"monocles:omemo2:kex-auth:v1";

/// The signature algorithm this transcript version fixes, bound into it.
const KEX_AUTH_ALGORITHM: &[u8] = b"ML-DSA-87";

/// One `<key kex='true'>` element of the stanza being authenticated.
pub struct KexAuthEntry<'a> {
    /// The bare JID of the `<keys jid='…'>` block the key sits in.
    pub recipient_jid: &'a str,
    /// The key element's `rid`.
    pub recipient_device_id: u32,
    /// The serialized `PreKeySignalMessage` (the decoded `<key>` content).
    pub prekey_message: &'a [u8],
}

fn push_len_prefixed(out: &mut Vec<u8>, bytes: &[u8]) {
    out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(bytes);
}

/// JIDs enter the transcript lower-cased: localpart and domainpart compare
/// case-insensitively in XMPP, and the sender and receiver may render the same
/// bare JID with different case. Doing it here, in the one shared
/// implementation, keeps both clients byte-identical.
fn canonical_jid(jid: &str) -> Vec<u8> {
    jid.to_lowercase().into_bytes()
}

/// Build the transcript the initiator's ML-DSA-87 identity key signs in
/// `<pq-auth>`.
///
/// ```text
/// "monocles:omemo2:kex-auth:v1" || lp("ML-DSA-87")
/// || lp(lower(sender bare JID)) || u32be(sender device id)
/// || lp(sender IK) || lp(sender PQ-IK)
/// || u32be(n) || for each entry, sorted by (JID bytes, rid, digest):
///      lp(lower(recipient bare JID)) || u32be(rid) || lp(SHA3-512(PreKeySignalMessage))
/// ```
///
/// Entries are sorted here, so the order in which a sender wrote the keys, or a
/// receiver read them, cannot change the result.
pub fn pq_kex_auth_transcript(
    sender_jid: &str,
    sender_device_id: u32,
    sender_identity_key: &IdentityKey,
    sender_pq_identity_key: &PqIdentityKey,
    entries: &[KexAuthEntry<'_>],
) -> Vec<u8> {
    let mut canonical: Vec<(Vec<u8>, u32, [u8; 64])> = entries
        .iter()
        .map(|e| {
            (
                canonical_jid(e.recipient_jid),
                e.recipient_device_id,
                Sha3_512::digest(e.prekey_message).into(),
            )
        })
        .collect();
    canonical.sort();

    let ik = sender_identity_key.serialize();
    let pq_ik = sender_pq_identity_key.serialize();
    let mut out = Vec::with_capacity(
        KEX_AUTH_LABEL.len() + pq_ik.len() + 128 + canonical.len() * 140,
    );
    out.extend_from_slice(KEX_AUTH_LABEL);
    push_len_prefixed(&mut out, KEX_AUTH_ALGORITHM);
    push_len_prefixed(&mut out, &canonical_jid(sender_jid));
    out.extend_from_slice(&sender_device_id.to_be_bytes());
    push_len_prefixed(&mut out, &ik);
    push_len_prefixed(&mut out, &pq_ik);
    out.extend_from_slice(&(canonical.len() as u32).to_be_bytes());
    for (jid, rid, digest) in &canonical {
        push_len_prefixed(&mut out, jid);
        out.extend_from_slice(&rid.to_be_bytes());
        push_len_prefixed(&mut out, digest);
    }
    out
}

/// Verify a `<pq-auth>`: the ML-DSA-87 signature by `sender_pq_identity_key` AND the
/// XEdDSA signature by `sender_identity_key`, both over [`pq_kex_auth_transcript`].
///
/// Malformed input — a key or signature of the wrong length, or one that does not
/// deserialize — yields `false`, never an error. Both signatures are evaluated (no
/// short-circuit), so a failure does not reveal which half failed.
pub fn pq_kex_auth_verify(
    sender_jid: &str,
    sender_device_id: u32,
    sender_identity_key: &IdentityKey,
    sender_pq_identity_key: &[u8],
    pq_signature: &[u8],
    ik_signature: &[u8],
    entries: &[KexAuthEntry<'_>],
) -> bool {
    if sender_pq_identity_key.len() != PQ_IDENTITY_PUBLIC_KEY_LEN
        || pq_signature.len() != PQ_IDENTITY_SIGNATURE_LEN
        || ik_signature.len() != PQ_IK_SIGNATURE_LEN
    {
        return false;
    }
    let (Ok(pq_identity_key), Ok(pq_signature)) = (
        PqIdentityKey::deserialize(sender_pq_identity_key),
        PqSignature::deserialize(pq_signature),
    ) else {
        return false;
    };
    let transcript = pq_kex_auth_transcript(
        sender_jid,
        sender_device_id,
        sender_identity_key,
        &pq_identity_key,
        entries,
    );
    let pq_ok = pq_identity_key.verify(&transcript, &pq_signature);
    let ik_ok = sender_identity_key
        .public_key()
        .verify_signature(&transcript, ik_signature);
    pq_ok & ik_ok
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed_keys() -> (IdentityKey, PqIdentityKey) {
        let mut ik = vec![0x05u8];
        ik.extend_from_slice(&[0x11u8; 32]);
        (
            IdentityKey::decode(&ik).expect("ik"),
            PqIdentityKey::deserialize(&[0x22u8; 2592]).expect("pq ik"),
        )
    }

    fn sample_entries() -> Vec<(String, u32, Vec<u8>)> {
        vec![
            ("Bob@Example.org".to_owned(), 12, vec![0x55u8; 40]),
            ("alice@example.com".to_owned(), 7, vec![0x66u8; 50]),
            ("alice@example.com".to_owned(), 3, vec![0x77u8; 30]),
        ]
    }

    fn transcript_for(entries: &[(String, u32, Vec<u8>)]) -> Vec<u8> {
        let (ik, pq_ik) = fixed_keys();
        let entries: Vec<KexAuthEntry<'_>> = entries
            .iter()
            .map(|(jid, rid, msg)| KexAuthEntry {
                recipient_jid: jid,
                recipient_device_id: *rid,
                prekey_message: msg,
            })
            .collect();
        pq_kex_auth_transcript("Alice@Example.com", 42, &ik, &pq_ik, &entries)
    }

    // Shared with the Java bridge test and documented in proto-XEP §4.9.7.
    #[test]
    fn kex_auth_transcript_known_answer() {
        let hex = Sha3_512::digest(transcript_for(&sample_entries()))
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>();
        assert_eq!(
            hex,
            "281cdeaf99a47eadaa3d51a49ae09c743afc7478b943d9995f7db71ef0533679\
             988c4923ebf5ed8c1e1407c199fe6a0dee8439c6f2385cbb527b924bb9859248"
        );
    }

    #[test]
    fn kex_auth_transcript_is_order_and_case_independent() {
        let mut reordered = sample_entries();
        reordered.reverse();
        for entry in &mut reordered {
            if entry.0 == "Bob@Example.org" {
                entry.0 = "BOB@EXAMPLE.ORG".to_owned();
            }
        }
        assert!(transcript_for(&sample_entries()) == transcript_for(&reordered));
    }

    #[test]
    fn kex_auth_verify_requires_both_signatures() {
        use rand::TryRngCore as _;
        use rand::rngs::OsRng;

        use crate::{IdentityKeyPair, PqIdentityKeyPair};

        let mut rng = OsRng.unwrap_err();
        let identity = IdentityKeyPair::generate(&mut rng);
        let pq = PqIdentityKeyPair::generate(&mut rng);
        let pq_pub = pq.public_key().serialize();
        let raw = sample_entries();
        let entries: Vec<KexAuthEntry<'_>> = raw
            .iter()
            .map(|(jid, rid, msg)| KexAuthEntry {
                recipient_jid: jid,
                recipient_device_id: *rid,
                prekey_message: msg,
            })
            .collect();
        let t = pq_kex_auth_transcript("a@b.c", 9, identity.identity_key(), &pq.public_key(), &entries);
        let pq_sig = pq.sign(&t, &mut rng).unwrap().serialize();
        let ik_sig = identity.private_key().calculate_signature(&t, &mut rng).unwrap();
        let ik = identity.identity_key();
        assert!(pq_kex_auth_verify("a@b.c", 9, ik, &pq_pub, &pq_sig, &ik_sig, &entries));

        // A planted PQ key with its own valid ML-DSA signature, genuine IK signature replayed.
        let other = PqIdentityKeyPair::generate(&mut rng);
        let t2 = pq_kex_auth_transcript("a@b.c", 9, ik, &other.public_key(), &entries);
        let other_sig = other.sign(&t2, &mut rng).unwrap().serialize();
        assert!(!pq_kex_auth_verify(
            "a@b.c", 9, ik, &other.public_key().serialize(), &other_sig, &ik_sig, &entries
        ));
        // IK signature by another key.
        let stranger = IdentityKeyPair::generate(&mut rng);
        let bad_ik_sig = stranger.private_key().calculate_signature(&t, &mut rng).unwrap();
        assert!(!pq_kex_auth_verify("a@b.c", 9, ik, &pq_pub, &pq_sig, &bad_ik_sig, &entries));
        // Wrong lengths and context changes.
        assert!(!pq_kex_auth_verify("a@b.c", 9, ik, &pq_pub[1..], &pq_sig, &ik_sig, &entries));
        assert!(!pq_kex_auth_verify("a@b.c", 9, ik, &pq_pub, &pq_sig[1..], &ik_sig, &entries));
        assert!(!pq_kex_auth_verify("a@b.c", 9, ik, &pq_pub, &pq_sig, &ik_sig[1..], &entries));
        assert!(!pq_kex_auth_verify("a@b.c", 10, ik, &pq_pub, &pq_sig, &ik_sig, &entries));
        assert!(!pq_kex_auth_verify("m@b.c", 9, ik, &pq_pub, &pq_sig, &ik_sig, &entries));
        assert!(!pq_kex_auth_verify("a@b.c", 9, ik, &pq_pub, &pq_sig, &ik_sig, &entries[1..]));
    }

    #[test]
    fn kex_auth_transcript_binds_every_entry() {
        let base = transcript_for(&sample_entries());
        let mut changed_msg = sample_entries();
        changed_msg[1].2[0] ^= 1;
        let mut changed_rid = sample_entries();
        changed_rid[1].1 = 8;
        let mut dropped = sample_entries();
        dropped.pop();
        let mut moved = sample_entries();
        moved[0].0 = "mallory@example.org".to_owned();
        for other in [changed_msg, changed_rid, dropped, moved] {
            assert!(base != transcript_for(&other));
        }
    }
}
