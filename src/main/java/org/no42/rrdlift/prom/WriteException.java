/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.prom;

import java.io.IOException;

/** A non-2xx answer to a remote write. 429 and 5xx are worth retrying; other 4xx are not. */
public class WriteException extends IOException {

    private final int status;
    private final String body;

    public WriteException(int status, String body) {
        super("remote write failed with HTTP " + status + ": " + body);
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    public boolean retryable() {
        return status == 429 || status >= 500;
    }
}
