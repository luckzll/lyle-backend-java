package com.example.aiapp.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

final class ClientIpResolver {

    private final List<Cidr> trustedProxies;

    ClientIpResolver(String trustedProxyCidrs) {
        this.trustedProxies = parseCidrs(trustedProxyCidrs);
    }

    String resolve(HttpServletRequest request) {
        String remoteAddress = normalizeAddress(request.getRemoteAddr());
        if (!isTrusted(remoteAddress)) {
            return remoteAddress;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddress;
        }
        String candidate = normalizeAddress(forwarded.split(",")[0]);
        return isValidAddress(candidate) ? candidate : remoteAddress;
    }

    private boolean isTrusted(String address) {
        if (!isValidAddress(address)) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            return trustedProxies.stream().anyMatch(cidr -> cidr.contains(parsed));
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static List<Cidr> parseCidrs(String value) {
        List<Cidr> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String raw : value.split(",")) {
            String candidate = raw.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                result.add(Cidr.parse(candidate));
            } catch (IllegalArgumentException ignored) {
                // Invalid trust configuration fails closed: the range is not trusted.
            }
        }
        return result;
    }

    private static boolean isValidAddress(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return false;
        }
        // Do not perform DNS lookups for forwarded values.
        return value.matches("[0-9a-fA-F:.]+");
    }

    private static String normalizeAddress(String value) {
        return value == null ? "" : value.trim();
    }

    private record Cidr(byte[] network, int prefixLength) {
        static Cidr parse(String value) {
            String[] parts = value.split("/", 2);
            try {
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int maxBits = address.length * 8;
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : maxBits;
                if (prefix < 0 || prefix > maxBits) {
                    throw new IllegalArgumentException("Invalid CIDR prefix");
                }
                return new Cidr(address, prefix);
            } catch (UnknownHostException | NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid CIDR", exception);
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
