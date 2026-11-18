//
// Copyright 2026 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Post-quantum identity signatures using ML-DSA-87 (FIPS 204).
//!
//! Used by the monocles PQ-OMEMO2 *hybrid identity*: in addition to its classical
//! Curve25519/Ed25519 [`crate::IdentityKey`], a device owns a long-lived ML-DSA-87
//! key pair. Published pre-key bundles are signed by BOTH the classical identity
//! key (as before) and this post-quantum key, so forging a bundle — and therefore
//! actively MITM-ing session establishment — requires breaking ML-DSA-87 as well
//! as Ed25519. The category-5 parameter set matches the ML-KEM-1024 already used
//! for confidentiality.
//!
//! This module deliberately only provides the raw signature primitive and key
//! (de)serialisation; how the signed transcript is constructed, and how the public
//! key is bound to the classical identity and the verified fingerprint, lives in
//! the bundle-processing layer and the application above it.

use libcrux_ml_dsa::ml_dsa_87::{self, MLDSA87Signature, MLDSA87SigningKey, MLDSA87VerificationKey};
use rand::{CryptoRng, Rng};

use crate::{Result, SignalProtocolError};

/// FIPS 204 domain-separation context for monocles PQ-OMEMO2 identity signatures.
/// ML-DSA mixes this `context` string into every signature, so a signature made
/// here can never be re-used or mistaken for one produced in another context.
const PQ_IDENTITY_CONTEXT: &[u8] = b"monocles:omemo2:pqid:v1";

/// Length in bytes of a serialized ML-DSA-87 public (verification) key (2592).
pub const PQ_IDENTITY_PUBLIC_KEY_LEN: usize = MLDSA87VerificationKey::len();
/// Length in bytes of a serialized ML-DSA-87 secret (signing) key (4896).
pub const PQ_IDENTITY_SECRET_KEY_LEN: usize = MLDSA87SigningKey::len();
/// Length in bytes of an ML-DSA-87 signature (4627).
pub const PQ_IDENTITY_SIGNATURE_LEN: usize = MLDSA87Signature::len();

/// A post-quantum identity public key (ML-DSA-87 verification key).
#[derive(Clone)]
pub struct PqIdentityKey {
    verifying_key: MLDSA87VerificationKey,
}

impl PqIdentityKey {
    pub fn deserialize(bytes: &[u8]) -> Result<Self> {
        let arr: [u8; PQ_IDENTITY_PUBLIC_KEY_LEN] = bytes.try_into().map_err(|_| {
            SignalProtocolError::InvalidArgument(format!(
                "ML-DSA-87 public key must be {PQ_IDENTITY_PUBLIC_KEY_LEN} bytes, got {}",
                bytes.len()
            ))
        })?;
        Ok(Self {
            verifying_key: MLDSA87VerificationKey::new(arr),
        })
    }

    pub fn serialize(&self) -> Box<[u8]> {
        self.verifying_key.as_slice().into()
    }

    /// Verify `signature` over `message`, returning `true` iff it is valid for
    /// this key under the PQ-OMEMO2 domain-separation context.
    #[must_use]
    pub fn verify(&self, message: &[u8], signature: &PqSignature) -> bool {
        ml_dsa_87::verify(
            &self.verifying_key,
            message,
            PQ_IDENTITY_CONTEXT,
            &signature.0,
        )
        .is_ok()
    }
}

/// An ML-DSA-87 signature.
#[derive(Clone)]
pub struct PqSignature(MLDSA87Signature);

impl PqSignature {
    pub fn deserialize(bytes: &[u8]) -> Result<Self> {
        let arr: [u8; PQ_IDENTITY_SIGNATURE_LEN] = bytes.try_into().map_err(|_| {
            SignalProtocolError::InvalidArgument(format!(
                "ML-DSA-87 signature must be {PQ_IDENTITY_SIGNATURE_LEN} bytes, got {}",
                bytes.len()
            ))
        })?;
        Ok(Self(MLDSA87Signature::new(arr)))
    }

    pub fn serialize(&self) -> Box<[u8]> {
        self.0.as_slice().into()
    }
}

/// A post-quantum identity key pair (ML-DSA-87).
pub struct PqIdentityKeyPair {
    signing_key: MLDSA87SigningKey,
    verifying_key: MLDSA87VerificationKey,
}

impl PqIdentityKeyPair {
    /// Generate a fresh ML-DSA-87 identity key pair.
    pub fn generate<R: Rng + CryptoRng>(csprng: &mut R) -> Self {
        let randomness: [u8; 32] = csprng.random();
        let kp = ml_dsa_87::generate_key_pair(randomness);
        Self {
            signing_key: kp.signing_key,
            verifying_key: kp.verification_key,
        }
    }

