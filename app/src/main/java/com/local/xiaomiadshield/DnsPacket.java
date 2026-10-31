package com.local.xiaomiadshield;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

final class DnsPacket {
    static final class Query {
        final byte[] dnsPayload;
        final String domain;
        final int sourcePort;
        final byte[] sourceIp;
        Query(byte[] dnsPayload, String domain, int sourcePort, byte[] sourceIp) {
            this.dnsPayload = dnsPayload; this.domain = domain;
            this.sourcePort = sourcePort; this.sourceIp = sourceIp;
        }
    }

    static Query parseIpv4Udp(byte[] packet, int length) {
        if (length < 28 || (packet[0] >> 4) != 4) return null;
        int ihl = (packet[0] & 0x0F) * 4;
        if (ihl < 20 || length < ihl + 8 || (packet[9] & 0xFF) != 17) return null;
        int dstPort = u16(packet, ihl + 2);
        if (dstPort != 53) return null;
        int udpLen = u16(packet, ihl + 4);
        int dnsLen = Math.min(udpLen - 8, length - ihl - 8);
        if (dnsLen < 12) return null;
        byte[] dns = new byte[dnsLen];
        System.arraycopy(packet, ihl + 8, dns, 0, dnsLen);
        String domain = readQName(dns, 12);
        byte[] source = new byte[4];
        System.arraycopy(packet, 12, source, 0, 4);
        return new Query(dns, domain, u16(packet, ihl), source);
    }

    private static String readQName(byte[] dns, int offset) {
        StringBuilder out = new StringBuilder();
        int p = offset;
        while (p < dns.length) {
            int n = dns[p++] & 0xFF;
            if (n == 0) break;
            if (n > 63 || p + n > dns.length) return "";
            if (out.length() > 0) out.append('.');
            for (int i = 0; i < n; i++) out.append((char) (dns[p++] & 0xFF));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    static byte[] blockedResponse(byte[] query) {
        byte[] result = query.clone();
        result[2] = (byte) 0x81;
        result[3] = (byte) 0x83; // response + recursion available + NXDOMAIN
        result[6] = result[7] = result[8] = result[9] = result[10] = result[11] = 0;
        return result;
    }

    static boolean responseReferencesBlockedDomain(byte[] dns) {
        if (dns == null || dns.length < 12) return false;
        int questions = u16(dns, 4);
        int records = u16(dns, 6) + u16(dns, 8) + u16(dns, 10);
        int p = 12;
        for (int i = 0; i < questions; i++) {
            p = skipName(dns, p);
            if (p < 0 || p + 4 > dns.length) return false;
            p += 4;
        }
        for (int i = 0; i < records; i++) {
            p = skipName(dns, p);
            if (p < 0 || p + 10 > dns.length) return false;
            int type = u16(dns, p);
            int dataLength = u16(dns, p + 8);
            int data = p + 10;
            if (data + dataLength > dns.length) return false;
            int nameOffset = type == 5 ? data : ((type == 64 || type == 65) && dataLength > 2 ? data + 2 : -1);
            if (nameOffset >= 0) {
                String target = readCompressedName(dns, nameOffset);
                if (DomainBlocker.isBlocked(target)) return true;
            }
            p = data + dataLength;
        }
        return false;
    }

    private static int skipName(byte[] dns, int offset) {
        int p = offset;
        for (int labels = 0; labels < 128 && p < dns.length; labels++) {
            int length = dns[p++] & 0xFF;
            if (length == 0) return p;
            if ((length & 0xC0) == 0xC0) return p < dns.length ? p + 1 : -1;
            if (length > 63 || p + length > dns.length) return -1;
            p += length;
        }
        return -1;
    }

    private static String readCompressedName(byte[] dns, int offset) {
        StringBuilder out = new StringBuilder();
        int p = offset;
        for (int labels = 0; labels < 128 && p < dns.length; labels++) {
            int length = dns[p++] & 0xFF;
            if (length == 0) return out.toString().toLowerCase(Locale.ROOT);
            if ((length & 0xC0) == 0xC0) {
                if (p >= dns.length) return "";
                p = ((length & 0x3F) << 8) | (dns[p] & 0xFF);
                continue;
            }
            if (length > 63 || p + length > dns.length) return "";
            if (out.length() > 0) out.append('.');
            for (int i = 0; i < length; i++) out.append((char) (dns[p++] & 0xFF));
        }
        return "";
    }

    static byte[] wrapIpv4Udp(byte[] dns, Query query) throws Exception {
        int total = 20 + 8 + dns.length;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 0x45).put((byte) 0).putShort((short) total);
        b.putShort((short) 0).putShort((short) 0x4000).put((byte) 64).put((byte) 17);
        b.putShort((short) 0);
        b.put(InetAddress.getByName("10.7.0.2").getAddress());
        b.put(query.sourceIp);
        b.putShort((short) 53).putShort((short) query.sourcePort);
        b.putShort((short) (8 + dns.length)).putShort((short) 0).put(dns);
        byte[] packet = b.array();
        put16(packet, 10, checksum(packet, 0, 20));
        return packet;
    }

    private static int checksum(byte[] data, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len; i += 2) {
            int high = data[i] & 0xFF;
            int low = i + 1 < off + len ? data[i + 1] & 0xFF : 0;
            sum += (high << 8) | low;
            while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum) & 0xFFFF;
    }

    private static int u16(byte[] p, int o) { return ((p[o] & 0xFF) << 8) | (p[o + 1] & 0xFF); }
    private static void put16(byte[] p, int o, int v) { p[o] = (byte) (v >> 8); p[o + 1] = (byte) v; }
}
