/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.prom;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Hand-written protobuf encoding of the remote-write 1.0 WriteRequest; no generated code. */
public final class RemoteWriteEncoder {

    private RemoteWriteEncoder() {}

    public static byte[] encode(List<TimeSeries> series) {
        try {
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(request);
            for (TimeSeries ts : series) {
                out.writeByteArray(1, timeSeries(ts));
            }
            out.flush();
            return request.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] timeSeries(TimeSeries ts) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(buf);
        for (Map.Entry<String, String> label : new TreeMap<>(ts.labels()).entrySet()) {
            ByteArrayOutputStream l = new ByteArrayOutputStream();
            CodedOutputStream lo = CodedOutputStream.newInstance(l);
            lo.writeString(1, label.getKey());
            lo.writeString(2, label.getValue());
            lo.flush();
            out.writeByteArray(1, l.toByteArray());
        }
        for (int i = 0; i < ts.timesMs().length; i++) {
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            CodedOutputStream so = CodedOutputStream.newInstance(s);
            so.writeDouble(1, ts.values()[i]);
            so.writeInt64(2, ts.timesMs()[i]);
            so.flush();
            out.writeByteArray(2, s.toByteArray());
        }
        out.flush();
        return buf.toByteArray();
    }

    public static List<TimeSeries> decode(byte[] bytes) {
        try {
            List<TimeSeries> out = new ArrayList<>();
            CodedInputStream in = CodedInputStream.newInstance(bytes);
            for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
                if (WireFormat.getTagFieldNumber(tag) == 1) {
                    out.add(decodeSeries(in.readByteArray()));
                } else {
                    in.skipField(tag);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TimeSeries decodeSeries(byte[] bytes) throws IOException {
        Map<String, String> labels = new TreeMap<>();
        List<long[]> times = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            CodedInputStream m = CodedInputStream.newInstance(in.readByteArray());
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                String name = null, value = null;
                for (int t = m.readTag(); t != 0; t = m.readTag()) {
                    if (WireFormat.getTagFieldNumber(t) == 1) {
                        name = m.readString();
                    } else {
                        value = m.readString();
                    }
                }
                labels.put(name, value);
            } else {
                double v = 0;
                long ts = 0;
                for (int t = m.readTag(); t != 0; t = m.readTag()) {
                    if (WireFormat.getTagFieldNumber(t) == 1) {
                        v = m.readDouble();
                    } else {
                        ts = m.readInt64();
                    }
                }
                times.add(new long[] {ts});
                values.add(v);
            }
        }
        return new TimeSeries(labels, times.stream().mapToLong(a -> a[0]).toArray(),
                values.stream().mapToDouble(Double::doubleValue).toArray());
    }
}
