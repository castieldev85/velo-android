//
// Copyright 2020 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

use std::clone::Clone;

use crate::state::{PreKeyId, SignedPreKeyId};
use crate::{
    DeviceId, IdentityKey, KyberPreKeyId, PqIdentityKey, PqSignature, PublicKey, Result,
    SignalProtocolError, kem,
};

/// Domain-separation prefix for the monocles PQ-OMEMO2 hybrid-identity transcript
/// (the message the device's ML-DSA-87 identity key signs / a verifier checks).
///
/// v2 bound the bundle's ML-KEM pre-keys via `kem_binding` (see
/// [`pq_kem_binding`]); v1 bound only the classical identity and the EC signed
/// pre-key, leaving the ML-KEM keys authenticated by Ed25519 alone — which a
/// quantum adversary (the very threat the hybrid identity exists to counter) can
/// forge, so it could substitute the KEM pre-key that the entire post-quantum
/// confidentiality rests on.
///
/// v3 additionally binds the *algorithm identifiers*. v2 committed
/// to the key bytes but not to which algorithm they belonged to, so the wire
/// `type` attributes on `<pq-ik>`/`<kem-spk>`/`<kem-pk>` sat outside the
/// signature. That is harmless while exactly one algorithm exists, but it
/// pre-wires a downgrade hole for the day a second one is registered: an
/// attacker could steer algorithm selection under an otherwise valid signature.
/// Binding the identifiers means a future multi-algorithm verifier reading a v3
/// transcript learns unambiguously which algorithm the signer intended, and any
/// new algorithm necessarily requires a further transcript bump.
///
/// v4 (this build) is signed by BOTH identity keys. Up to v3 only the ML-DSA-87
/// key signed the transcript: the post-quantum key vouched for the classical
/// one, but nothing classical vouched for the post-quantum one. Anyone able to
/// serve a bundle (a malicious server, today, with no quantum computer) could
/// therefore re-publish a device's genuine `IK`, EC and KEM pre-keys and their
/// genuine Ed25519 signatures next to a `PQ-IK` of its own and a valid
/// `<pq-sig>` under it. The verifier would pin that foreign `PQ-IK`, and once a
/// quantum computer exists the attacker could forge Ed25519 and substitute
/// bundles that match the pin — defeating the hybrid identity for every contact
/// that was not verified out of band. v4 adds an XEdDSA signature by `IK` over
/// the same transcript ([`pq_bundle_verify`]), so planting a `PQ-IK` requires
/// the device's classical private key.
///
/// Each label change makes signatures from the previous version fail
/// verification here. All builds are pre-release, so no compatibility shim is
/// provided; Android, desktop and this crate deploy in lockstep.
const PQ_BUNDLE_TRANSCRIPT_LABEL: &[u8] = b"monocles:omemo2:pq-bundle:v4";

/// Length of the classical (XEdDSA) identity-key signature over the transcript.
pub const PQ_IK_SIGNATURE_LEN: usize = 64;

/// Domain-separation prefix for the ML-KEM binding digest (see [`pq_kem_binding`]).
///
/// v2 widened the digest from SHA-256 to SHA3-512 and bound the KEM algorithm
/// identifier; see [`pq_kem_binding`] for why the width matters.
const PQ_KEM_BINDING_LABEL: &[u8] = b"monocles:omemo2:kem-binding:v2";

/// The post-quantum identity signature algorithm this profile version fixes.
/// Bound into the transcript so the signature commits to it (see
/// [`PQ_BUNDLE_TRANSCRIPT_LABEL`]).
const PQ_IDENTITY_ALGORITHM: &[u8] = b"ML-DSA-87";

/// The KEM algorithm this profile version fixes, bound into [`pq_kem_binding`].
const PQ_KEM_ALGORITHM: &[u8] = b"ML-KEM-1024";

/// Size of the ML-KEM binding digest and its inner manifest hash.
pub const PQ_KEM_BINDING_LEN: usize = 64;

fn push_len_prefixed(out: &mut Vec<u8>, bytes: &[u8]) {
    out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(bytes);
}

