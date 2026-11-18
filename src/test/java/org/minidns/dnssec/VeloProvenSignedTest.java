package org.minidns.dnssec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.minidns.DnsWorld.a;
import static org.minidns.DnsWorld.applyZones;
import static org.minidns.DnsWorld.ns;
import static org.minidns.DnsWorld.record;
import static org.minidns.DnsWorld.zone;
import static org.minidns.dnssec.DnssecWorld.addNsec;
import static org.minidns.dnssec.DnssecWorld.ds;
import static org.minidns.dnssec.DnssecWorld.merge;
import static org.minidns.dnssec.DnssecWorld.sign;
import static org.minidns.dnssec.DnssecWorld.signedRootZone;

import java.io.IOException;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.minidns.DnsWorld;
import org.minidns.cache.LruCache;
import org.minidns.constants.DnssecConstants.DigestAlgorithm;
import org.minidns.constants.DnssecConstants.SignatureAlgorithm;
import org.minidns.dnsname.DnsName;
import org.minidns.iterative.ReliableDnsClient.Mode;
import org.minidns.record.A;
import org.minidns.record.DNSKEY;
import org.minidns.record.Data;
import org.minidns.record.Record;
import org.minidns.record.Record.TYPE;

/**
 * upstream: an answer that is not authenticated is bogus if a validated chain of DS RRs proves its zone signed, and
 * only insecure if the chain ends at a validated delegation without DS RRs (or there is no validated data at all, see
 * DnssecClientTest.testUnsignedRoot()). Otherwise stripping the RRSIGs off a TLSA answer turned DANE off.
 */
public class VeloProvenSignedTest {

    private static final SignatureAlgorithm ALGORITHM = SignatureAlgorithm.RSASHA256;
    private static final DigestAlgorithm DIGEST = DigestAlgorithm.SHA1;
    private static final DNSKEY ROOT_KSK;
    private static final PrivateKey ROOT_PRIVATE_KSK;
    private static final DNSKEY ROOT_ZSK;
    private static final PrivateKey ROOT_PRIVATE_ZSK;
    private static final DNSKEY COM_KSK;
    private static final PrivateKey COM_PRIVATE_KSK;
    private static final DNSKEY COM_ZSK;
    private static final PrivateKey COM_PRIVATE_ZSK;

    static {
        DnssecWorld.DnssecData root = DnssecWorld.getDnssecDataFor("");
        ROOT_KSK = root.ksk;
        ROOT_PRIVATE_KSK = root.privateKsk;
        ROOT_ZSK = root.zsk;
        ROOT_PRIVATE_ZSK = root.privateZsk;
        DnssecWorld.DnssecData com = DnssecWorld.getDnssecDataFor("com");
        COM_KSK = com.ksk;
        COM_PRIVATE_KSK = com.privateKsk;
        COM_ZSK = com.zsk;
        COM_PRIVATE_ZSK = com.privateZsk;
    }

    private static DnssecClient client() {
        DnssecClient client = new DnssecClient(new LruCache(0));
        client.addSecureEntryPoint(DnsName.ROOT, ROOT_KSK.getKey());
        client.setMode(Mode.iterativeOnly);
        return client;
    }

    @Test
    public void strippedAnswerInSignedZoneIsBogus() {
        DnssecClient client = client();
        // com is signed and its DNSKEY RRset validates, but the RRSIG of the answer was stripped.
        List<Record<? extends Data>> com = new ArrayList<>(merge(
                sign(COM_KSK, "com", COM_PRIVATE_KSK, ALGORITHM,
                        record("com", COM_KSK),
                        record("com", COM_ZSK))));
        com.add(record("example.com", a("1.1.1.2")));
        applyZones(client,
                signedRootZone(
                        sign(ROOT_KSK, "", ROOT_PRIVATE_KSK, ALGORITHM,
                                record("", ROOT_KSK),
                                record("", ROOT_ZSK)),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("com", ds("com", DIGEST, COM_KSK))),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("com", ns("ns.com"))),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("ns.com", a("1.1.1.1")))
                ), zone("com", "ns.com", "1.1.1.1", com)
        );
        DnssecValidationFailedException e = assertThrows(DnssecValidationFailedException.class,
                () -> client.queryDnssec("example.com", TYPE.A));
        assertTrue(e.getMessage().contains("from a DNSSEC-signed zone"), e.getMessage());
    }

    @Test
    public void unsignedDelegationProvenByParentIsInsecure() throws IOException {
        DnssecClient client = client();
        DnsWorld world = applyZones(client,
                signedRootZone(
                        sign(ROOT_KSK, "", ROOT_PRIVATE_KSK, ALGORITHM,
                                record("", ROOT_KSK),
                                record("", ROOT_ZSK)),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("com", ns("ns.com"))),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("ns.com", a("1.1.1.1")))
                ), zone("com", "ns.com", "1.1.1.1",
                        record("example.com", a("1.1.1.2"))
                )
        );
        // The root proves that com is a delegation (NS) without DS: an unsigned zone.
        addNsec(world, "", "a.root-servers.net", "com", "org", TYPE.NS);

        DnssecQueryResult result = client.queryDnssec("example.com", TYPE.A);
        assertFalse(result.isAuthenticData());
        Record<A> answer = result.dnsQueryResult.response.filterAnswerSectionBy(A.class).get(0);
        assertArrayEquals(new byte[] {1, 1, 1, 2}, answer.payloadData.getIp());
    }
}
