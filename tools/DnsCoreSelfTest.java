package com.local.xiaomiadshield;

import java.io.ByteArrayOutputStream;

public final class DnsCoreSelfTest {
    public static void main(String[] args) throws Exception {
        byte[] dns = query("pagead2.googlesyndication.com");
        byte[] ip = ipv4Udp(dns, 42000);
        DnsPacket.Query parsed = DnsPacket.parseIpv4Udp(ip, ip.length);
        check(parsed != null, "DNS query should parse");
        check("pagead2.googlesyndication.com".equals(parsed.domain), "domain mismatch");
        check(parsed.sourcePort == 42000, "source port mismatch");
        check(DomainBlocker.isBlocked(parsed.domain), "known ad domain should block");
        check(!DomainBlocker.isBlocked("example.com"), "normal domain should pass");
        check(DnsPacket.responseReferencesBlockedDomain(cnameResponse("example.com", "pagead2.googlesyndication.com")),
                "blocked CNAME target should block");
        check(!DnsPacket.responseReferencesBlockedDomain(cnameResponse("example.com", "cdn.example.com")),
                "normal CNAME target should pass");
        byte[] blocked = DnsPacket.blockedResponse(parsed.dnsPayload);
        check((blocked[3] & 0x0F) == 3, "blocked response must be NXDOMAIN");
        byte[] response = DnsPacket.wrapIpv4Udp(blocked, parsed);
        check(response.length == 28 + blocked.length, "wrapped packet length mismatch");
        System.out.println("DnsCoreSelfTest passed");
    }

    private static byte[] query(String domain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        for (String label : domain.split("\\.")) {
            out.write(label.length()); out.write(label.getBytes("US-ASCII"));
        }
        out.write(0); out.write(new byte[]{0, 1, 0, 1});
        return out.toByteArray();
    }

    private static byte[] ipv4Udp(byte[] dns, int sourcePort) {
        byte[] p = new byte[28 + dns.length];
        p[0] = 0x45; p[2] = (byte) (p.length >> 8); p[3] = (byte) p.length; p[9] = 17;
        p[12] = 10; p[13] = 7; p[14] = 0; p[15] = 1;
        p[16] = 10; p[17] = 7; p[18] = 0; p[19] = 2;
        p[20] = (byte) (sourcePort >> 8); p[21] = (byte) sourcePort; p[23] = 53;
        int udpLen = 8 + dns.length; p[24] = (byte) (udpLen >> 8); p[25] = (byte) udpLen;
        System.arraycopy(dns, 0, p, 28, dns.length); return p;
    }

    private static byte[] cnameResponse(String queryDomain, String target) throws Exception {
        byte[] q = query(queryDomain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        q[2] = (byte) 0x81; q[3] = (byte) 0x80; q[6] = 0; q[7] = 1;
        out.write(q);
        out.write(new byte[]{(byte) 0xC0, 0x0C, 0, 5, 0, 1, 0, 0, 0, 60});
        ByteArrayOutputStream name = new ByteArrayOutputStream();
        for (String label : target.split("\\.")) {
            name.write(label.length()); name.write(label.getBytes("US-ASCII"));
        }
        name.write(0);
        byte[] encoded = name.toByteArray();
        out.write((encoded.length >> 8) & 0xFF); out.write(encoded.length & 0xFF); out.write(encoded);
        return out.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