/// Compute the 64-byte ML-KEM binding digest that ties a bundle's post-quantum
/// signature to *all* of its ML-KEM pre-keys.
///
/// - `kem_signed_pre_key_id` / `kem_signed_pre_key_public`: the long-lived
///   ("last-resort") signed KEM pre-key (`<kem-spk>`), bound directly.
/// - `one_time`: every published one-time KEM pre-key (`<kem-pk>`), in bundle
///   document order, as `(id, serialized_public_key)`. These are folded into a
///   manifest hash so a single value commits to the whole set regardless of its
///   size; an empty set yields an all-zero manifest.
///
/// A verifier recomputes this from the bundle it fetched. If an adversary
/// substituted the last-resort key or *any* one-time key, the recomputed digest
/// differs from the one the ML-DSA-87 signature covers, so verification fails —
/// no matter which KEM pre-key the initiator later selects, because the selected
/// key necessarily came from this same authenticated set.
///
/// SHA3-512, not SHA-256: an ML-DSA-87 signature offers 256-bit (NIST category
/// 5) security, but it only ever commits to *this digest*, so a 32-byte SHA-256
/// value capped the real strength of "the signature covers every ML-KEM pre-key"
/// at the 128-bit birthday bound — the weak link in precisely the
/// harvest-and-forge threat model the hybrid identity exists to counter. Keccak
/// also matches what ML-KEM and ML-DSA use internally.
pub fn pq_kem_binding(
    kem_signed_pre_key_id: KyberPreKeyId,
    kem_signed_pre_key_public: &[u8],
    one_time: &[(KyberPreKeyId, &[u8])],
) -> [u8; PQ_KEM_BINDING_LEN] {
    use sha3::{Digest, Sha3_512};
    let manifest: [u8; PQ_KEM_BINDING_LEN] = if one_time.is_empty() {
        [0u8; PQ_KEM_BINDING_LEN]
    } else {
        let mut m = Sha3_512::new();
        for (id, pk) in one_time {
            m.update(u32::from(*id).to_be_bytes());
            m.update((pk.len() as u32).to_be_bytes());
            m.update(pk);
        }
        m.finalize().into()
    };
    let mut h = Sha3_512::new();
    h.update(PQ_KEM_BINDING_LABEL);
    h.update((PQ_KEM_ALGORITHM.len() as u32).to_be_bytes());
    h.update(PQ_KEM_ALGORITHM);
    h.update(u32::from(kem_signed_pre_key_id).to_be_bytes());
    h.update((kem_signed_pre_key_public.len() as u32).to_be_bytes());
    h.update(kem_signed_pre_key_public);
    h.update((manifest.len() as u32).to_be_bytes());
    h.update(manifest);
    h.finalize().into()
}

/// Build the canonical transcript bound by a bundle's post-quantum identity
/// signature. It commits the post-quantum identity key to the device's classical
/// [`IdentityKey`], its EC signed pre-key, the fixed algorithm identifiers, and —
/// via `kem_binding` ([`pq_kem_binding`]) — all of the bundle's ML-KEM pre-keys,
/// so that an attacker who cannot forge an ML-DSA-87 signature can substitute
/// none of them. Fields are length-prefixed and ordered to be unambiguous.
pub fn pq_bundle_transcript(
    identity_key: &IdentityKey,
    pq_identity_key: &PqIdentityKey,
    signed_pre_key_id: SignedPreKeyId,
    signed_pre_key_public: &PublicKey,
    kem_binding: &[u8],
) -> Vec<u8> {
    let ik = identity_key.serialize();
    let pq_ik = pq_identity_key.serialize();
    let spk = signed_pre_key_public.serialize();
    let mut out = Vec::with_capacity(
        PQ_BUNDLE_TRANSCRIPT_LABEL.len()
            + PQ_IDENTITY_ALGORITHM.len()
            + ik.len()
            + pq_ik.len()
            + spk.len()
            + kem_binding.len()
            + 44,
    );
    out.extend_from_slice(PQ_BUNDLE_TRANSCRIPT_LABEL);
    push_len_prefixed(&mut out, PQ_IDENTITY_ALGORITHM);
    push_len_prefixed(&mut out, &ik);
    push_len_prefixed(&mut out, &pq_ik);
    out.extend_from_slice(&u32::from(signed_pre_key_id).to_be_bytes());
    push_len_prefixed(&mut out, &spk);
    push_len_prefixed(&mut out, kem_binding);
    out
}

