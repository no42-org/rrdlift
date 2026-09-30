/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Parses the XML written by {@code rrdtool dump}. Rows in the dump are oldest first. */
public final class DumpXmlParser {

    private DumpXmlParser() {}

    public static RrdFile parse(InputStream in) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            return parse(factory.createXMLStreamReader(in));
        } catch (XMLStreamException | RuntimeException e) {
            throw new IOException("cannot parse rrdtool dump: " + e.getMessage(), e);
        }
    }

    private static RrdFile parse(XMLStreamReader x) throws XMLStreamException {
        Deque<String> path = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();
        String version = null;
        long step = 0;
        long lastUpdate = 0;
        List<DataSource> dataSources = new ArrayList<>();
        List<Archive> archives = new ArrayList<>();
        // current <ds>
        String name = null, type = null;
        long heartbeat = 0, unknownSec = 0;
        double min = Double.NaN, max = Double.NaN, lastValue = Double.NaN, pdpValue = Double.NaN;
        // current <rra>
        String cf = null;
        long pdpPerRow = 0;
        double xff = Double.NaN;
        List<double[]> rows = new ArrayList<>();
        List<Double> row = new ArrayList<>();

        while (x.hasNext()) {
            int event = x.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                path.addLast(x.getLocalName());
                text.setLength(0);
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                text.append(x.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String p = String.join("/", path);
                String t = text.toString().trim();
                switch (p) {
                    case "rrd/version" -> version = t;
                    case "rrd/step" -> step = Long.parseLong(t);
                    case "rrd/lastupdate" -> lastUpdate = Long.parseLong(t);
                    case "rrd/ds/name" -> name = t;
                    case "rrd/ds/type" -> type = t;
                    case "rrd/ds/minimal_heartbeat" -> heartbeat = Long.parseLong(t);
                    case "rrd/ds/min" -> min = number(t);
                    case "rrd/ds/max" -> max = number(t);
                    case "rrd/ds/last_ds" -> lastValue = number(t);
                    case "rrd/ds/value" -> pdpValue = number(t);
                    case "rrd/ds/unknown_sec" -> unknownSec = Long.parseLong(t);
                    case "rrd/ds" -> dataSources.add(
                            new DataSource(name, type, heartbeat, min, max, lastValue, pdpValue, unknownSec));
                    case "rrd/rra/cf" -> cf = t;
                    case "rrd/rra/pdp_per_row" -> pdpPerRow = Long.parseLong(t);
                    case "rrd/rra/params/xff" -> xff = number(t);
                    case "rrd/rra/database/row/v" -> row.add(number(t));
                    case "rrd/rra/database/row" -> {
                        rows.add(row.stream().mapToDouble(Double::doubleValue).toArray());
                        row.clear();
                    }
                    case "rrd/rra" -> {
                        archives.add(new Archive(cf, pdpPerRow, xff, rows.toArray(new double[0][])));
                        rows.clear();
                    }
                    default -> { }
                }
                path.removeLast();
                text.setLength(0);
            }
        }
        return new RrdFile(version, step, lastUpdate, List.copyOf(dataSources), List.copyOf(archives));
    }

    /** Parses a dump number; {@code NaN}, {@code U} and anything unparsable become NaN. */
    static double number(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