    pub fn public_key(&self) -> PqIdentityKey {
        PqIdentityKey {
            verifying_key: self.verifying_key.clone(),
        }
    }

    /// Sign `message` under the PQ-OMEMO2 domain-separation context.
    pub fn sign<R: Rng + CryptoRng>(&self, message: &[u8], csprng: &mut R) -> Result<PqSignature> {
        let randomness: [u8; 32] = csprng.random();
        let signature = ml_dsa_87::sign(&self.signing_key, message, PQ_IDENTITY_CONTEXT, randomness)
            .map_err(|e| {
                SignalProtocolError::InvalidArgument(format!("ML-DSA-87 signing failed: {e:?}"))
            })?;
        Ok(PqSignature(signature))
    }

    /// Serialize as signing-key bytes followed by verification-key bytes.
    pub fn serialize(&self) -> Box<[u8]> {
        let mut out = Vec::with_capacity(PQ_IDENTITY_SECRET_KEY_LEN + PQ_IDENTITY_PUBLIC_KEY_LEN);
        out.extend_from_slice(self.signing_key.as_slice());
        out.extend_from_slice(self.verifying_key.as_slice());
        out.into_boxed_slice()
    }

    pub fn deserialize(bytes: &[u8]) -> Result<Self> {
        let expected = PQ_IDENTITY_SECRET_KEY_LEN + PQ_IDENTITY_PUBLIC_KEY_LEN;
        if bytes.len() != expected {
            return Err(SignalProtocolError::InvalidArgument(format!(
                "ML-DSA-87 key pair must be {expected} bytes, got {}",
                bytes.len()
            )));
        }
        let (sk, vk) = bytes.split_at(PQ_IDENTITY_SECRET_KEY_LEN);
        let sk_arr: [u8; PQ_IDENTITY_SECRET_KEY_LEN] = sk.try_into().expect("checked length");
        let vk_arr: [u8; PQ_IDENTITY_PUBLIC_KEY_LEN] = vk.try_into().expect("checked length");
        Ok(Self {
            signing_key: MLDSA87SigningKey::new(sk_arr),
            verifying_key: MLDSA87VerificationKey::new(vk_arr),
        })
    }
}

#[cfg(test)]
mod tests {
    use rand::TryRngCore as _;
    use rand::rngs::OsRng;

    use super::*;

    #[test]
    fn sign_verify_roundtrip() {
        let mut rng = OsRng.unwrap_err();
        let pair = PqIdentityKeyPair::generate(&mut rng);
        let public = pair.public_key();
        let message = b"the bundle transcript digest";
        let signature = pair.sign(message, &mut rng).expect("sign");
        assert!(public.verify(message, &signature));
        // Wrong message must fail.
        assert!(!public.verify(b"tampered transcript", &signature));
    }

    #[test]
    fn rejects_other_keys_signature() {
        let mut rng = OsRng.unwrap_err();
        let a = PqIdentityKeyPair::generate(&mut rng);
        let b = PqIdentityKeyPair::generate(&mut rng);
        let message = b"transcript";
        let sig_a = a.sign(message, &mut rng).expect("sign");
        // b's public key must not verify a's signature.
        assert!(!b.public_key().verify(message, &sig_a));
        assert!(a.public_key().verify(message, &sig_a));
    }

    #[test]
    fn serialization_roundtrip() {
        let mut rng = OsRng.unwrap_err();
        let pair = PqIdentityKeyPair::generate(&mut rng);
        let message = b"transcript";
        let signature = pair.sign(message, &mut rng).expect("sign");

        let pair2 = PqIdentityKeyPair::deserialize(&pair.serialize()).expect("kp roundtrip");
        let pub2 = PqIdentityKey::deserialize(&pair.public_key().serialize()).expect("pk roundtrip");
        let sig2 = PqSignature::deserialize(&signature.serialize()).expect("sig roundtrip");

        assert!(pub2.verify(message, &sig2));
        // The re-imported pair signs validly under the original public key.
        let sig_from_pair2 = pair2.sign(message, &mut rng).expect("sign");
        assert!(pair.public_key().verify(message, &sig_from_pair2));
    }

    #[test]
    fn deserialize_rejects_wrong_length() {
        assert!(PqIdentityKey::deserialize(&[0u8; 10]).is_err());
        assert!(PqSignature::deserialize(&[0u8; 10]).is_err());
        assert!(PqIdentityKeyPair::deserialize(&[0u8; 10]).is_err());
    }
}