/// Verify both signatures a v4 bundle transcript carries: the ML-DSA-87
/// signature by `pq_identity_key` and the XEdDSA signature by the classical
/// `identity_key`, over the same transcript.
///
/// This is the single verification routine for a hybrid identity: session
/// establishment ([`crate::process_prekey_bundle`]), and the applications'
/// out-of-band pin reconciliation, both go through it so that no path can check
/// one signature and forget the other. `kem_binding` must be the digest the
/// verifier recomputed from the bundle it fetched ([`pq_kem_binding`]); a value
/// of the wrong length is refused outright.
pub fn pq_bundle_verify(
    identity_key: &IdentityKey,
    pq_identity_key: &PqIdentityKey,
    pq_signature: &PqSignature,
    ik_signature: &[u8],
    signed_pre_key_id: SignedPreKeyId,
    signed_pre_key_public: &PublicKey,
    kem_binding: &[u8],
) -> bool {
    if kem_binding.len() != PQ_KEM_BINDING_LEN || ik_signature.len() != PQ_IK_SIGNATURE_LEN {
        return false;
    }
    let transcript = pq_bundle_transcript(
        identity_key,
        pq_identity_key,
        signed_pre_key_id,
        signed_pre_key_public,
        kem_binding,
    );
    // Evaluate both, not short-circuit: no observable difference between "the
    // classical half failed" and "the post-quantum half failed".
    let pq_ok = pq_identity_key.verify(&transcript, pq_signature);
    let ik_ok = identity_key
        .public_key()
        .verify_signature(&transcript, ik_signature);
    pq_ok & ik_ok
}

#[derive(Clone)]
struct SignedPreKey {
    id: SignedPreKeyId,
    public_key: PublicKey,
    signature: Vec<u8>,
}

impl SignedPreKey {
    fn new(id: SignedPreKeyId, public_key: PublicKey, signature: Vec<u8>) -> Self {
        Self {
            id,
            public_key,
            signature,
        }
    }
}

#[derive(Clone)]
struct KyberPreKey {
    id: KyberPreKeyId,
    public_key: kem::PublicKey,
    signature: Vec<u8>,
}

impl KyberPreKey {
    fn new(id: KyberPreKeyId, public_key: kem::PublicKey, signature: Vec<u8>) -> Self {
        Self {
            id,
            public_key,
            signature,
        }
    }
}

// Represents the raw contents of the pre-key bundle without any notion of required/optional
// fields.
// Can be used as a "builder" for PreKeyBundle, in which case all the validation will happen in
// PreKeyBundle::new.
pub struct PreKeyBundleContent {
    pub registration_id: Option<u32>,
    pub device_id: Option<DeviceId>,
    pub pre_key_id: Option<PreKeyId>,
    pub pre_key_public: Option<PublicKey>,
    pub signed_pre_key_id: Option<SignedPreKeyId>,
    pub signed_pre_key_public: Option<PublicKey>,
    pub signed_pre_key_signature: Option<Vec<u8>>,
    pub identity_key: Option<IdentityKey>,
    pub kyber_pre_key_id: Option<KyberPreKeyId>,
    pub kyber_pre_key_public: Option<kem::PublicKey>,
    pub kyber_pre_key_signature: Option<Vec<u8>>,
    // Optional monocles PQ-OMEMO2 hybrid identity (ML-DSA-87): the post-quantum
    // identity public key, its signature over pq_bundle_transcript(...), the
    // classical identity key's XEdDSA signature over the same transcript, plus
    // the ML-KEM binding digest the transcript covers.
    pub pq_identity_key: Option<PqIdentityKey>,
    pub pq_signature: Option<PqSignature>,
    pub pq_ik_signature: Option<Vec<u8>>,
    pub pq_kem_binding: Option<Vec<u8>>,
}

impl From<PreKeyBundle> for PreKeyBundleContent {
    fn from(bundle: PreKeyBundle) -> Self {
        let (pq_identity_key, pq_signature, pq_ik_signature) = match bundle.pq_identity {
            Some((key, sig, ik_sig)) => (Some(key), Some(sig), Some(ik_sig)),
            None => (None, None, None),
        };
        let pq_kem_binding = bundle.pq_kem_binding;
        Self {
            registration_id: Some(bundle.registration_id),
            device_id: Some(bundle.device_id),
            pre_key_id: bundle.pre_key_id,
            pre_key_public: bundle.pre_key_public,
            signed_pre_key_id: Some(bundle.ec_signed_pre_key.id),
            signed_pre_key_public: Some(bundle.ec_signed_pre_key.public_key),
            signed_pre_key_signature: Some(bundle.ec_signed_pre_key.signature),
            identity_key: Some(bundle.identity_key),
            kyber_pre_key_id: Some(bundle.kyber_pre_key.id),
            kyber_pre_key_public: Some(bundle.kyber_pre_key.public_key),
            kyber_pre_key_signature: Some(bundle.kyber_pre_key.signature),
            pq_identity_key,
            pq_signature,
            pq_ik_signature,
            pq_kem_binding,
        }
    }
}

