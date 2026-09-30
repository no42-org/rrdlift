/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jrobin.core.RrdDb;
import org.jrobin.core.RrdException;

/** Reads JRobin files with JRobin itself, read-only. */
public final class JrobinReader {

    private JrobinReader() {}

    public static RrdFile read(Path file) throws IOException {
        RrdDb db;
        try {
            db = new RrdDb(file.toString(), true);
        } catch (RrdException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
        try {
            int dsCount = db.getDsCount();
            List<DataSource> dataSources = new ArrayList<>(dsCount);
            for (int i = 0; i < dsCount; i++) {
                org.jrobin.core.Datasource d = db.getDatasource(i);
                dataSources.add(new DataSource(d.getDsName(), d.getDsType(), d.getHeartbeat(),
                        d.getMinValue(), d.getMaxValue(), d.getLastValue(), d.getAccumValue(), d.getNanSeconds()));
            }
            List<Archive> archives = new ArrayList<>(db.getArcCount());
            for (int a = 0; a < db.getArcCount(); a++) {
                org.jrobin.core.Archive arc = db.getArchive(a);
                double[][] rows = new double[arc.getRows()][dsCount];
                for (int i = 0; i < dsCount; i++) {
                    double[] values = arc.getRobin(i).getValues(); // oldest first
                    for (int j = 0; j < values.length; j++) {
                        rows[j][i] = values[j];
                    }
                }
                archives.add(new Archive(arc.getConsolFun(), arc.getSteps(), arc.getXff(), rows));
            }
            return new RrdFile(db.getHeader().getSignature(), db.getHeader().getStep(),
                    db.getLastUpdateTime(), List.copyOf(dataSources), List.copyOf(archives));
        } finally {
            db.close();
        }
    }
}
