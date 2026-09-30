/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

import java.nio.charset.StandardCharsets;

/** Port of OpenNMS RrdLabelUtils.computeLabelForRRD: the name of an SNMP interface's resource directory. */
public final class RrdLabels {

    private RrdLabels() {}

    public static String label(String ifName, String ifDescr, String physAddr, boolean preferIfDescr,
                               boolean dontSanitizeIfName) {
        String first = preferIfDescr ? ifDescr : ifName;
        String second = preferIfDescr ? ifName : ifDescr;
        String name = null;
        if (first != null && !first.isEmpty()) {
            name = dontSanitizeIfName ? first : replaceNonAlphanumeric(first);
        } else if (second != null && !second.isEmpty()) {
            name = dontSanitizeIfName ? second : replaceNonAlphanumeric(second);
        }
        String mac = physAddr(physAddr);
        return mac == null ? name : name + '-' + mac;
    }

    private static String replaceNonAlphanumeric(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (!alphanumeric(bytes[i])) {
                bytes[i] = '_';
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String physAddr(String physAddr) {
        if (physAddr == null || physAddr.isEmpty()) {
            return null;
        }
        byte[] bytes = physAddr.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (!(bytes[i] == ' ' || alphanumeric(bytes[i]))) {
                bytes[i] = ' ';
            }
        }
        String trimmed = new String(bytes, StandardCharsets.US_ASCII).trim();
        return trimmed.length() == 12 ? trimmed : null;
    }

    private static boolean alphanumeric(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z');
    }
}