impl TryFrom<PreKeyBundleContent> for PreKeyBundle {
    type Error = SignalProtocolError;

    fn try_from(content: PreKeyBundleContent) -> Result<Self> {
        let bundle = PreKeyBundle::new(
            content.registration_id.ok_or_else(|| {
                SignalProtocolError::InvalidArgument("registration_id is required".to_string())
            })?,
            content.device_id.ok_or_else(|| {
                SignalProtocolError::InvalidArgument("device_id is required".to_string())
            })?,
            content
                .pre_key_id
                .and_then(|id| content.pre_key_public.map(|public| (id, public))),
            content.signed_pre_key_id.ok_or_else(|| {
                SignalProtocolError::InvalidArgument("signed_pre_key_id is required".to_string())
            })?,
            content.signed_pre_key_public.ok_or_else(|| {
                SignalProtocolError::InvalidArgument(
                    "signed_pre_key_public is required".to_string(),
                )
            })?,
            content.signed_pre_key_signature.ok_or_else(|| {
                SignalProtocolError::InvalidArgument(
                    "signed_pre_key_signature is required".to_string(),
                )
            })?,
            content.kyber_pre_key_id.ok_or_else(|| {
                SignalProtocolError::InvalidArgument("kyber_pre_key_id is required".to_string())
            })?,
            content.kyber_pre_key_public.ok_or_else(|| {
                SignalProtocolError::InvalidArgument("kyber_pre_key_public is required".to_string())
            })?,
            content.kyber_pre_key_signature.ok_or_else(|| {
                SignalProtocolError::InvalidArgument(
                    "kyber_pre_key_signature is required".to_string(),
                )
            })?,
            content.identity_key.ok_or_else(|| {
                SignalProtocolError::InvalidArgument("identity_key is required".to_string())
            })?,
        )?;
        // Re-attach the optional PQ identity if all parts are present. A partial
        // PQ identity is malformed and refused rather than silently dropped:
        // dropping it would hand the caller a bundle that looks classical-only.
        match (
            content.pq_identity_key,
            content.pq_signature,
            content.pq_ik_signature,
            content.pq_kem_binding,
        ) {
            (Some(key), Some(sig), Some(ik_sig), Some(binding)) => {
                Ok(bundle.with_pq_identity(key, sig, ik_sig, binding))
            }
            (None, None, None, None) => Ok(bundle),
            _ => Err(SignalProtocolError::InvalidArgument(
                "incomplete PQ identity".to_string(),
            )),
        }
    }
}

#[derive(Clone)]
pub struct PreKeyBundle {
    registration_id: u32,
    device_id: DeviceId,
    pre_key_id: Option<PreKeyId>,
    pre_key_public: Option<PublicKey>,
    ec_signed_pre_key: SignedPreKey,
    identity_key: IdentityKey,
    kyber_pre_key: KyberPreKey,
    // Optional monocles PQ-OMEMO2 hybrid identity: (ML-DSA-87 public key, its
    // signature over `pq_bundle_transcript`, the classical identity key's XEdDSA
    // signature over the same transcript). When present, session establishment
    // additionally requires BOTH signatures to verify (see `process_prekey_bundle`).
    pq_identity: Option<(PqIdentityKey, PqSignature, Vec<u8>)>,
    // The 64-byte ML-KEM binding digest (`pq_kem_binding`) the verifier must feed
    // into the v4 transcript. Set together with `pq_identity`; required to be
    // present whenever `pq_identity` is (enforced in `process_prekey_bundle`).
    pq_kem_binding: Option<Vec<u8>>,
}

impl PreKeyBundle {
    #[expect(clippy::too_many_arguments)]
    pub fn new(
        registration_id: u32,
        device_id: DeviceId,
        pre_key: Option<(PreKeyId, PublicKey)>,
        signed_pre_key_id: SignedPreKeyId,
        signed_pre_key_public: PublicKey,
        signed_pre_key_signature: Vec<u8>,
        kyber_pre_key_id: KyberPreKeyId,
        kyber_pre_key_public: kem::PublicKey,
        kyber_pre_key_signature: Vec<u8>,
        identity_key: IdentityKey,
    ) -> Result<Self> {
        let (pre_key_id, pre_key_public) = match pre_key {
            None => (None, None),
            Some((id, key)) => (Some(id), Some(key)),
        };

        let ec_signed_pre_key = SignedPreKey::new(
            signed_pre_key_id,
            signed_pre_key_public,
            signed_pre_key_signature,
        );

        let kyber_pre_key = KyberPreKey::new(
            kyber_pre_key_id,
            kyber_pre_key_public,
            kyber_pre_key_signature,
        );

        Ok(Self {
            registration_id,
            device_id,
            pre_key_id,
            pre_key_public,
            ec_signed_pre_key,
            identity_key,
            kyber_pre_key,
            pq_identity: None,
            pq_kem_binding: None,
        })
    }

