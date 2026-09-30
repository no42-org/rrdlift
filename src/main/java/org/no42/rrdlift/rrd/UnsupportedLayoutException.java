/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import java.io.IOException;

/** An RRDtool file whose binary layout the native reader has not been verified against. */
public class UnsupportedLayoutException extends IOException {
    public UnsupportedLayoutException(String message) {
        super(message);
    }
}
