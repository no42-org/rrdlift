/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads RRDtool files directly, following the struct layout in rrd_format.h. */
public final class NativeRrdtoolReader {

    static final double FLOAT_COOKIE = 8.642135E130;
    private static final int UNIVAL = 8;
    private static final int PAR_BYTES = 10 * UNIVAL;

    private NativeRrdtoolReader() {}

    public static RrdFile read(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file));
        if (b.capacity() < 32 || !cstr(b, 0, 4).equals("RRD")) {
            throw new IOException(file + ": not an RRDtool file");
        }
        String version = cstr(b, 4, 5);
        int cookieAt = -1;
        int word = 0;
        ByteOrder order = null;
        for (int a : new int[] {8, 4}) {
            for (ByteOrder o : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
                int at = align(9, a);
                if (cookieAt < 0 && b.order(o).getDouble(at) == FLOAT_COOKIE) {
                    cookieAt = at;
                    word = a;
                    order = o;
                }
            }
        }
        if (cookieAt < 0) {
            throw new UnsupportedLayoutException(file + ": float cookie not found, unknown byte order or alignment");
        }
        if (!version.equals("0003") || order != ByteOrder.LITTLE_ENDIAN || word != 8) {
            throw new UnsupportedLayoutException(String.format(
                    "%s: layout version %s, %s, %d-byte word is not verified", file, version, order, word));
        }
        b.order(order);
        try {
            return parse(b, cookieAt + 8, version, file);
        } catch (IndexOutOfBoundsException e) {
            throw new IOException(file + ": file ends early, parsed beyond " + b.capacity() + " bytes", e);
        }
    }

    private static RrdFile parse(ByteBuffer b, int p, String version, Path file) throws IOException {
        long dsCount = b.getLong(p);
        long rraCount = b.getLong(p + 8);
        long step = b.getLong(p + 16);
        p += 24 + PAR_BYTES;
        long cap = b.capacity();
        if (dsCount < 0 || rraCount < 0 || dsCount > cap / 120 || rraCount > cap / 120
                || p + dsCount * 120 + rraCount * 120 + 16 + dsCount * 112 + rraCount * dsCount * PAR_BYTES
                        + rraCount * 8 > cap) {
            throw countsDoNotFit(file);
        }
        int ds = (int) dsCount;
        int rra = (int) rraCount;

        String[] name = new String[ds];
        String[] type = new String[ds];
        long[] heartbeat = new long[ds];
        double[] min = new double[ds];
        double[] max = new double[ds];
        for (int i = 0; i < ds; i++) {
            name[i] = cstr(b, p, 20);
            type[i] = cstr(b, p + 20, 20);
            int par = align(p + 40, 8);
            heartbeat[i] = b.getLong(par);
            min[i] = b.getDouble(par + 8);
            max[i] = b.getDouble(par + 16);
            p = par + PAR_BYTES;
        }

        String[] cf = new String[rra];
        long[] rowCnt = new long[rra];
        long[] pdpCnt = new long[rra];
        double[] xff = new double[rra];
        for (int i = 0; i < rra; i++) {
            cf[i] = cstr(b, p, 20);
            int q = align(p + 20, 8); // 4 padding bytes after cf_nam[20]
            rowCnt[i] = b.getLong(q);
            pdpCnt[i] = b.getLong(q + 8);
            xff[i] = b.getDouble(q + 16);
            p = q + 16 + PAR_BYTES;
        }
        long rowsStart = (long) p + 16 + (long) ds * 112 + (long) rra * ds * PAR_BYTES + (long) rra * 8;
        long totalRows = 0;
        for (long n : rowCnt) {
            if (n < 0 || n > Integer.MAX_VALUE) {
                throw countsDoNotFit(file);
            }
            totalRows += n;
        }
        try {
            if (Math.addExact(rowsStart, Math.multiplyExact(totalRows, (long) ds * 8)) > cap) {
                throw countsDoNotFit(file);
            }
        } catch (ArithmeticException e) {
            throw countsDoNotFit(file);
        }

        long lastUpdate = b.getLong(p);
        p += 16; // last_up, last_up_usec

        List<DataSource> dataSources = new ArrayList<>(ds);
        for (int i = 0; i < ds; i++) {
            double lastValue = DumpXmlParser.number(cstr(b, p, 30));
            int s = align(p + 30, 8);
            long unknownSec = b.getLong(s);
            double pdpValue = b.getDouble(s + 8);
            dataSources.add(new DataSource(name[i], type[i], heartbeat[i], min[i], max[i],
                    lastValue, pdpValue, unknownSec));
            p = s + PAR_BYTES;
        }

        p += rra * ds * PAR_BYTES; // cdp_prep_t

        long[] curRow = new long[rra];
        for (int i = 0; i < rra; i++) {
            curRow[i] = b.getLong(p);
            p += 8;
        }

        List<Archive> archives = new ArrayList<>(rra);
        for (int i = 0; i < rra; i++) {
            int n = (int) rowCnt[i];
            double[][] ring = new double[n][ds];
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < ds; k++) {
                    ring[j][k] = b.getDouble(p);
                    p += 8;
                }
            }
            double[][] rows = new double[n][];
            for (int j = 0; j < n; j++) {
                rows[j] = ring[(int) ((curRow[i] + 1 + j) % n)];
            }
            archives.add(new Archive(cf[i], pdpCnt[i], xff[i], rows));
        }

        if (p != b.capacity()) {
            throw new IOException(file + ": parsed " + p + " of " + b.capacity() + " bytes");
        }
        return new RrdFile(version, step, lastUpdate, List.copyOf(dataSources), List.copyOf(archives));
    }

    private static IOException countsDoNotFit(Path file) {
        return new IOException(file + ": header counts do not fit the file size");
    }

    private static int align(int pos, int a) {
        return (pos + a - 1) / a * a;
    }

    private static String cstr(ByteBuffer b, int pos, int len) {
        byte[] x = new byte[len];
        b.get(pos, x);
        int n = 0;
        while (n < len && x[n] != 0) {
            n++;
        }
        return new String(x, 0, n, StandardCharsets.US_ASCII);
    }
}