    /// Attach a monocles PQ-OMEMO2 hybrid identity (ML-DSA-87 public key, its
    /// signature over [`pq_bundle_transcript`], and the classical identity key's
    /// XEdDSA signature over the same transcript) to this bundle, together with
    /// the 64-byte ML-KEM binding digest ([`pq_kem_binding`]) recomputed from
    /// the bundle the verifier fetched. When present,
    /// [`crate::process_prekey_bundle`] additionally verifies both signatures
    /// over the v4 transcript ([`pq_bundle_verify`]) and aborts on failure.
    pub fn with_pq_identity(
        mut self,
        pq_identity_key: PqIdentityKey,
        pq_signature: PqSignature,
        ik_signature: Vec<u8>,
        kem_binding: Vec<u8>,
    ) -> Self {
        self.pq_identity = Some((pq_identity_key, pq_signature, ik_signature));
        self.pq_kem_binding = Some(kem_binding);
        self
    }

    /// The bundle's post-quantum identity key and signature, if present.
    pub fn pq_identity(&self) -> Option<(&PqIdentityKey, &PqSignature)> {
        self.pq_identity.as_ref().map(|(key, sig, _)| (key, sig))
    }

    /// The classical identity key's signature over the transcript, if a PQ
    /// identity is attached.
    pub fn pq_ik_signature(&self) -> Option<&[u8]> {
        self.pq_identity.as_ref().map(|(_, _, ik_sig)| ik_sig.as_slice())
    }

    /// The bundle's ML-KEM binding digest, if a PQ identity is attached.
    pub fn pq_kem_binding(&self) -> Option<&[u8]> {
        self.pq_kem_binding.as_deref()
    }

    /// The serialized ML-DSA-87 identity public key, if present (for bridging).
    pub fn pq_identity_key(&self) -> Result<Option<Box<[u8]>>> {
        Ok(self.pq_identity.as_ref().map(|(key, _, _)| key.serialize()))
    }

    /// The serialized ML-DSA-87 bundle signature, if present (for bridging).
    pub fn pq_signature(&self) -> Result<Option<Box<[u8]>>> {
        Ok(self.pq_identity.as_ref().map(|(_, sig, _)| sig.serialize()))
    }

    pub fn registration_id(&self) -> Result<u32> {
        Ok(self.registration_id)
    }

    pub fn device_id(&self) -> Result<DeviceId> {
        Ok(self.device_id)
    }

    pub fn pre_key_id(&self) -> Result<Option<PreKeyId>> {
        Ok(self.pre_key_id)
    }

    pub fn pre_key_public(&self) -> Result<Option<PublicKey>> {
        Ok(self.pre_key_public)
    }

    pub fn signed_pre_key_id(&self) -> Result<SignedPreKeyId> {
        Ok(self.ec_signed_pre_key.id)
    }

    pub fn signed_pre_key_public(&self) -> Result<PublicKey> {
        Ok(self.ec_signed_pre_key.public_key)
    }

    pub fn signed_pre_key_signature(&self) -> Result<&[u8]> {
        Ok(self.ec_signed_pre_key.signature.as_ref())
    }

    pub fn identity_key(&self) -> Result<&IdentityKey> {
        Ok(&self.identity_key)
    }

    pub fn kyber_pre_key_id(&self) -> Result<KyberPreKeyId> {
        Ok(self.kyber_pre_key.id)
    }

    pub fn kyber_pre_key_public(&self) -> Result<&kem::PublicKey> {
        Ok(&self.kyber_pre_key.public_key)
    }

    pub fn kyber_pre_key_signature(&self) -> Result<&[u8]> {
        Ok(&self.kyber_pre_key.signature)
    }

    pub fn modify<F>(self, modify: F) -> Result<Self>
    where
        F: FnOnce(&mut PreKeyBundleContent),
    {
        let mut content = self.into();
        modify(&mut content);
        content.try_into()
    }
}

#[cfg(test)]
mod tests {
    use rand::TryRngCore as _;
    use rand::rngs::OsRng;

    use super::*;
    use crate::{IdentityKeyPair, KeyPair, PqIdentityKeyPair};

    fn sample_bundle<R: rand::Rng + rand::CryptoRng>(
        identity: &IdentityKeyPair,
        signed_pre_key_pair: &KeyPair,
        signed_pre_key_id: u32,
        rng: &mut R,
    ) -> PreKeyBundle {
        let kyber = kem::KeyPair::generate(kem::KeyType::Kyber1024, rng);
        let device_id: DeviceId = rng.random();
        PreKeyBundle::new(
            1,
            device_id,
            None,
            signed_pre_key_id.into(),
            signed_pre_key_pair.public_key,
            vec![0u8; 64], // EC spk signature: unused by the PQ path under test
            1u32.into(),
            kyber.public_key.clone(),
            vec![0u8; 64], // kyber signature: unused by the PQ path under test
            *identity.identity_key(),
        )
        .expect("bundle")
    }

    // A deterministic sample ML-KEM binding for the identity/spk-focused tests.
    fn sample_binding() -> Vec<u8> {
        pq_kem_binding(1u32.into(), &[7u8; 32], &[]).to_vec()
    }

    // What a publishing device does: sign the v4 transcript with BOTH keys.
    fn sign_both<R: rand::Rng + rand::CryptoRng>(
        identity: &IdentityKeyPair,
        pq: &PqIdentityKeyPair,
        spk: &KeyPair,
        binding: &[u8],
        rng: &mut R,
    ) -> (PqSignature, Vec<u8>) {
        let transcript = pq_bundle_transcript(
            identity.identity_key(),
            &pq.public_key(),
            7u32.into(),
            &spk.public_key,
            binding,
        );
        let pq_sig = pq.sign(&transcript, rng).expect("sign");
        let ik_sig = identity
            .private_key()
            .calculate_signature(&transcript, rng)
            .expect("ik sign")
            .to_vec();
        (pq_sig, ik_sig)
    }

    // Mirrors the verification performed in `process_prekey_bundle`.
    fn pq_verify(bundle: &PreKeyBundle) -> bool {
        match (
            bundle.pq_identity(),
            bundle.pq_ik_signature(),
            bundle.pq_kem_binding(),
        ) {
            (Some((pq_key, pq_sig)), Some(ik_sig), Some(binding)) => pq_bundle_verify(
                bundle.identity_key().unwrap(),
                pq_key,
                pq_sig,
                ik_sig,
                bundle.signed_pre_key_id().unwrap(),
                &bundle.signed_pre_key_public().unwrap(),
                binding,
            ),
            _ => false,
        }
    }

    #[test]
    fn pq_identity_bundle_roundtrip_verifies() {
        let mut rng = OsRng.unwrap_err();
        let identity = IdentityKeyPair::generate(&mut rng);
        let spk = KeyPair::generate(&mut rng);
        let pq = PqIdentityKeyPair::generate(&mut rng);
        let binding = sample_binding();
        let (pq_sig, ik_sig) = sign_both(&identity, &pq, &spk, &binding, &mut rng);

        let plain = sample_bundle(&identity, &spk, 7, &mut rng);
        assert!(plain.pq_identity().is_none());
        let bundle = plain.with_pq_identity(pq.public_key(), pq_sig, ik_sig, binding);
        assert!(bundle.pq_identity().is_some());
        assert!(pq_verify(&bundle), "valid PQ identity bundle must verify");
    }

    #[test]
    fn pq_identity_rejects_substituted_signed_pre_key() {
        let mut rng = OsRng.unwrap_err();
        let identity = IdentityKeyPair::generate(&mut rng);
        let spk = KeyPair::generate(&mut rng);
        let pq = PqIdentityKeyPair::generate(&mut rng);
        let binding = sample_binding();
        // Sign a transcript for the genuine signed pre-key...
        let (pq_sig, ik_sig) = sign_both(&identity, &pq, &spk, &binding, &mut rng);

        // ...but ship a bundle whose signed pre-key was swapped by an attacker.
        let attacker_spk = KeyPair::generate(&mut rng);
        let tampered = sample_bundle(&identity, &attacker_spk, 7, &mut rng)
            .with_pq_identity(pq.public_key(), pq_sig, ik_sig, binding);
        assert!(
            !pq_verify(&tampered),
            "swapped signed pre-key must fail PQ verification"
        );
    }

    #[test]
    fn pq_identity_rejects_substituted_kem_binding() {
        let mut rng = OsRng.unwrap_err();
        let identity = IdentityKeyPair::generate(&mut rng);
        let spk = KeyPair::generate(&mut rng);
        let pq = PqIdentityKeyPair::generate(&mut rng);
        // Sign over the genuine KEM binding...
        let binding = sample_binding();
        let (pq_sig, ik_sig) = sign_both(&identity, &pq, &spk, &binding, &mut rng);

        // ...but attach a bundle whose KEM keys (hence binding) were swapped: the
        // harvest-and-forge substitution the KEM binding exists to catch.
        let attacker_binding = pq_kem_binding(1u32.into(), &[42u8; 32], &[]).to_vec();
        assert_ne!(binding, attacker_binding);
        let tampered = sample_bundle(&identity, &spk, 7, &mut rng).with_pq_identity(
            pq.public_key(),
            pq_sig,
            ik_sig,
            attacker_binding,
        );
        assert!(
            !pq_verify(&tampered),
            "swapped ML-KEM binding must fail PQ verification"
        );
    }

    // The attack v4 exists to stop: re-publish a device's genuine classical
    // material next to a PQ identity the attacker controls. The attacker can make
    // a valid ML-DSA signature with its own key, but cannot make IK sign the new
    // transcript — replaying the device's genuine IK signature does not help,
    // because that one covers the genuine PQ-IK.
    #[test]
    fn pq_identity_rejects_planted_pq_key() {
        let mut rng = OsRng.unwrap_err();
        let identity = IdentityKeyPair::generate(&mut rng);
        let spk = KeyPair::generate(&mut rng);
        let genuine_pq = PqIdentityKeyPair::generate(&mut rng);
        let binding = sample_binding();
        let (_, genuine_ik_sig) = sign_both(&identity, &genuine_pq, &spk, &binding, &mut rng);

        let attacker_pq = PqIdentityKeyPair::generate(&mut rng);
        let planted_transcript = pq_bundle_transcript(
            identity.identity_key(),
            &attacker_pq.public_key(),
            7u32.into(),
            &spk.public_key,
            &binding,
        );
        let attacker_pq_sig = attacker_pq.sign(&planted_transcript, &mut rng).expect("sign");
        let planted = sample_bundle(&identity, &spk, 7, &mut rng).with_pq_identity(
            attacker_pq.public_key(),
            attacker_pq_sig,
            genuine_ik_sig,
            binding,
        );
        assert!(!pq_verify(&planted), "a planted PQ-IK must not verify");
    }

    #[test]
    fn pq_identity_rejects_ik_signature_by_another_key() {
        let mut rng = OsRng.unwrap_err();
        let identity = IdentityKeyPair::generate(&mut rng);
        let other = IdentityKeyPair::generate(&mut rng);
        let spk = KeyPair::generate(&mut rng);
        let pq = PqIdentityKeyPair::generate(&mut rng);
        let binding = sample_binding();
        let (pq_sig, _) = sign_both(&identity, &pq, &spk, &binding, &mut rng);
        let transcript = pq_bundle_transcript(
            identity.identity_key(),
            &pq.public_key(),
            7u32.into(),
            &spk.public_key,
            &binding,
        );
        let wrong_ik_sig = other
            .private_key()
            .calculate_signature(&transcript, &mut rng)
            .expect("sign")
            .to_vec();
        let bundle = sample_bundle(&identity, &spk, 7, &mut rng).with_pq_identity(
            pq.public_key(),
            pq_sig,
            wrong_ik_sig,
            binding,
        );
        assert!(!pq_verify(&bundle));
    }

    #[test]
    fn pq_identity_rejects_malformed_ik_signature() {
        let mut rng = OsRng.unwrap_err();
        let identity = IdentityKeyPair::generate(&mut rng);
        let spk = KeyPair::generate(&mut rng);
        let pq = PqIdentityKeyPair::generate(&mut rng);
        let binding = sample_binding();
        let (pq_sig, ik_sig) = sign_both(&identity, &pq, &spk, &binding, &mut rng);
        for bad in [Vec::new(), vec![0u8; 64], ik_sig[..63].to_vec(), {
            let mut v = ik_sig.clone();
            v.push(0);
            v
        }] {
            let bundle = sample_bundle(&identity, &spk, 7, &mut rng).with_pq_identity(
                pq.public_key(),
                pq_sig.clone(),
                bad,
                binding.clone(),
            );
            assert!(!pq_verify(&bundle));
        }
    }

    // Known-answer test locking this digest to the Java `PqBundle.kemBinding`
    // (and the desktop Rust copy) and to the vector documented in the proto-XEP
    // §4.9.1. If this value changes, ALL three implementations must change in
    // lockstep or Android/desktop bundle authentication breaks.
    #[test]
    fn pq_kem_binding_known_answer() {
        let v = pq_kem_binding(
            1u32.into(),
            &[0xAAu8; 4],
            &[(2u32.into(), &[0xBBu8; 3][..]), (3u32.into(), &[0xCCu8; 5][..])],
        );
        let hex = v.iter().map(|b| format!("{b:02x}")).collect::<String>();
        assert_eq!(
            hex,
            "7f93e76279a9e5c4dffe34e20c809fb2d1ef8dafaebf26742725aa4a1eba4d0f\
             fac892689509fa655a04fb8a6d6c0beff91f757ab714b6cb8c8cd3303c8c98f2"
        );
    }

    // Known answer for the v4 transcript layout itself (SHA3-512 of its bytes),
    // documented in proto-XEP §4.9.1: IK = 0x05||0x11*32, PQ-IK = 0x22*2592,
    // spk id 7, SPK = 0x05||0x33*32, KEM-BINDING = 0x44*64.
    #[test]
    fn pq_bundle_transcript_known_answer() {
        use sha3::{Digest, Sha3_512};
        let mut ik = vec![0x05u8];
        ik.extend_from_slice(&[0x11u8; 32]);
        let mut spk = vec![0x05u8];
        spk.extend_from_slice(&[0x33u8; 32]);
        let t = pq_bundle_transcript(
            &IdentityKey::decode(&ik).expect("ik"),
            &PqIdentityKey::deserialize(&[0x22u8; 2592]).expect("pq ik"),
            7u32.into(),
            &PublicKey::deserialize(&spk).expect("spk"),
            &[0x44u8; 64],
        );
        let hex = Sha3_512::digest(&t)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>();
        assert_eq!(
            hex,
            "51006cbeb032541274a0222b2b2e6ef68250803ccd9f8921ffcd8a93b4be7028\
             dec4c46f0dfb1d50a3add5593871157dd0c1ce958d5aa35f320ffbdafcfd621a"
        );
    }

    #[test]
    fn pq_kem_binding_reflects_onetime_set() {
        // Changing any one-time key, its id, or the set's membership changes the digest.
        let base = pq_kem_binding(1u32.into(), &[7u8; 32], &[(10u32.into(), &[1u8; 4][..])]);
        let changed_key = pq_kem_binding(1u32.into(), &[7u8; 32], &[(10u32.into(), &[2u8; 4][..])]);
        let changed_id = pq_kem_binding(1u32.into(), &[7u8; 32], &[(11u32.into(), &[1u8; 4][..])]);
        let extra = pq_kem_binding(
            1u32.into(),
            &[7u8; 32],
            &[(10u32.into(), &[1u8; 4][..]), (11u32.into(), &[3u8; 4][..])],
        );
        let empty = pq_kem_binding(1u32.into(), &[7u8; 32], &[]);
        assert_ne!(base, changed_key);
        assert_ne!(base, changed_id);
        assert_ne!(base, extra);
        assert_ne!(base, empty);
        // Empty set → all-zero manifest, but still a well-defined digest.
        assert_eq!(empty, pq_kem_binding(1u32.into(), &[7u8; 32], &[]));
    }

    #[test]
    fn pq_identity_survives_content_roundtrip() {
        let mut rng = OsRng.unwrap_err();
        let identity = IdentityKeyPair::generate(&mut rng);
        let spk = KeyPair::generate(&mut rng);
        let pq = PqIdentityKeyPair::generate(&mut rng);
        let binding = sample_binding();
        let (pq_sig, ik_sig) = sign_both(&identity, &pq, &spk, &binding, &mut rng);
        let bundle = sample_bundle(&identity, &spk, 7, &mut rng)
            .with_pq_identity(pq.public_key(), pq_sig, ik_sig, binding);

        // PreKeyBundle -> PreKeyBundleContent -> PreKeyBundle (the `modify` path)
        // must preserve the PQ identity, both signatures and its KEM binding.
        let round = bundle.clone().modify(|_| {}).expect("roundtrip");
        assert!(round.pq_identity().is_some());
        assert!(round.pq_ik_signature().is_some());
        assert!(round.pq_kem_binding().is_some());
        assert!(pq_verify(&round));

        // A partial PQ identity is refused, never silently dropped.
        let partial = bundle.modify(|c| c.pq_ik_signature = None);
        assert!(partial.is_err());
    }
}
